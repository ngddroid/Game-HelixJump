package com.ngstudio.spindledrop.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ngstudio.spindledrop.game.GameEngine
import com.ngstudio.spindledrop.game.GameView
import com.ngstudio.spindledrop.game.render.BallThumbnails
import com.ngstudio.spindledrop.game.render.ProceduralTextures
import com.ngstudio.spindledrop.game.theme.BallSkin
import com.ngstudio.spindledrop.game.theme.TrailSkin
import com.ngstudio.spindledrop.game.theme.TrailStyle
import com.ngstudio.spindledrop.game.theme.WorldTheme
import com.ngstudio.spindledrop.ui.theme.toComposeColor

/** Hosts a [GameEngine] in a GL surface, tied to the lifecycle. */
@Composable
fun GameSurface(engine: GameEngine, modifier: Modifier = Modifier, active: Boolean = true) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val holder = remember { arrayOfNulls<GameView>(1) }
    AndroidView(
        factory = { ctx -> GameView(ctx, engine).also { holder[0] = it } },
        modifier = modifier,
        update = { v ->
            v.renderMode = if (active) android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY else android.opengl.GLSurfaceView.RENDERMODE_WHEN_DIRTY
        },
    )
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> holder[0]?.onPause()
                Lifecycle.Event.ON_RESUME -> holder[0]?.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
}

/** Cropped tiles from the procedural atlases (shared with the 3D renderer). */
object UiArt {
    private val particleTiles: List<ImageBitmap> by lazy { crop(ProceduralTextures.particleAtlas(), 4, 4) }
    private val splatTiles: List<ImageBitmap> by lazy { crop(ProceduralTextures.splatAtlas(), 4, 2) }

    private fun crop(b: Bitmap, cols: Int, rows: Int): List<ImageBitmap> {
        val w = b.width / cols; val h = b.height / rows
        return (0 until cols * rows).map { i -> Bitmap.createBitmap(b, (i % cols) * w, (i / cols) * h, w, h).asImageBitmap() }
    }

    fun particle(tile: Int) = particleTiles[tile]
    fun splat(tile: Int) = splatTiles[tile]
}

@Composable
fun BallImage(ball: BallSkin, size: Dp, modifier: Modifier = Modifier) {
    val v by BallThumbnails.version.collectAsState()
    val bmp = remember(ball.id, v) { BallThumbnails.get(ball.id)?.asImageBitmap() }
    if (bmp != null) {
        Image(bmp, ball.name, modifier.size(size))
    } else {
        val c1 = ball.c1.toComposeColor(); val c2 = ball.c2.toComposeColor()
        Canvas(modifier.size(size)) {
            val r = this.size.minDimension * 0.46f
            drawCircle(Brush.radialGradient(listOf(c1.copy(alpha = 1f).let { androidx.compose.ui.graphics.lerp(it, Color.White, 0.5f) }, c1, c1.copy(red = c1.red * 0.6f, green = c1.green * 0.6f, blue = c1.blue * 0.6f)),
                center = center + Offset(-r * 0.35f, -r * 0.4f), radius = r * 1.6f), r)
            drawCircle(c2.copy(alpha = 0.25f), r * 0.2f, center + Offset(-r * 0.4f, -r * 0.45f))
        }
    }
}

@Composable
fun SplatImage(tile: Int, color: Color, size: Dp, modifier: Modifier = Modifier) {
    Image(UiArt.splat(tile), null, modifier.size(size), colorFilter = ColorFilter.tint(color, androidx.compose.ui.graphics.BlendMode.Modulate))
}

@Composable
fun ParticleImage(tile: Int, size: Dp, modifier: Modifier = Modifier, tint: Color? = null) {
    Image(UiArt.particle(tile), null, modifier.size(size), colorFilter = tint?.let { ColorFilter.tint(it, androidx.compose.ui.graphics.BlendMode.Modulate) })
}

