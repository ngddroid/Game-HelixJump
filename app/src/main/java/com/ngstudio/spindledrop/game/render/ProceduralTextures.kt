package com.ngstudio.spindledrop.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.opengl.GLES30.*
import android.opengl.GLUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Builds every texture the renderer needs from code (no image files). */
object ProceduralTextures {

    /** Particle tiles (4x4 atlas). */
    object Tile {
        const val DOT = 0f
        const val DROP = 1f
        const val STAR = 2f
        const val HEART = 3f
        const val SPARKLE = 4f
        const val BUBBLE = 5f
        const val SNOW = 6f
        const val LEAF = 7f
        const val PETAL = 8f
        const val NOTE = 9f
        const val CONFETTI = 10f
        const val SHARD = 11f
        const val SHIELD = 12f
        const val MAGNET = 13f
        const val HOURGLASS = 14f
        const val DRILL = 15f
    }

    fun upload(bmp: Bitmap, mipmaps: Boolean): Int {
        val id = Gl.gen { n, a, o -> glGenTextures(n, a, o) }
        glBindTexture(GL_TEXTURE_2D, id)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0)
        if (mipmaps) {
            glGenerateMipmap(GL_TEXTURE_2D)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        } else {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        bmp.recycle()
        return id
    }

    private fun paint() = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }

    fun particleAtlas(): Bitmap {
        val size = 512
        val cell = size / 4f
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        fun at(i: Int, block: (Float, Float, Float) -> Unit) {
            val cx = (i % 4) * cell + cell / 2
            val cy = (i / 4) * cell + cell / 2
            cv.save()
            cv.clipRect(cx - cell / 2, cy - cell / 2, cx + cell / 2, cy + cell / 2)
            block(cx, cy, cell * 0.5f)
            cv.restore()
        }
        // 0 soft dot
        at(0) { x, y, r ->
            val p = paint()
            p.shader = RadialGradient(x, y, r * 0.95f, intArrayOf(Color.WHITE, Color.argb(160, 255, 255, 255), Color.TRANSPARENT), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            cv.drawCircle(x, y, r, p)
        }
        // 1 droplet
        at(1) { x, y, r ->
            val path = Path()
            path.moveTo(x, y - r * 0.8f)
            path.cubicTo(x + r * 0.55f, y - r * 0.1f, x + r * 0.55f, y + r * 0.6f, x, y + r * 0.6f)
            path.cubicTo(x - r * 0.55f, y + r * 0.6f, x - r * 0.55f, y - r * 0.1f, x, y - r * 0.8f)
            cv.drawPath(path, paint())
        }
        // 2 star
        at(2) { x, y, r -> cv.drawPath(starPath(x, y, r * 0.85f, r * 0.38f, 5), paint()) }
        // 3 heart
        at(3) { x, y, r -> cv.drawPath(heartPath(x, y, r * 0.8f), paint()) }
        // 4 sparkle (4-point star with glow)
        at(4) { x, y, r ->
            val g = paint()
            g.shader = RadialGradient(x, y, r * 0.6f, intArrayOf(Color.argb(170, 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            cv.drawCircle(x, y, r * 0.6f, g)
            cv.drawPath(starPath(x, y, r * 0.95f, r * 0.12f, 4), paint())
        }
        // 5 bubble ring
        at(5) { x, y, r ->
            val p = paint()
            p.shader = RadialGradient(x, y, r * 0.8f, intArrayOf(Color.argb(30, 255, 255, 255), Color.argb(90, 255, 255, 255), Color.argb(230, 255, 255, 255), Color.TRANSPARENT), floatArrayOf(0f, 0.75f, 0.9f, 1f), Shader.TileMode.CLAMP)
            cv.drawCircle(x, y, r * 0.8f, p)
            val h = paint(); h.alpha = 220
            cv.drawOval(RectF(x - r * 0.45f, y - r * 0.5f, x - r * 0.15f, y - r * 0.3f), h)
        }
        // 6 snowflake
        at(6) { x, y, r ->
            val p = paint(); p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.12f; p.strokeCap = Paint.Cap.ROUND
            for (k in 0 until 6) {
                val a = k * PI / 3
                val ex = x + cos(a).toFloat() * r * 0.8f
                val ey = y + sin(a).toFloat() * r * 0.8f
                cv.drawLine(x, y, ex, ey, p)
                val mx = x + cos(a).toFloat() * r * 0.5f
                val my = y + sin(a).toFloat() * r * 0.5f
                for (s in listOf(-1, 1)) {
                    val b = a + s * PI / 4
                    cv.drawLine(mx, my, mx + cos(b).toFloat() * r * 0.22f, my + sin(b).toFloat() * r * 0.22f, p)
                }
            }
        }
        // 7 leaf
        at(7) { x, y, r ->
            val path = Path()
            path.moveTo(x - r * 0.75f, y + r * 0.2f)
            path.quadTo(x - r * 0.1f, y - r * 0.9f, x + r * 0.75f, y - r * 0.2f)
            path.quadTo(x + r * 0.1f, y + r * 0.9f, x - r * 0.75f, y + r * 0.2f)
            cv.drawPath(path, paint())
            val v = paint(); v.color = Color.argb(120, 0, 0, 0); v.style = Paint.Style.STROKE; v.strokeWidth = r * 0.06f
            cv.drawLine(x - r * 0.65f, y + r * 0.18f, x + r * 0.65f, y - r * 0.18f, v)
        }
        // 8 petal
        at(8) { x, y, r ->
            val path = Path()
            path.moveTo(x, y + r * 0.75f)
            path.cubicTo(x - r * 0.9f, y + r * 0.1f, x - r * 0.4f, y - r * 0.8f, x, y - r * 0.45f)
            path.cubicTo(x + r * 0.4f, y - r * 0.8f, x + r * 0.9f, y + r * 0.1f, x, y + r * 0.75f)
            cv.drawPath(path, paint())
        }
        // 9 music note
        at(9) { x, y, r ->
            val p = paint()
            cv.drawOval(RectF(x - r * 0.55f, y + r * 0.2f, x + r * 0.05f, y + r * 0.65f), p)
            cv.drawRect(x - r * 0.05f, y - r * 0.7f, x + r * 0.07f, y + r * 0.45f, p)
            val f = Path(); f.moveTo(x + r * 0.07f, y - r * 0.7f); f.quadTo(x + r * 0.6f, y - r * 0.5f, x + r * 0.45f, y - r * 0.05f)
            f.quadTo(x + r * 0.4f, y - r * 0.35f, x + r * 0.07f, y - r * 0.4f); f.close()
            cv.drawPath(f, p)
        }
        // 10 confetti
        at(10) { x, y, r -> cv.drawRoundRect(RectF(x - r * 0.35f, y - r * 0.7f, x + r * 0.35f, y + r * 0.7f), r * 0.12f, r * 0.12f, paint()) }
        // 11 shard
        at(11) { x, y, r ->
            val path = Path(); path.moveTo(x - r * 0.7f, y + r * 0.5f); path.lineTo(x + r * 0.1f, y - r * 0.75f); path.lineTo(x + r * 0.7f, y + r * 0.35f); path.close()
            cv.drawPath(path, paint())
        }
        // 12..15 power-up icons (full colour)
        at(12) { x, y, r -> iconBase(cv, x, y, r, "#39B8FF", "#1565C0"); shieldGlyph(cv, x, y, r) }
        at(13) { x, y, r -> iconBase(cv, x, y, r, "#FF5A7A", "#C2185B"); magnetGlyph(cv, x, y, r) }
        at(14) { x, y, r -> iconBase(cv, x, y, r, "#B45CFF", "#5E35B1"); hourglassGlyph(cv, x, y, r) }
        at(15) { x, y, r -> iconBase(cv, x, y, r, "#FFB020", "#E65100"); drillGlyph(cv, x, y, r) }
        return bmp
    }

    private fun iconBase(cv: Canvas, x: Float, y: Float, r: Float, light: String, dark: String) {
        val p = paint()
        p.shader = LinearGradient(x, y - r, x, y + r, Color.parseColor(light), Color.parseColor(dark), Shader.TileMode.CLAMP)
        cv.drawCircle(x, y, r * 0.86f, p)
        val rim = paint(); rim.style = Paint.Style.STROKE; rim.strokeWidth = r * 0.08f; rim.color = Color.WHITE
        cv.drawCircle(x, y, r * 0.82f, rim)
        val hl = paint(); hl.color = Color.argb(90, 255, 255, 255)
        cv.drawOval(RectF(x - r * 0.55f, y - r * 0.72f, x + r * 0.55f, y - r * 0.15f), hl)
    }

    private fun shieldGlyph(cv: Canvas, x: Float, y: Float, r: Float) {
        val path = Path()
        path.moveTo(x, y - r * 0.5f)
        path.lineTo(x + r * 0.4f, y - r * 0.33f)
        path.quadTo(x + r * 0.38f, y + r * 0.3f, x, y + r * 0.52f)
        path.quadTo(x - r * 0.38f, y + r * 0.3f, x - r * 0.4f, y - r * 0.33f)
        path.close()
        cv.drawPath(path, paint())
    }

    private fun magnetGlyph(cv: Canvas, x: Float, y: Float, r: Float) {
        val p = paint(); p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.22f
        cv.drawArc(RectF(x - r * 0.38f, y - r * 0.35f, x + r * 0.38f, y + r * 0.42f), 0f, 180f, false, p)
        val f = paint()
        cv.drawRect(x - r * 0.49f, y - r * 0.45f, x - r * 0.27f, y + r * 0.05f, f)
        cv.drawRect(x + r * 0.27f, y - r * 0.45f, x + r * 0.49f, y + r * 0.05f, f)
    }

    private fun hourglassGlyph(cv: Canvas, x: Float, y: Float, r: Float) {
        val path = Path()
        path.moveTo(x - r * 0.35f, y - r * 0.5f); path.lineTo(x + r * 0.35f, y - r * 0.5f); path.lineTo(x + r * 0.06f, y)
        path.lineTo(x + r * 0.35f, y + r * 0.5f); path.lineTo(x - r * 0.35f, y + r * 0.5f); path.lineTo(x - r * 0.06f, y); path.close()
        cv.drawPath(path, paint())
    }

    private fun drillGlyph(cv: Canvas, x: Float, y: Float, r: Float) {
        val path = Path()
        path.moveTo(x - r * 0.32f, y - r * 0.45f); path.lineTo(x + r * 0.32f, y - r * 0.45f); path.lineTo(x, y + r * 0.55f); path.close()
        cv.drawPath(path, paint())
        val s = paint(); s.color = Color.parseColor("#E65100"); s.strokeWidth = r * 0.07f
        for (k in 0..2) {
            val yy = y - r * 0.28f + k * r * 0.22f
            cv.drawLine(x - r * 0.24f + k * 0.07f * r, yy, x + r * 0.2f - k * 0.07f * r, yy + r * 0.12f, s)
        }
    }

    private fun starPath(x: Float, y: Float, ro: Float, ri: Float, points: Int): Path {
        val path = Path()
        for (k in 0 until points * 2) {
            val rr = if (k % 2 == 0) ro else ri
            val a = -PI / 2 + k * PI / points
            val px = x + cos(a).toFloat() * rr
            val py = y + sin(a).toFloat() * rr
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        return path
    }

    private fun heartPath(x: Float, y: Float, s: Float): Path {
        val path = Path()
        path.moveTo(x, y + s * 0.75f)
        path.cubicTo(x - s * 1.25f, y - s * 0.1f, x - s * 0.55f, y - s * 1.05f, x, y - s * 0.4f)
        path.cubicTo(x + s * 0.55f, y - s * 1.05f, x + s * 1.25f, y - s * 0.1f, x, y + s * 0.75f)
        path.close()
        return path
    }

    /**
     * Splat stamps (4x2 atlas): paint, star, heart, ink, paw, flower, bolt, pixel.
     * The red channel carries soft shading so splats look wet and raised.
     */
    fun splatAtlas(): Bitmap {
        val w = 1024
        val h = 512
        val cell = 256f
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val rnd = Random(42)
        fun at(i: Int, block: (Float, Float, Float) -> Unit) {
            val cx = (i % 4) * cell + cell / 2
            val cy = (i / 4) * cell + cell / 2
            cv.save()
            cv.clipRect(cx - cell / 2, cy - cell / 2, cx + cell / 2, cy + cell / 2)
            block(cx, cy, cell * 0.5f)
            cv.restore()
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 200, 200) }
        val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 255, 255) }
        fun shaded(path: Path, x: Float, y: Float, r: Float) {
            cv.drawPath(path, body)
            cv.save()
            cv.clipPath(path)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = RadialGradient(x - r * 0.2f, y - r * 0.25f, r * 0.9f, intArrayOf(Color.rgb(255, 255, 255), Color.rgb(190, 190, 190), Color.rgb(140, 140, 140)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            cv.drawPath(path, p)
            cv.restore()
        }
        // 0 paint splat: noisy blob + droplets
        at(0) { x, y, r ->
            val path = Path()
            val n = 22
            val radii = FloatArray(n) { r * (0.42f + rnd.nextFloat() * 0.16f) * (if (it % 5 == 0) 1.25f else 1f) }
            for (k in 0..n) {
                val i = k % n
                val a = 2 * PI * i / n
                val px = x + cos(a).toFloat() * radii[i]
                val py = y + sin(a).toFloat() * radii[i]
                if (k == 0) path.moveTo(px, py) else {
                    val pa = 2 * PI * (k - 0.5) / n
                    val pr = (radii[(k - 1) % n] + radii[i]) * 0.5f * 1.05f
                    path.quadTo(x + cos(pa).toFloat() * pr, y + sin(pa).toFloat() * pr, px, py)
                }
            }
            path.close()
            shaded(path, x, y, r * 0.6f)
            repeat(9) {
                val a = rnd.nextDouble() * 2 * PI
                val d = r * (0.62f + rnd.nextFloat() * 0.3f)
                val s = r * (0.04f + rnd.nextFloat() * 0.06f)
                cv.drawCircle(x + cos(a).toFloat() * d, y + sin(a).toFloat() * d, s, body)
            }
            cv.drawOval(RectF(x - r * 0.22f, y - r * 0.3f, x - r * 0.02f, y - r * 0.18f), shine)
        }
        at(1) { x, y, r -> shaded(starPath(x, y, r * 0.8f, r * 0.36f, 5), x, y, r * 0.7f) }
        at(2) { x, y, r -> shaded(heartPath(x, y + r * 0.08f, r * 0.62f), x, y, r * 0.7f) }
        // 3 ink blot: several overlapping blobs
        at(3) { x, y, r ->
            val path = Path()
            repeat(6) {
                val a = rnd.nextDouble() * 2 * PI
                val d = r * rnd.nextFloat() * 0.3f
                path.addCircle(x + cos(a).toFloat() * d, y + sin(a).toFloat() * d, r * (0.2f + rnd.nextFloat() * 0.2f), Path.Direction.CW)
            }
            shaded(path, x, y, r * 0.6f)
            repeat(5) {
                val a = rnd.nextDouble() * 2 * PI
                val d = r * (0.6f + rnd.nextFloat() * 0.25f)
                cv.drawCircle(x + cos(a).toFloat() * d, y + sin(a).toFloat() * d, r * 0.05f, body)
            }
        }
        // 4 paw
        at(4) { x, y, r ->
            val path = Path()
            path.addOval(RectF(x - r * 0.34f, y - r * 0.05f, x + r * 0.34f, y + r * 0.5f), Path.Direction.CW)
            for ((dx, dy) in listOf(-0.42f to -0.28f, -0.15f to -0.52f, 0.15f to -0.52f, 0.42f to -0.28f)) {
                path.addCircle(x + dx * r, y + dy * r, r * 0.14f, Path.Direction.CW)
            }
            shaded(path, x, y, r * 0.6f)
        }
        // 5 flower
        at(5) { x, y, r ->
            val path = Path()
            for (k in 0 until 6) {
                val a = k * PI / 3
                path.addCircle(x + cos(a).toFloat() * r * 0.32f, y + sin(a).toFloat() * r * 0.32f, r * 0.24f, Path.Direction.CW)
            }
            path.addCircle(x, y, r * 0.25f, Path.Direction.CW)
            shaded(path, x, y, r * 0.7f)
        }
        // 6 bolt
        at(6) { x, y, r ->
            val path = Path()
            path.moveTo(x + r * 0.15f, y - r * 0.75f); path.lineTo(x - r * 0.45f, y + r * 0.1f); path.lineTo(x - r * 0.02f, y + r * 0.1f)
            path.lineTo(x - r * 0.2f, y + r * 0.75f); path.lineTo(x + r * 0.45f, y - r * 0.12f); path.lineTo(x + r * 0.02f, y - r * 0.12f); path.close()
            shaded(path, x, y, r * 0.7f)
        }
        // 7 pixel splat
        at(7) { x, y, r ->
            val px = r * 0.14f
            val path = Path()
            for (gy in -4..4) for (gx in -4..4) {
                val d = kotlin.math.sqrt((gx * gx + gy * gy).toFloat())
                if (d < 2.6f || (d < 4.2f && rnd.nextFloat() < 0.35f)) {
                    path.addRect(x + gx * px - px / 2, y + gy * px - px / 2, x + gx * px + px / 2, y + gy * px + px / 2, Path.Direction.CW)
                }
            }
            shaded(path, x, y, r * 0.6f)
        }
        return bmp
    }

    /** R8 mask texture: one 360-wide row per ring slot (64 slots). 255 = solid, 0 = gap. */
    fun createMaskTexture(): Int {
        val id = Gl.gen { n, a, o -> glGenTextures(n, a, o) }
        glBindTexture(GL_TEXTURE_2D, id)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        val data = java.nio.ByteBuffer.allocateDirect(360 * 64)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, 360, 64, 0, GL_RED, GL_UNSIGNED_BYTE, data)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        return id
    }

    fun uploadMaskRow(tex: Int, row: Int, cells: ByteArray) {
        val rowBuf = java.nio.ByteBuffer.allocateDirect(360)
        for (i in 0 until 360) rowBuf.put(if (cells[i].toInt() == 0) 0 else 255.toByte())
        rowBuf.position(0)
        glBindTexture(GL_TEXTURE_2D, tex)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, row, 360, 1, GL_RED, GL_UNSIGNED_BYTE, rowBuf)
    }
}
