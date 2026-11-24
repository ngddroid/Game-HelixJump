package com.ngstudio.spindledrop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngstudio.spindledrop.game.Haptic
import com.ngstudio.spindledrop.game.Sfx
import com.ngstudio.spindledrop.ui.theme.GC
import com.ngstudio.spindledrop.ui.theme.LocalServices
import com.ngstudio.spindledrop.ui.theme.Lilita
import com.ngstudio.spindledrop.ui.theme.darken
import com.ngstudio.spindledrop.ui.theme.lighten
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ============================================================================ feedback helpers

@Composable
fun rememberClick(sfx: Sfx = Sfx.UI_TAP, onClick: () -> Unit): () -> Unit {
    val s = LocalServices.current
    return remember(onClick, sfx) {
        {
            s.audio.sfx(sfx)
            s.audio.haptic(Haptic.TICK)
            onClick()
        }
    }
}

fun Modifier.noRippleClick(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = MutableInteractionSource(), indication = null, onClick = onClick,
)

// ============================================================================ text

/** Chunky outlined "game" text (fill + stroke + drop shadow). */
@Composable
fun StrokeText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 28.sp,
    color: Color = GC.White,
    brush: Brush? = null,
    stroke: Color = GC.Ink,
    strokeWidth: Dp = 4.dp,
    align: TextAlign = TextAlign.Center,
    maxLines: Int = 2,
) {
    val px = with(LocalDensity.current) { strokeWidth.toPx() }
    val base = TextStyle(fontFamily = Lilita, fontSize = size, textAlign = align, letterSpacing = 0.6.sp, lineHeight = size * 1.05f)
    Box(modifier) {
        Text(
            text, maxLines = maxLines,
            style = base.copy(color = stroke, drawStyle = Stroke(width = px * 2f, join = StrokeJoin.Round),
                shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, px * 1.2f), px)),
        )
        Text(text, maxLines = maxLines, style = if (brush != null) base.copy(brush = brush) else base.copy(color = color))
    }
}

// ============================================================================ buttons

/** 3D game button: the face sinks into its base when pressed. */
@Composable
fun GameButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = GC.Mint,
    height: Dp = 64.dp,
    fontSize: TextUnit = 26.sp,
    enabled: Boolean = true,
    sfx: Sfx = Sfx.UI_TAP,
    shine: Boolean = false,
    corner: Dp = 22.dp,
    leading: (@Composable () -> Unit)? = null,
    sub: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val depth = 7.dp
    val press by animateDpAsState(if (pressed) depth - 2.dp else 0.dp, spring(dampingRatio = 0.5f, stiffness = 1400f), label = "press")
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.45f, stiffness = 900f), label = "scale")
    val c = if (enabled) color else Color(0xFF7D7895)
    val click = rememberClick(sfx, onClick)
    val shape = RoundedCornerShape(corner)
    val sweep = if (shine) {
        val inf = rememberInfiniteTransition(label = "shine")
        inf.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(2200, delayMillis = 900, easing = LinearEasing)), label = "sweep").value
    } else -1f
    Box(
        modifier
            .height(height + depth)
            .scale(scale)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = click),
    ) {
        // Base (the "depth").
        Box(
            Modifier.fillMaxWidth().height(height).offset(y = depth)
                .shadow(6.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .background(c.darken(0.38f), shape),
        )
        // Face.
        Box(
            Modifier.fillMaxWidth().height(height).offset(y = press)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(c.lighten(0.28f), c, c.darken(0.08f))))
                .border(2.dp, c.darken(0.25f), shape)
                .drawBehind {
                    // Glossy highlight band.
                    drawRoundRect(
                        Color.White.copy(alpha = 0.28f), topLeft = Offset(size.width * 0.04f, size.height * 0.08f),
                        size = Size(size.width * 0.92f, size.height * 0.34f), cornerRadius = CornerRadius(size.height * 0.2f),
                    )
                    if (sweep > -0.5f) {
                        val x = sweep * size.width
                        val p = Path().apply {
                            moveTo(x, 0f); lineTo(x + size.height * 0.45f, 0f)
                            lineTo(x + size.height * 0.05f, size.height); lineTo(x - size.height * 0.4f, size.height); close()
                        }
                        drawPath(p, Color.White.copy(alpha = 0.35f))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (leading != null) { leading(); Spacer(Modifier.width(10.dp)) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    StrokeText(text, size = fontSize, stroke = c.darken(0.55f), strokeWidth = 3.dp, maxLines = 1)
                    if (sub != null) StrokeText(sub, size = (fontSize.value * 0.5f).sp, stroke = c.darken(0.55f), strokeWidth = 2.dp, maxLines = 1)
                }
            }
        }
    }
}

