package com.ngstudio.spindledrop.game.render

import android.opengl.GLES30.*
import com.ngstudio.spindledrop.game.theme.Shape
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pre-computes the three parallax skyline layers of a world as a periodic 1D texture
 * (R = far, G = mid, B = near heights, A = lit-window columns). The sky shader then needs
 * only one texture read per layer, so it can run at full resolution with crisp edges.
 */
class SkyProfiles {
    private var tex = 0
    private var bakedShape = -1

    fun texture(shape: Int): Int {
        if (tex == 0) {
            tex = Gl.gen { n, a, o -> glGenTextures(n, a, o) }
            glBindTexture(GL_TEXTURE_2D, tex)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            bakedShape = -1
        }
        if (shape != bakedShape) {
            val buf = ByteBuffer.allocateDirect(WIDTH * 4)
            val far = layer(shape, 11, 1.0f)
            val mid = layer(shape, 23, 1.4f)
            val near = layer(shape, 37, 1.9f)
            val lights = windows(shape)
            for (i in 0 until WIDTH) {
                buf.put(b(far[i])); buf.put(b(mid[i])); buf.put(b(near[i])); buf.put(b(lights[i]))
            }
            buf.position(0)
            glBindTexture(GL_TEXTURE_2D, tex)
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, WIDTH, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf)
            bakedShape = shape
        }
        return tex
    }

    fun reset() { tex = 0; bakedShape = -1 }

    companion object {
        const val WIDTH = 1024
    }

    private fun b(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()

    /** Periodic value noise on [0,1) with [cells] cells. */
    private class PNoise(seed: Int, val cells: Int) {
        private val v = FloatArray(cells) { hash(seed * 7919 + it * 104729) }
        fun at(u: Float): Float {
            val x = (u - floor(u)) * cells
            val i = x.toInt() % cells
            val f = x - floor(x)
            val s = f * f * (3 - 2 * f)
            return v[i] + (v[(i + 1) % cells] - v[i]) * s
        }
        fun cell(u: Float): Float = v[(((u - floor(u)) * cells).toInt()) % cells]
        companion object {
            fun hash(n: Int): Float {
                var x = n
                x = (x xor (x ushr 16)) * 0x45d9f3b
                x = (x xor (x ushr 16)) * 0x45d9f3b
                x = x xor (x ushr 16)
                return (x and 0xFFFFFF) / 16777215f
            }
        }
    }

    private fun layer(shape: Int, seed: Int, detail: Float): FloatArray {
        val out = FloatArray(WIDTH)
        val n1 = PNoise(seed, (6 * detail).toInt().coerceAtLeast(3))
        val n2 = PNoise(seed + 1, (14 * detail).toInt())
        val n3 = PNoise(seed + 2, (32 * detail).toInt())
        val blocks = PNoise(seed + 3, (18 * detail).toInt())
        val tall = PNoise(seed + 4, (18 * detail).toInt())
        for (i in 0 until WIDTH) {
            val u = i.toFloat() / WIDTH
            val h = when (shape) {
                Shape.MOUNTAINS -> {
                    fun ridge(n: PNoise) = 1f - abs(n.at(u) * 2f - 1f)
                    ridge(n1) * 0.55f + ridge(n2) * 0.3f + ridge(n3) * 0.15f
                }
                Shape.CITY -> {
                    val base = 0.25f + blocks.cell(u) * 0.6f
                    val w = (u * blocks.cells) - floor(u * blocks.cells)
                    val spire = if (tall.cell(u) > 0.85f && abs(w - 0.5f) < 0.08f) 0.3f else 0f
                    base + spire
                }
                Shape.DUNES -> 0.45f + 0.25f * sin((u * 3 + seed) * 2 * PI).toFloat() + 0.18f * sin((u * 7 + seed * 0.3f) * 2 * PI).toFloat() * n1.at(u)
                Shape.SPIRES -> {
                    val c = (u * blocks.cells) - floor(u * blocks.cells)
                    (0.25f + blocks.cell(u) * 0.75f) * (1f - abs(c - 0.5f) * 2f) + 0.08f
                }
                Shape.CORAL -> {
                    val k = n2.at(u)
                    0.35f + 0.35f * k * k + 0.15f * n3.at(u) + 0.1f * max(0f, sin(u * 40 * PI.toFloat()))
                }
                Shape.CLOUDS -> {
                    var hh = 0f
                    for (j in 0 until 4) {
                        val cells = 5 + j * 2
                        val x = u * cells + j * 0.37f
                        val cx = x - floor(x) - 0.5f
                        val r = 0.5f + PNoise.hash(seed * 31 + j * 97 + floor(x).toInt()) * 0.5f
                        hh = max(hh, sqrt(max(0f, 0.25f - cx * cx)) * 1.8f * r)
                    }
                    hh + 0.1f
                }
                Shape.RUINS -> {
                    val c = (u * blocks.cells) - floor(u * blocks.cells)
                    val hgt = 0.25f + blocks.cell(u) * 0.45f
                    val step3 = floor(c * 4f) / 4f
                    val pyramid = hgt * (1f - abs(step3 - 0.375f) * 1.6f)
                    val column = if (tall.cell(u) > 0.75f && abs(c - 0.5f) < 0.06f) 0.55f else 0f
                    max(pyramid, column) + 0.05f
                }
                Shape.PALMS -> {
                    val c = (u * blocks.cells) - floor(u * blocks.cells)
                    val palm = tall.cell(u) > 0.5f
                    val trunk = if (palm && abs(c - 0.5f) < 0.025f) 0.75f else 0f
                    val crown = if (palm && abs(c - 0.5f) < 0.16f) 0.7f + 0.08f * sin(c * 40f) else 0f
                    max(0.12f + 0.02f * n3.at(u), max(trunk, crown))
                }
                else -> 0f
            }
            out[i] = (h / 1.25f).coerceIn(0f, 1f)
        }
        return out
    }

    private fun windows(shape: Int): FloatArray {
        val out = FloatArray(WIDTH)
        if (shape != Shape.CITY && shape != Shape.SPIRES) return out
        for (i in 0 until WIDTH) {
            val col = (i / 4)
            out[i] = if (PNoise.hash(col * 131 + 7) > 0.45f && (i % 4) in 1..2) 1f else 0f
        }
        return out
    }
}