/** Preview ribbon for a trail style. */
@Composable
fun TrailPreview(trail: TrailSkin, modifier: Modifier = Modifier) {
    val base = trail.color.toComposeColor()
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val colors = when (trail.style) {
                TrailStyle.NONE -> listOf(Color.Transparent, Color.Transparent)
                TrailStyle.RAINBOW -> listOf(Color(0xFFFF4F4F), Color(0xFFFFC93C), Color(0xFF3DDC97), Color(0xFF39B8FF), Color(0xFFB45CFF), Color.Transparent)
                TrailStyle.FIRE -> listOf(Color(0xFFFFF1A8), Color(0xFFFF9A2A), Color(0xFFFF3D1F), Color.Transparent)
                else -> listOf(base, base.copy(alpha = 0.6f), Color.Transparent)
            }
            val p = Path().apply {
                moveTo(w * 0.5f - w * 0.16f, h * 0.78f); lineTo(w * 0.5f + w * 0.16f, h * 0.78f)
                lineTo(w * 0.5f + w * 0.03f, h * 0.05f); lineTo(w * 0.5f - w * 0.03f, h * 0.05f); close()
            }
            drawPath(p, Brush.verticalGradient(colors.reversed(), startY = 0f, endY = h * 0.8f))
            if (trail.style == TrailStyle.NONE) {
                drawLine(Color.White.copy(alpha = 0.4f), Offset(w * 0.3f, h * 0.3f), Offset(w * 0.7f, h * 0.7f), 6f, StrokeCap.Round)
            }
            drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFFF6FB5), Color(0xFFC2185B)), center = Offset(w * 0.45f, h * 0.76f), radius = w * 0.22f), w * 0.16f, Offset(w * 0.5f, h * 0.82f))
        }
        val tile = when (trail.style) {
            TrailStyle.BUBBLES -> 5; TrailStyle.STARS -> 2; TrailStyle.HEARTS -> 3; TrailStyle.NOTES -> 9
            TrailStyle.LIGHTNING -> 4; TrailStyle.SMOKE -> 0; else -> -1
        }
        if (tile >= 0) ParticleImage(tile, 26.dp, Modifier.align(Alignment.TopEnd), tint = base)
    }
}

/** A small painted illustration of a world: sky, sun, smooth hills and a mini 3D tower in its colours. */
@Composable
fun WorldPreview(w: WorldTheme, modifier: Modifier = Modifier, showTower: Boolean = true) {
    Canvas(modifier) {
        val sw = size.width; val sh = size.height
        drawRect(Brush.verticalGradient(listOf(w.skyTop.toComposeColor(), w.skyBottom.toComposeColor())))
        if (w.stars > 0f) for (k in 0 until 22) {
            drawCircle(Color.White.copy(alpha = 0.35f + 0.4f * ((k * 37) % 10) / 10f), 1.2f + (k % 3) * 0.6f, Offset(((k * 0.618f) % 1f) * sw, ((k * 0.381f) % 0.5f) * sh))
        }
        val sun = Offset(sw * (0.25f + 0.5f * w.sunX), sh * (0.85f - 0.6f * w.sunY))
        val glow = w.glow.toComposeColor()
        drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.75f), Color.Transparent), center = sun, radius = sw * 0.45f), sw * 0.45f, sun)
        drawCircle(glow.copy(alpha = 0.9f), sw * 0.09f, sun)
        fun hills(base: Float, amp: Float, freq: Float, phase: Float, color: Color) {
            val p = Path(); p.moveTo(0f, sh)
            val n = 48
            for (i in 0..n) {
                val x = sw * i / n
                val y = sh * base - amp * sh * (0.55f + 0.45f * kotlin.math.sin(i * freq / n + phase) * kotlin.math.cos(i * freq * 0.41f / n + phase * 1.7f))
                p.lineTo(x, y)
            }
            p.lineTo(sw, sh); p.close(); drawPath(p, color)
        }
        hills(0.70f, 0.20f, 9f, 1f, w.far.toComposeColor())
        hills(0.82f, 0.14f, 12f, 2.4f, w.mid.toComposeColor())
        hills(0.95f, 0.09f, 16f, 4.1f, w.near.toComposeColor())

        if (!showTower) {
            drawRect(Brush.verticalGradient(0.7f to Color.Transparent, 1f to w.fog.toComposeColor().copy(alpha = 0.45f)))
            return@Canvas
        }
        // ---- mini tower ----
        val cx = sw * 0.5f
        val pillarW = sw * 0.12f
        val rx = sw * 0.36f
        val ry = sh * 0.055f
        val band = sw * 0.10f
        val thick = sh * 0.028f
        val top = w.ringTop.toComposeColor()
        val side = w.ringSide.toComposeColor()
        val hz = w.hazard.toComposeColor()
        val ys = listOf(0.30f, 0.48f, 0.66f, 0.84f).map { it * sh }
        val gaps = listOf(230f, 330f, 60f, 160f)
        fun ring(i: Int, front: Boolean) {
            val cy = ys[i]
            val gapStart = gaps[i]
            val start = gapStart + 60f
            val tl = Offset(cx - rx, cy - ry)
            val sz = Size(rx * 2, ry * 2)
            arcHalf(side, start, 300f, front, tl + Offset(0f, thick), sz, band)
            arcHalf(top, start, 300f, front, tl, sz, band)
            arcHalf(hz, start + 140f, 38f, front, tl, sz, band * 0.92f)
        }
        for (i in ys.indices) ring(i, front = false)
        drawRect(
            Brush.horizontalGradient(listOf(w.pillarB.toComposeColor(), w.pillarA.toComposeColor(), w.pillarB.toComposeColor()), startX = cx - pillarW / 2, endX = cx + pillarW / 2),
            topLeft = Offset(cx - pillarW / 2, 0f), size = Size(pillarW, sh),
        )
        for (i in ys.indices) ring(i, front = true)
        // Ball bouncing above the top ring.
        val br = sw * 0.065f
        val bc = Offset(cx + rx * 0.15f, ys[0] - ry - br * 1.6f)
        drawCircle(Color.Black.copy(alpha = 0.18f), br * 0.9f, Offset(bc.x, ys[0] + ry * 0.4f))
        drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFFF6FB5), Color(0xFFB0205A)), center = bc + Offset(-br * 0.35f, -br * 0.4f), radius = br * 1.8f), br, bc)
        // Depth fog at the bottom.
        drawRect(Brush.verticalGradient(0.7f to Color.Transparent, 1f to w.fog.toComposeColor().copy(alpha = 0.55f)))
    }
}

