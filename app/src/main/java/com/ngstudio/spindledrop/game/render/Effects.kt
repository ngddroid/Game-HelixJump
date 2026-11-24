package com.ngstudio.spindledrop.game.render

import android.graphics.Color
import android.opengl.GLES30.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Quad + per-instance buffer (10 floats per instance) used by particles and decals. */
class InstanceBatch(private val maxInstances: Int) {
    private val vao = Gl.gen { n, a, o -> glGenVertexArrays(n, a, o) }
    private val quadVbo = Gl.gen { n, a, o -> glGenBuffers(n, a, o) }
    private val ibo = Gl.gen { n, a, o -> glGenBuffers(n, a, o) }
    private val instVbo = Gl.gen { n, a, o -> glGenBuffers(n, a, o) }
    val data = FloatArray(maxInstances * FLOATS)
    private val buf: FloatBuffer = ByteBuffer.allocateDirect(maxInstances * FLOATS * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    init {
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, quadVbo)
        val q = floatArrayOf(-0.5f, -0.5f, 0f, 0.5f, -0.5f, 0f, 0.5f, 0.5f, 0f, -0.5f, 0.5f, 0f)
        glBufferData(GL_ARRAY_BUFFER, q.size * 4, Gl.floatBuffer(q), GL_STATIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo)
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, 6 * 4, Gl.intBuffer(intArrayOf(0, 1, 2, 0, 2, 3)), GL_STATIC_DRAW)
        glBindBuffer(GL_ARRAY_BUFFER, instVbo)
        glBufferData(GL_ARRAY_BUFFER, maxInstances * FLOATS * 4, null, GL_DYNAMIC_DRAW)
        val stride = FLOATS * 4
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 4, GL_FLOAT, false, stride, 0); glVertexAttribDivisor(2, 1)
        glEnableVertexAttribArray(3); glVertexAttribPointer(3, 4, GL_FLOAT, false, stride, 16); glVertexAttribDivisor(3, 1)
        glEnableVertexAttribArray(4); glVertexAttribPointer(4, 2, GL_FLOAT, false, stride, 32); glVertexAttribDivisor(4, 1)
        glBindVertexArray(0)
    }

    fun draw(count: Int) {
        if (count <= 0) return
        val n = min(count, maxInstances)
        buf.position(0)
        buf.put(data, 0, n * FLOATS)
        buf.position(0)
        glBindBuffer(GL_ARRAY_BUFFER, instVbo)
        glBufferSubData(GL_ARRAY_BUFFER, 0, n * FLOATS * 4, buf)
        glBindVertexArray(vao)
        glDrawElementsInstanced(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0, n)
        glBindVertexArray(0)
    }

    companion object { const val FLOATS = 10 }
}

/** CPU particle simulation (structure of arrays, no allocations per frame). */
class ParticleSystem(private val cap: Int = 1800) {
    private val px = FloatArray(cap); private val py = FloatArray(cap); private val pz = FloatArray(cap)
    private val vx = FloatArray(cap); private val vy = FloatArray(cap); private val vz = FloatArray(cap)
    private val life = FloatArray(cap); private val maxLife = FloatArray(cap)
    private val s0 = FloatArray(cap); private val s1 = FloatArray(cap)
    private val cr = FloatArray(cap); private val cg = FloatArray(cap); private val cb = FloatArray(cap)
    private val a0 = FloatArray(cap); private val a1 = FloatArray(cap)
    private val rot = FloatArray(cap); private val rotV = FloatArray(cap)
    private val tile = FloatArray(cap); private val grav = FloatArray(cap); private val drag = FloatArray(cap)
    private val add = BooleanArray(cap)
    var count = 0
        private set
    var budget = 1f
    val rnd = Random(7)

    // One-frame sprites (power-up icons etc.).
    private val statics = FloatArray(64 * InstanceBatch.FLOATS)
    private var staticCount = 0