/** Round icon button (gear, close, back...). */
@Composable
fun RoundButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = GC.Violet,
    size: Dp = 48.dp,
    sfx: Sfx = Sfx.UI_TAP,
    badge: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = 0.4f, stiffness = 900f), label = "rb")
    val click = rememberClick(sfx, onClick)
    Box(modifier.size(size).scale(scale)) {
        Box(
            Modifier.fillMaxSize().offset(y = 3.dp).background(color.darken(0.4f), CircleShape),
        )
        Box(
            Modifier.fillMaxSize().clip(CircleShape)
                .background(Brush.verticalGradient(listOf(color.lighten(0.3f), color)))
                .border(2.dp, color.darken(0.3f), CircleShape)
                .clickable(interactionSource = interaction, indication = null, onClick = click),
            contentAlignment = Alignment.Center,
            content = content,
        )
        if (badge) Badge(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp))
    }
}

@Composable
fun Badge(modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "badge")
    val s by inf.animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "bs")
    Box(modifier.size(14.dp).scale(s).background(GC.Red, CircleShape).border(2.dp, Color.White, CircleShape))
}

// ============================================================================ panels

@Composable
fun GamePanel(
    modifier: Modifier = Modifier,
    top: Color = Color(0xFF4A2AA8),
    bottom: Color = Color(0xFF2A1166),
    corner: Dp = 26.dp,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    Column(
        modifier
            .shadow(10.dp, shape, spotColor = Color.Black)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .border(1.5.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.06f))), shape)
            .padding(padding),
        content = content,
    )
}

/** Ribbon banner title used on dialogs and headers. */
@Composable
fun Ribbon(text: String, modifier: Modifier = Modifier, color: Color = GC.Pink, textSize: TextUnit = 26.sp) {
    Box(modifier.height(56.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width; val h = size.height
            val tail = h * 0.55f
            val dark = color.darken(0.4f)
            // Tails.
            val lt = Path().apply { moveTo(0f, h * 0.3f); lineTo(tail, h * 0.3f); lineTo(tail, h); lineTo(0f, h); lineTo(tail * 0.45f, h * 0.65f); close() }
            val rt = Path().apply { moveTo(w, h * 0.3f); lineTo(w - tail, h * 0.3f); lineTo(w - tail, h); lineTo(w, h); lineTo(w - tail * 0.45f, h * 0.65f); close() }
            drawPath(lt, dark); drawPath(rt, dark)
            drawRoundRect(color.darken(0.25f), topLeft = Offset(tail * 0.6f, h * 0.08f), size = Size(w - tail * 1.2f, h * 0.84f), cornerRadius = CornerRadius(10f))
            drawRoundRect(Brush.verticalGradient(listOf(color.lighten(0.25f), color)), topLeft = Offset(tail * 0.6f, 0f), size = Size(w - tail * 1.2f, h * 0.84f), cornerRadius = CornerRadius(10f))
            drawRoundRect(Color.White.copy(alpha = 0.25f), topLeft = Offset(tail * 0.7f, h * 0.06f), size = Size(w - tail * 1.4f, h * 0.22f), cornerRadius = CornerRadius(8f))
        }
        StrokeText(text, size = textSize, stroke = color.darken(0.6f), strokeWidth = 3.dp, modifier = Modifier.padding(bottom = 8.dp, start = 40.dp, end = 40.dp), maxLines = 1)
    }
}

/**
 * In-layout modal dialog with a springy pop-in. Place it as the last child of a full-screen Box.
 */
/** Counts open dialogs so chrome (dock, top bar) can step aside while one is showing. */
object DialogTracker {
    var open by androidx.compose.runtime.mutableIntStateOf(0)
}

