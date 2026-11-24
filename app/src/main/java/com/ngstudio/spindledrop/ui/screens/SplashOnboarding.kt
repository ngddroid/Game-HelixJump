package com.ngstudio.spindledrop.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngstudio.spindledrop.core.audio.Music
import com.ngstudio.spindledrop.game.Mode
import com.ngstudio.spindledrop.game.Sfx
import com.ngstudio.spindledrop.game.theme.Catalog
import com.ngstudio.spindledrop.ui.LocalNav
import com.ngstudio.spindledrop.ui.Route
import com.ngstudio.spindledrop.ui.components.BallImage
import com.ngstudio.spindledrop.ui.components.CoinIcon
import com.ngstudio.spindledrop.ui.components.ChestIcon
import com.ngstudio.spindledrop.ui.components.GameButton
import com.ngstudio.spindledrop.ui.components.GameProgressBar
import com.ngstudio.spindledrop.ui.components.GemIcon
import com.ngstudio.spindledrop.ui.components.GlowPulse
import com.ngstudio.spindledrop.ui.components.MenuBackground
import com.ngstudio.spindledrop.ui.components.StrokeText
import com.ngstudio.spindledrop.ui.components.noRippleClick
import com.ngstudio.spindledrop.ui.components.rememberClick
import com.ngstudio.spindledrop.ui.theme.GC
import com.ngstudio.spindledrop.ui.theme.GT
import com.ngstudio.spindledrop.ui.theme.LocalServices
import com.ngstudio.spindledrop.ui.theme.darken
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ============================================================================ logo

@Composable
fun SpindleLogo(modifier: Modifier = Modifier, scale: Float = 1f) {
    val inf = rememberInfiniteTransition(label = "logo")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "spin")
    val bounce by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing)), label = "bounce")
    Column(modifier.scale(scale), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            GlowPulse(GC.Purple, Modifier.size(230.dp))
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(com.ngstudio.spindledrop.R.drawable.splash_icon), null,
                modifier = Modifier.size(300.dp).graphicsLayer {
                    val b = 1f + 0.03f * sin(bounce * 2f * PI.toFloat())
                    scaleX = b; scaleY = b; rotationZ = sin(spin / 360f * 2f * PI.toFloat()) * 2f
                },
            )
        }
        StrokeText("SPINDLE", size = 58.sp, brush = Brush.verticalGradient(listOf(Color(0xFFFFF3A8), GC.Yellow, GC.Orange)), stroke = GC.Ink, strokeWidth = 5.dp)
        StrokeText("DROP", size = 52.sp, brush = Brush.verticalGradient(listOf(Color(0xFFFFB3D6), GC.Pink, Color(0xFFB0207A))), stroke = GC.Ink, strokeWidth = 5.dp,
            modifier = Modifier.offset(y = (-10).dp))
    }
}

/** Animated mini spiral tower with a bouncing ball (used by the logo and onboarding). */
fun DrawScope.drawMiniTower(spin: Float, bounce: Float, rings: Int, hazardPulse: Float = -1f, ballColor: Color = GC.Pink, fire: Boolean = false) {
    val w = size.width; val h = size.height
    val cx = w / 2
    drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFFE8D7FF), Color.White, Color(0xFFCDB8F5)), startX = cx - w * 0.08f, endX = cx + w * 0.08f),
        topLeft = Offset(cx - w * 0.08f, h * 0.05f), size = Size(w * 0.16f, h * 0.95f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f))
    for (i in 0 until rings) {
        val y = h * (0.42f + i * 0.2f)
        val rw = w * 0.42f; val rh = h * 0.1f
        val start = spin + i * 95f
        val gap = 70f
        drawArc(Color(0xFF2E9E73), start + gap, 360f - gap, true, topLeft = Offset(cx - rw, y - rh + h * 0.03f), size = Size(rw * 2, rh * 2))
        drawArc(Brush.verticalGradient(listOf(Color(0xFF7CF5C0), GC.Mint), startY = y - rh, endY = y + rh), start + gap, 360f - gap, true, topLeft = Offset(cx - rw, y - rh), size = Size(rw * 2, rh * 2))
        if (hazardPulse >= 0f) {
            drawArc(GC.Red.copy(alpha = 0.75f + 0.25f * hazardPulse), start + 200f, 60f, true, topLeft = Offset(cx - rw, y - rh), size = Size(rw * 2, rh * 2))
        }
        drawOval(Color(0xFFE9DEFF), topLeft = Offset(cx - w * 0.08f, y - rh * 0.35f), size = Size(w * 0.16f, rh * 0.7f))
    }
    val by = h * (0.12f + 0.2f * (1f - abs(sin(bounce * PI.toFloat()))))
    val r = w * 0.085f
    if (fire) {
        for (k in 0 until 6) drawCircle(Color(0xFFFF9A2A).copy(alpha = 0.5f - k * 0.07f), r * (1f - k * 0.1f), Offset(cx, by - k * r * 0.6f))
    }
    drawCircle(Color.Black.copy(alpha = 0.18f), r * 0.9f, Offset(cx, h * 0.42f - h * 0.005f))
    drawCircle(Brush.radialGradient(listOf(Color.White, ballColor, ballColor.darken(0.4f)), center = Offset(cx - r * 0.35f, by - r * 0.4f), radius = r * 1.8f), r, Offset(cx, by))
}

// ============================================================================ splash

@Composable
fun SplashScreen() {
    val s = LocalServices.current
    val nav = LocalNav.current
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        s.audio.playMusic(Music.MENU)
        progress.animateTo(0.85f, tween(1300, easing = FastOutSlowInEasing))
        s.ready.first { it }
        progress.animateTo(1f, tween(250))
        delay(150)
        val p = s.profile.value
        if (p.onboardingDone) nav.resetTo(Route.Main) else nav.resetTo(Route.Onboarding)
    }
    val appear by animateFloatAsState(1f, spring(dampingRatio = 0.5f, stiffness = 200f), label = "appear")
    Box(Modifier.fillMaxSize()) {
        MenuBackground()
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            SpindleLogo(scale = appear)
            Spacer(Modifier.weight(1f))
            GameProgressBar(progress.value, Modifier.fillMaxWidth(0.6f), color = GC.Yellow, height = 16.dp)
            Spacer(Modifier.height(10.dp))
            Text("Loading worlds…", style = GT.body(14.sp, GC.Muted))
            Spacer(Modifier.height(36.dp))
            Text("NG Studio", style = GT.body(13.sp, GC.Muted.copy(alpha = 0.7f)))
            Spacer(Modifier.height(16.dp))
        }
    }
}