/** Draws only the back (upper) or front (lower) half of an elliptical arc band. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.arcHalf(color: Color, start: Float, sweep: Float, front: Boolean, tl: Offset, sz: Size, stroke: Float) {
    var a = start
    val end = start + sweep
    while (a < end - 0.01f) {
        val base = kotlin.math.floor(a / 180f) * 180f
        val segEnd = kotlin.math.min(end, base + 180f)
        val isFront = (((base / 180f).toInt() % 2) + 2) % 2 == 0
        if (isFront == front) drawArc(color, a, segEnd - a, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Butt))
        a = segEnd
    }
}

/** Original friendly avatars drawn in code. */
@Composable
fun Avatar(index: Int, size: Dp, modifier: Modifier = Modifier) {
    val palette = listOf(0xFFFF6FB5, 0xFF39B8FF, 0xFF3DDC97, 0xFFFFC93C, 0xFFB45CFF, 0xFFFF8A3D, 0xFF4FE0FF, 0xFFFF5A5A)
    val c = Color(palette[index % palette.size])
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(androidx.compose.ui.graphics.lerp(c, Color.White, 0.45f), c), center = center + Offset(-r * 0.3f, -r * 0.35f), radius = r * 1.5f), r)
        val eyeY = center.y - r * 0.1f
        val style = index % 4
        val eye = Color(0xFF2A1A3A)
        when (style) {
            0 -> { drawCircle(eye, r * 0.11f, Offset(center.x - r * 0.3f, eyeY)); drawCircle(eye, r * 0.11f, Offset(center.x + r * 0.3f, eyeY)) }
            1 -> { drawArc(eye, 200f, 140f, false, topLeft = Offset(center.x - r * 0.45f, eyeY - r * 0.12f), size = Size(r * 0.3f, r * 0.24f), style = Stroke(r * 0.07f, cap = StrokeCap.Round))
                   drawArc(eye, 200f, 140f, false, topLeft = Offset(center.x + r * 0.15f, eyeY - r * 0.12f), size = Size(r * 0.3f, r * 0.24f), style = Stroke(r * 0.07f, cap = StrokeCap.Round)) }
            2 -> { drawCircle(Color.White, r * 0.17f, Offset(center.x - r * 0.3f, eyeY)); drawCircle(Color.White, r * 0.17f, Offset(center.x + r * 0.3f, eyeY))
                   drawCircle(eye, r * 0.09f, Offset(center.x - r * 0.27f, eyeY + r * 0.03f)); drawCircle(eye, r * 0.09f, Offset(center.x + r * 0.33f, eyeY + r * 0.03f)) }
            else -> { drawLine(eye, Offset(center.x - r * 0.42f, eyeY), Offset(center.x - r * 0.18f, eyeY), r * 0.08f, StrokeCap.Round)
                      drawLine(eye, Offset(center.x + r * 0.18f, eyeY), Offset(center.x + r * 0.42f, eyeY), r * 0.08f, StrokeCap.Round) }
        }
        drawArc(eye, 20f, 140f, false, topLeft = Offset(center.x - r * 0.3f, center.y - r * 0.05f), size = Size(r * 0.6f, r * 0.45f), style = Stroke(r * 0.08f, cap = StrokeCap.Round))
        drawCircle(Color(0x55FF5A7A), r * 0.12f, Offset(center.x - r * 0.55f, center.y + r * 0.12f))
        drawCircle(Color(0x55FF5A7A), r * 0.12f, Offset(center.x + r * 0.55f, center.y + r * 0.12f))
        drawCircle(Color.White.copy(alpha = 0.5f), r, style = Stroke(r * 0.08f))
    }
}