    fun emit(
        x: Float, y: Float, z: Float, vx0: Float, vy0: Float, vz0: Float,
        lifeS: Float, size0: Float, size1: Float, color: Int, alpha0: Float, alpha1: Float,
        tileIdx: Float, gravity: Float = 0f, dragK: Float = 0f, additive: Boolean = false,
        rotation: Float = rnd.nextFloat() * 6.283f, rotVel: Float = 0f,
    ) {
        if (count >= cap) return
        val i = count++
        px[i] = x; py[i] = y; pz[i] = z; vx[i] = vx0; vy[i] = vy0; vz[i] = vz0
        life[i] = 0f; maxLife[i] = lifeS; s0[i] = size0; s1[i] = size1
        cr[i] = Color.red(color) / 255f; cg[i] = Color.green(color) / 255f; cb[i] = Color.blue(color) / 255f
        a0[i] = alpha0; a1[i] = alpha1; rot[i] = rotation; rotV[i] = rotVel; tile[i] = tileIdx
        grav[i] = gravity; drag[i] = dragK; add[i] = additive
    }

    /** Respects the quality budget: returns how many of [n] to actually emit. */
    fun scaled(n: Int): Int = max(1, (n * budget).toInt())

    fun addStatic(x: Float, y: Float, z: Float, size: Float, color: Int, alpha: Float, tileIdx: Float, rotation: Float = 0f) {
        if (staticCount >= 64) return
        val o = staticCount++ * InstanceBatch.FLOATS
        statics[o] = x; statics[o + 1] = y; statics[o + 2] = z; statics[o + 3] = size
        statics[o + 4] = Color.red(color) / 255f; statics[o + 5] = Color.green(color) / 255f; statics[o + 6] = Color.blue(color) / 255f; statics[o + 7] = alpha
        statics[o + 8] = rotation; statics[o + 9] = tileIdx
    }

    fun update(dt: Float) {
        var i = 0
        while (i < count) {
            life[i] += dt
            if (life[i] >= maxLife[i]) {
                kill(i)
                continue
            }
            val dk = 1f - min(1f, drag[i] * dt)
            vx[i] *= dk; vy[i] = vy[i] * dk - grav[i] * dt; vz[i] *= dk
            px[i] += vx[i] * dt; py[i] += vy[i] * dt; pz[i] += vz[i] * dt
            rot[i] += rotV[i] * dt
            i++
        }
    }

    private fun kill(i: Int) {
        val j = --count
        if (i == j) return
        px[i] = px[j]; py[i] = py[j]; pz[i] = pz[j]; vx[i] = vx[j]; vy[i] = vy[j]; vz[i] = vz[j]
        life[i] = life[j]; maxLife[i] = maxLife[j]; s0[i] = s0[j]; s1[i] = s1[j]
        cr[i] = cr[j]; cg[i] = cg[j]; cb[i] = cb[j]; a0[i] = a0[j]; a1[i] = a1[j]
        rot[i] = rot[j]; rotV[i] = rotV[j]; tile[i] = tile[j]; grav[i] = grav[j]; drag[i] = drag[j]; add[i] = add[j]
    }

    fun clear() { count = 0; staticCount = 0 }

    /** Writes instances of one blend group into [out]; returns the instance count. */
    fun fill(additive: Boolean, out: FloatArray, includeStatics: Boolean): Int {
        var n = 0
        val maxN = out.size / InstanceBatch.FLOATS
        for (i in 0 until count) {
            if (add[i] != additive) continue
            if (n >= maxN) break
            val t = life[i] / maxLife[i]
            val o = n * InstanceBatch.FLOATS
            out[o] = px[i]; out[o + 1] = py[i]; out[o + 2] = pz[i]
            out[o + 3] = s0[i] + (s1[i] - s0[i]) * t
            out[o + 4] = cr[i]; out[o + 5] = cg[i]; out[o + 6] = cb[i]
            // Fade in quickly, then towards alpha1.
            val fadeIn = min(1f, t * 12f)
            out[o + 7] = (a0[i] + (a1[i] - a0[i]) * t) * fadeIn
            out[o + 8] = rot[i]; out[o + 9] = tile[i]
            n++
        }
        if (includeStatics && staticCount > 0) {
            val k = min(staticCount, maxN - n)
            System.arraycopy(statics, 0, out, n * InstanceBatch.FLOATS, k * InstanceBatch.FLOATS)
            n += k
            staticCount = 0
        }
        return n
    }

    /** Writes only this frame's static sprites and clears them. */
    fun fillStatics(out: FloatArray): Int {
        val k = min(staticCount, out.size / InstanceBatch.FLOATS)
        System.arraycopy(statics, 0, out, 0, k * InstanceBatch.FLOATS)
        staticCount = 0
        return k
    }
}

