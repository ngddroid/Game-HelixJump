package com.ngstudio.spindledrop.game.render

import android.opengl.GLES30.*
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.floor

/**
 * Bakes a world's procedural surface detail into two textures once, so the per-pixel ring
 * shader only needs two texture reads instead of expensive noise (keeps 60 fps on entry GPUs).
 */
class PatternBaker {
    var texA = 0
        private set
    var texB = 0
        private set
    var noiseTex = 0
        private set
    private var fbo = 0
    private var size = 0
    private var bakedPattern = -1
    private var bakedKey = ""
    private val programs = HashMap<Int, Program>()

    fun init() {
        noiseTex = createNoise(256)
    }

    fun invalidate() { bakedKey = "" }

    /** Bakes if [pattern]/[key] changed. Leaves framebuffer 0 bound. */
    fun ensure(pattern: Int, key: String, accent: FloatArray, wantSize: Int, emptyVao: Int) {
        val k = "$pattern|$key|$wantSize"
        if (k == bakedKey && texA != 0) return
        if (size != wantSize || texA == 0) allocate(wantSize)
        val prog = programs.getOrPut(pattern) { Program(Shaders.FULLSCREEN_VS, Shaders.bakeFs(pattern), "bake$pattern") }
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glDrawBuffers(2, intArrayOf(GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1), 0)
        glViewport(0, 0, size, size)
        glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND); glDisable(GL_CULL_FACE)
        prog.use()
        prog.v3("uAccent", accent)
        glBindVertexArray(emptyVao)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glBindVertexArray(0)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        for (t in intArrayOf(texA, texB)) {
            glBindTexture(GL_TEXTURE_2D, t)
            glGenerateMipmap(GL_TEXTURE_2D)
        }
        glEnable(GL_DEPTH_TEST); glEnable(GL_CULL_FACE)
        bakedPattern = pattern
        bakedKey = k
        Log.i(Gl.TAG, "Baked surface pattern $pattern at ${size}px")
    }

    private fun allocate(s: Int) {
        release()
        size = s
        texA = tex(s); texB = tex(s)
        fbo = Gl.gen { n, a, o -> glGenFramebuffers(n, a, o) }
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texA, 0)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, texB, 0)
        val st = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        if (st != GL_FRAMEBUFFER_COMPLETE) Log.w(Gl.TAG, "Bake FBO incomplete 0x${Integer.toHexString(st)}")
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    private fun tex(s: Int): Int {
        val t = Gl.gen { n, a, o -> glGenTextures(n, a, o) }
        glBindTexture(GL_TEXTURE_2D, t)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, s, s, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        return t
    }

    /** Tileable 2-octave value noise, R8, repeat wrap. */
    private fun createNoise(n: Int): Int {
        val grid1 = 8
        val grid2 = 32
        val r = java.util.Random(1234)
        val g1 = FloatArray(grid1 * grid1) { r.nextFloat() }
        val g2 = FloatArray(grid2 * grid2) { r.nextFloat() }
        fun sample(g: FloatArray, gs: Int, x: Float, y: Float): Float {
            val fx = x * gs; val fy = y * gs
            val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
            val tx = fx - x0; val ty = fy - y0
            val sx = tx * tx * (3 - 2 * tx); val sy = ty * ty * (3 - 2 * ty)
            fun at(i: Int, j: Int) = g[((j % gs + gs) % gs) * gs + ((i % gs + gs) % gs)]
            val a = at(x0, y0); val b = at(x0 + 1, y0); val c = at(x0, y0 + 1); val d = at(x0 + 1, y0 + 1)
            return (a + (b - a) * sx) + ((c + (d - c) * sx) - (a + (b - a) * sx)) * sy
        }
        val buf = ByteBuffer.allocateDirect(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val u = x.toFloat() / n; val v = y.toFloat() / n
            val value = sample(g1, grid1, u, v) * 0.65f + sample(g2, grid2, u, v) * 0.35f
            buf.put((value.coerceIn(0f, 1f) * 255).toInt().toByte())
        }
        buf.position(0)
        val t = Gl.gen { c, a, o -> glGenTextures(c, a, o) }
        glBindTexture(GL_TEXTURE_2D, t)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, n, n, 0, GL_RED, GL_UNSIGNED_BYTE, buf)
        glGenerateMipmap(GL_TEXTURE_2D)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT)
        return t
    }

    fun release() {
        if (fbo != 0) glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        val t = intArrayOf(texA, texB).filter { it != 0 }.toIntArray()
        if (t.isNotEmpty()) glDeleteTextures(t.size, t, 0)
        fbo = 0; texA = 0; texB = 0
    }

    fun clearPrograms() { programs.clear(); bakedKey = "" ; texA = 0; texB = 0; fbo = 0; size = 0 }
}