@Composable
fun GameDialog(
    visible: Boolean,
    onDismiss: (() -> Unit)?,
    title: String? = null,
    titleColor: Color = GC.Pink,
    width: Dp = 340.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val s = LocalServices.current
    LaunchedEffect(visible) { if (visible) s.audio.sfx(Sfx.UI_OPEN, volume = 0.8f) }
    if (visible) {
        androidx.compose.runtime.DisposableEffect(Unit) {
            DialogTracker.open++
            onDispose { DialogTracker.open-- }
        }
    }
    // Back closes a dismissible dialog (and never falls through to the screen behind it).
    androidx.activity.compose.BackHandler(enabled = visible) { onDismiss?.invoke() }
    AnimatedVisibility(visible, enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) {
        Box(
            Modifier.fillMaxSize().background(Color(0xCC0C0420)).noRippleClick { onDismiss?.invoke() },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible,
                enter = scaleIn(spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.6f) + fadeIn(),
                exit = scaleOut(tween(140), targetScale = 0.85f) + fadeOut(tween(140)),
            ) {
                Box(Modifier.widthIn(max = width).padding(horizontal = 20.dp)) {
                    GamePanel(Modifier.fillMaxWidth().padding(top = 26.dp).noRippleClick { }, padding = 20.dp) {
                        Spacer(Modifier.height(if (title != null) 26.dp else 0.dp))
                        content()
                    }
                    if (title != null) Ribbon(title, Modifier.align(Alignment.TopCenter).fillMaxWidth(0.92f), color = titleColor)
                    if (onDismiss != null) {
                        RoundButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = 14.dp), color = GC.Red, size = 40.dp, sfx = Sfx.UI_BACK) {
                            CloseGlyph(Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================ hand-drawn icons

@Composable
fun CoinIcon(size: Dp = 26.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) { drawCoin() }

fun DrawScope.drawCoin() {
    val r = size.minDimension / 2
    val c = center
    drawCircle(Color(0xFFB86E00), r, c + Offset(0f, r * 0.08f))
    drawCircle(Brush.verticalGradient(listOf(Color(0xFFFFE27A), Color(0xFFFFB31A)), startY = c.y - r, endY = c.y + r), r * 0.94f, c)
    drawCircle(Color(0xFFE59400), r * 0.68f, c, style = Stroke(r * 0.1f))
    drawPath(starPath(c, r * 0.42f, r * 0.18f), Color(0xFFFFF1B8))
    drawCircle(Color.White.copy(alpha = 0.55f), r * 0.16f, c + Offset(-r * 0.42f, -r * 0.42f))
}

@Composable
fun GemIcon(size: Dp = 26.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) {
    val w = this.size.width; val h = this.size.height
    val p = Path().apply {
        moveTo(w * 0.22f, h * 0.1f); lineTo(w * 0.78f, h * 0.1f); lineTo(w * 0.98f, h * 0.38f)
        lineTo(w * 0.5f, h * 0.95f); lineTo(w * 0.02f, h * 0.38f); close()
    }
    drawPath(p, Brush.verticalGradient(listOf(Color(0xFF9BE7FF), Color(0xFF1E88E5))))
    val top = Path().apply { moveTo(w * 0.22f, h * 0.1f); lineTo(w * 0.78f, h * 0.1f); lineTo(w * 0.98f, h * 0.38f); lineTo(w * 0.02f, h * 0.38f); close() }
    drawPath(top, Color.White.copy(alpha = 0.35f))
    val facet = Path().apply { moveTo(w * 0.3f, h * 0.38f); lineTo(w * 0.5f, h * 0.95f); lineTo(w * 0.7f, h * 0.38f); close() }
    drawPath(facet, Color.White.copy(alpha = 0.18f))
    drawPath(p, Color(0xFF0D47A1), style = Stroke(w * 0.06f, join = StrokeJoin.Round))
}

@Composable
fun StarIcon(filled: Boolean, size: Dp = 28.dp, modifier: Modifier = Modifier) = Canvas(modifier.size(size)) {
    val r = this.size.minDimension / 2
    val c = center
    val p = starPath(c, r, r * 0.48f)
    if (filled) {
        drawPath(starPath(c + Offset(0f, r * 0.08f), r, r * 0.48f), Color(0xFFB86E00))
        drawPath(p, Brush.verticalGradient(listOf(Color(0xFFFFF09A), Color(0xFFFFB300)), startY = c.y - r, endY = c.y + r))
        drawPath(starPath(c + Offset(-r * 0.05f, -r * 0.08f), r * 0.45f, r * 0.22f), Color.White.copy(alpha = 0.4f))
    } else {
        drawPath(p, Color(0x55000000))
        drawPath(p, Color.White.copy(alpha = 0.25f), style = Stroke(r * 0.1f, join = StrokeJoin.Round))
    }
}

fun starPath(c: Offset, ro: Float, ri: Float, points: Int = 5): Path = Path().apply {
    for (k in 0 until points * 2) {
        val rr = if (k % 2 == 0) ro else ri
        val a = -PI / 2 + k * PI / points
        val x = c.x + cos(a).toFloat() * rr
        val y = c.y + sin(a).toFloat() * rr
        if (k == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

@Composable
fun LockIcon(size: Dp = 24.dp, modifier: Modifier = Modifier, color: Color = Color(0xFFFFD36E)) = Canvas(modifier.size(size)) {
    val w = this.size.width; val h = this.size.height
    drawArc(color.darken(0.3f), 180f, 180f, false, topLeft = Offset(w * 0.24f, h * 0.06f), size = Size(w * 0.52f, h * 0.6f), style = Stroke(w * 0.13f))
    drawRoundRect(color.darken(0.35f), topLeft = Offset(w * 0.12f, h * 0.42f), size = Size(w * 0.76f, h * 0.56f), cornerRadius = CornerRadius(w * 0.14f))
    drawRoundRect(Brush.verticalGradient(listOf(color.lighten(0.3f), color)), topLeft = Offset(w * 0.12f, h * 0.4f), size = Size(w * 0.76f, h * 0.52f), cornerRadius = CornerRadius(w * 0.14f))
    drawCircle(color.darken(0.55f), w * 0.08f, Offset(w * 0.5f, h * 0.62f))
}

@Composable
fun CloseGlyph(modifier: Modifier = Modifier) = Canvas(modifier) {
    val s = Stroke(size.width * 0.22f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(Color.White, Offset(0f, 0f), Offset(size.width, size.height), s.width, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), s.width, cap = androidx.compose.ui.graphics.StrokeCap.Round)
}

@Composable
fun ChestIcon(size: Dp = 64.dp, modifier: Modifier = Modifier, open: Float = 0f) = Canvas(modifier.size(size)) {
    val w = this.size.width; val h = this.size.height
    val wood = Color(0xFF8D4F2A); val woodL = Color(0xFFC07A45); val gold = Color(0xFFFFC53D)
    // Body
    drawRoundRect(wood.darken(0.3f), topLeft = Offset(w * 0.08f, h * 0.45f), size = Size(w * 0.84f, h * 0.5f), cornerRadius = CornerRadius(w * 0.08f))
    drawRoundRect(Brush.verticalGradient(listOf(woodL, wood), startY = h * 0.45f, endY = h * 0.95f), topLeft = Offset(w * 0.08f, h * 0.43f), size = Size(w * 0.84f, h * 0.48f), cornerRadius = CornerRadius(w * 0.08f))
    if (open > 0f) {
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF6C2), Color.Transparent), center = Offset(w / 2, h * 0.42f), radius = w * 0.6f * open), w * 0.6f * open, Offset(w / 2, h * 0.42f))
    }
    // Lid
    rotate(-35f * open, pivot = Offset(w * 0.1f, h * 0.42f)) {
        drawRoundRect(wood.darken(0.25f), topLeft = Offset(w * 0.06f, h * 0.18f), size = Size(w * 0.88f, h * 0.3f), cornerRadius = CornerRadius(w * 0.14f))
        drawRoundRect(Brush.verticalGradient(listOf(woodL.lighten(0.15f), woodL), startY = h * 0.15f, endY = h * 0.45f), topLeft = Offset(w * 0.06f, h * 0.15f), size = Size(w * 0.88f, h * 0.28f), cornerRadius = CornerRadius(w * 0.14f))
        drawRect(gold, topLeft = Offset(w * 0.44f, h * 0.15f), size = Size(w * 0.12f, h * 0.3f))
    }
    drawRect(gold, topLeft = Offset(w * 0.44f, h * 0.43f), size = Size(w * 0.12f, h * 0.5f))
    drawRoundRect(gold.darken(0.2f), topLeft = Offset(w * 0.4f, h * 0.45f), size = Size(w * 0.2f, h * 0.16f), cornerRadius = CornerRadius(w * 0.04f))
}

// ============================================================================ widgets

@Composable
fun CurrencyPill(amount: Int, gem: Boolean = false, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val shown by animateFloatAsState(amount.toFloat(), tween(650, easing = FastOutSlowInEasing), label = "cur")
    Box(modifier.height(38.dp)) {
        Row(
            Modifier.padding(start = 14.dp).height(34.dp).align(Alignment.CenterStart)
                .background(Color(0x99140530), RoundedCornerShape(17.dp))
                .border(1.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(17.dp))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(start = 24.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StrokeText("%,d".format(shown.toInt()), size = 17.sp, strokeWidth = 2.dp, maxLines = 1)
        }
        if (gem) GemIcon(32.dp, Modifier.align(Alignment.CenterStart)) else CoinIcon(34.dp, Modifier.align(Alignment.CenterStart))
    }
}

@Composable
fun GameProgressBar(progress: Float, modifier: Modifier = Modifier, color: Color = GC.Mint, height: Dp = 14.dp) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(300), label = "pb")
    Box(modifier.height(height).clip(RoundedCornerShape(height / 2)).background(Color(0x66000000))) {
        Box(
            Modifier.fillMaxWidth(p.coerceAtLeast(0.001f)).height(height).clip(RoundedCornerShape(height / 2))
                .background(Brush.verticalGradient(listOf(color.lighten(0.35f), color))),
        ) {
            Box(Modifier.fillMaxWidth().height(height * 0.38f).padding(horizontal = 4.dp).offset(y = 2.dp)
                .background(Color.White.copy(alpha = 0.35f), RoundedCornerShape(height)))
        }
    }
}

/** Game-style toggle switch. */
@Composable
fun GameToggle(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val x by animateDpAsState(if (checked) 30.dp else 2.dp, spring(dampingRatio = 0.55f, stiffness = 700f), label = "tg")
    val click = rememberClick(Sfx.UI_TOGGLE) { onChange(!checked) }
    Box(
        modifier.size(64.dp, 36.dp).clip(RoundedCornerShape(18.dp))
            .background(if (checked) Brush.verticalGradient(listOf(GC.Mint.lighten(0.2f), GC.Mint)) else Brush.verticalGradient(listOf(Color(0xFF5D4B8C), Color(0xFF44336F))))
            .border(2.dp, Color.Black.copy(alpha = 0.2f), RoundedCornerShape(18.dp))
            .noRippleClick(click),
    ) {
        Box(Modifier.offset(x = x, y = 2.dp).size(32.dp).shadow(3.dp, CircleShape).background(Brush.verticalGradient(listOf(Color.White, Color(0xFFE6DDFF))), CircleShape))
    }
}

/** Animated menu background: deep gradient with drifting glow blobs and twinkles. */
@Composable
fun MenuBackground(top: Color = Color(0xFF3B1B9A), bottom: Color = GC.Night, accent: Color = GC.Pink, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "bg")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "bgt")
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(top, bottom)))
        val blobs = listOf(Triple(0.15f, 0.2f, accent), Triple(0.85f, 0.45f, GC.Sky), Triple(0.3f, 0.85f, GC.Purple), Triple(0.75f, 0.1f, GC.Yellow))
        for ((i, b) in blobs.withIndex()) {
            val a = (t + i * 0.25f) * 2 * PI.toFloat()
            val cx = size.width * (b.first + 0.06f * cos(a))
            val cy = size.height * (b.second + 0.04f * sin(a * 1.3f))
            val r = size.width * 0.55f
            drawCircle(Brush.radialGradient(listOf(b.third.copy(alpha = 0.32f), Color.Transparent), center = Offset(cx, cy), radius = r), r, Offset(cx, cy))
        }
        for (k in 0 until 40) {
            val px = ((k * 0.6180339f) % 1f) * size.width
            val py = ((k * 0.3819660f + t * 0.15f * (1 + k % 3)) % 1f) * size.height
            val tw = 0.5f + 0.5f * sin((t * 30f + k) * 1.7f)
            drawCircle(Color.White.copy(alpha = 0.15f + 0.35f * tw), 1.2f + (k % 3), Offset(px, size.height - py))
        }
    }
}

/** Pulsing glow behind a hero element. */
@Composable
fun GlowPulse(color: Color, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "glow")
    val s by inf.animateFloat(0.85f, 1.1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "gs")
    Canvas(modifier.graphicsLayer { scaleX = s; scaleY = s }) {
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent)))
    }
}