/** Splat decals stored per ring, in ring-local coordinates. */
class DecalSystem {
    class Splat(val x: Float, val z: Float, val size: Float, val rot: Float, val color: Int, val tile: Int, var age: Float)

    val byRing = HashMap<Int, ArrayList<Splat>>()
    private val rnd = Random(11)

    fun add(ring: Int, localAngleDeg: Float, radius: Float, color: Int, tile: Int, big: Boolean) {
        val list = byRing.getOrPut(ring) { ArrayList() }
        if (list.size >= 14) list.removeAt(0)
        val a = Math.toRadians(localAngleDeg.toDouble())
        val r = radius + (rnd.nextFloat() - 0.5f) * 0.08f
        val size = (if (big) 0.62f else 0.40f) * (0.85f + rnd.nextFloat() * 0.3f)
        list += Splat((kotlin.math.cos(a) * r).toFloat(), (kotlin.math.sin(a) * r).toFloat(), size, rnd.nextFloat() * 6.283f, color, tile, 0f)
    }

    fun update(dt: Float) {
        for (l in byRing.values) for (s in l) s.age += dt
    }

    fun removeBelow(minRing: Int) {
        byRing.keys.removeAll { it < minRing }
    }

    fun clear() = byRing.clear()

    /** Fills instance data for one ring; returns count. */
    fun fill(ring: Int, out: FloatArray): Int {
        val l = byRing[ring] ?: return 0
        var n = 0
        for (s in l) {
            val o = n * InstanceBatch.FLOATS
            // Splats "bloom" open in the first 0.12 s.
            val grow = min(1f, 0.35f + s.age / 0.12f * 0.65f)
            out[o] = s.x; out[o + 1] = s.z; out[o + 2] = s.size * grow; out[o + 3] = s.rot
            out[o + 4] = Color.red(s.color) / 255f; out[o + 5] = Color.green(s.color) / 255f; out[o + 6] = Color.blue(s.color) / 255f
            out[o + 7] = 0.95f
            out[o + 8] = s.tile.toFloat(); out[o + 9] = min(1f, s.age / 3f)
            n++
        }
        return n
    }
}

/** Vertical ribbon that follows the falling ball. */
class TrailRenderer {
    private val maxPts = 48
    private val ys = FloatArray(maxPts)
    private val ages = FloatArray(maxPts)
    private var n = 0
    private val vao = Gl.gen { c, a, o -> glGenVertexArrays(c, a, o) }
    private val vbo = Gl.gen { c, a, o -> glGenBuffers(c, a, o) }
    private val verts = FloatArray(maxPts * 2 * 7)
    private val fb: FloatBuffer = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    init {
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, verts.size * 4, null, GL_DYNAMIC_DRAW)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 28, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 4, GL_FLOAT, false, 28, 12)
        glBindVertexArray(0)
    }

    fun reset() { n = 0 }

    fun push(y: Float, dt: Float) {
        for (i in 0 until n) ages[i] += dt
        // Drop old points.
        while (n > 0 && ages[n - 1] > 0.32f) n--
        if (n == maxPts) n--
        for (i in n downTo 1) { ys[i] = ys[i - 1]; ages[i] = ages[i - 1] }
        ys[0] = y; ages[0] = 0f
        n++
    }

    /**
     * Draws the ribbon at x=0, z=[z] with [width]. [colorAt] gives the colour for a 0..1 position along the trail.
     */
    fun draw(z: Float, width: Float, intensity: Float, colorAt: (Float) -> Int) {
        if (n < 2 || intensity <= 0.01f) return
        var k = 0
        for (i in 0 until n) {
            val t = i.toFloat() / (n - 1)
            val w = width * (1f - t * 0.75f)
            val c = colorAt(t)
            val a = (1f - t) * (1f - t) * intensity * (Color.alpha(c) / 255f)
            val r = Color.red(c) / 255f; val g = Color.green(c) / 255f; val b = Color.blue(c) / 255f
            for (side in 0..1) {
                val x = if (side == 0) -w else w
                verts[k++] = x; verts[k++] = ys[i]; verts[k++] = z
                verts[k++] = r; verts[k++] = g; verts[k++] = b; verts[k++] = a
            }
        }
        fb.position(0); fb.put(verts, 0, k); fb.position(0)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferSubData(GL_ARRAY_BUFFER, 0, k * 4, fb)
        glBindVertexArray(vao)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, n * 2)
        glBindVertexArray(0)
    }
}
