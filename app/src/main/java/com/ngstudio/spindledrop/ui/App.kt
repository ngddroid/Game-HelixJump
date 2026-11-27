package com.ngstudio.spindledrop.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngstudio.spindledrop.AppServices
import com.ngstudio.spindledrop.core.ads.FakeAdGateway
import com.ngstudio.spindledrop.game.Mode
import com.ngstudio.spindledrop.game.Sfx
import com.ngstudio.spindledrop.ui.components.GameButton
import com.ngstudio.spindledrop.ui.components.StrokeText
import com.ngstudio.spindledrop.ui.components.noRippleClick
import com.ngstudio.spindledrop.ui.screens.DevPanelScreen
import com.ngstudio.spindledrop.ui.screens.LegalScreen
import com.ngstudio.spindledrop.ui.screens.MainScreen
import com.ngstudio.spindledrop.ui.screens.OnboardingScreen
import com.ngstudio.spindledrop.ui.screens.PlayScreen
import com.ngstudio.spindledrop.ui.screens.ProfileScreen
import com.ngstudio.spindledrop.ui.screens.SettingsScreen
import com.ngstudio.spindledrop.ui.screens.SplashScreen
import com.ngstudio.spindledrop.ui.screens.StudioIntroScreen
import com.ngstudio.spindledrop.ui.theme.GC
import com.ngstudio.spindledrop.ui.theme.GT
import com.ngstudio.spindledrop.ui.theme.LocalServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow

sealed interface Route {
    data object StudioIntro : Route
    data object Splash : Route
    data object Onboarding : Route
    data object Main : Route
    data class Play(val mode: Mode, val level: Int = 1, val seed: Long = 0L) : Route
    data object Settings : Route
    data class Legal(val terms: Boolean) : Route
    data object Profile : Route
    data object Dev : Route
}

class Navigator {
    val stack = mutableStateListOf<Route>(Route.StudioIntro)
    var tab by mutableIntStateOf(0)
    var forward by mutableStateOf(true)
    val current: Route get() = stack.last()

    fun push(r: Route) { forward = true; stack.add(r) }
    fun pop(): Boolean { if (stack.size <= 1) return false; forward = false; stack.removeAt(stack.size - 1); return true }
    fun replaceTop(r: Route) { forward = true; stack[stack.size - 1] = r }
    fun resetTo(vararg r: Route) { forward = true; stack.clear(); stack.addAll(r) }
}

val LocalNav = staticCompositionLocalOf<Navigator> { error("No navigator") }

/** Global toast messages. */
object Toasts {
    val flow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    fun show(msg: String) { flow.tryEmit(msg) }
}

@Composable
fun SpindleApp(services: AppServices) {
    val nav = remember { Navigator() }
    val profile by services.profile.state.collectAsState()
    // Keep the audio engine in sync with settings.
    LaunchedEffect(profile.settings) {
        val s = profile.settings
        services.audio.configure(s.music, s.musicVolume, s.sfx, s.sfxVolume, s.vibration)
    }
    CompositionLocalProvider(LocalServices provides services, LocalNav provides nav) {
        Box(Modifier.fillMaxSize().background(GC.Night)) {
            val route = nav.current
            LaunchedEffect(route) { services.telemetry.screenView(routeName(route)) }
            BackHandler(enabled = nav.stack.size > 1 && route !is Route.Play) {
                services.audio.sfx(Sfx.UI_BACK)
                nav.pop()
            }
            AnimatedContent(
                targetState = route,
                transitionSpec = { transitionFor(initialState, targetState, nav.forward) },
                label = "nav",
            ) { r ->
                when (r) {
                    Route.StudioIntro -> StudioIntroScreen { nav.resetTo(Route.Splash) }
                    Route.Splash -> SplashScreen()
                    Route.Onboarding -> OnboardingScreen()
                    Route.Main -> MainScreen()
                    is Route.Play -> PlayScreen(r)
                    Route.Settings -> SettingsScreen()
                    is Route.Legal -> LegalScreen(r.terms)
                    Route.Profile -> ProfileScreen()
                    Route.Dev -> DevPanelScreen()
                }
            }
            (services.ads as? FakeAdGateway)?.let { TestAdOverlay(it) }
            ToastHost()
        }
    }
}

/** Stable screen name for analytics / crash breadcrumbs. */
private fun routeName(r: Route): String = when (r) {
    Route.StudioIntro -> "studio_intro"
    Route.Splash -> "splash"
    Route.Onboarding -> "onboarding"
    Route.Main -> "main"
    is Route.Play -> "play_${r.mode.name.lowercase()}"
    Route.Settings -> "settings"
    is Route.Legal -> if (r.terms) "legal_terms" else "legal_privacy"
    Route.Profile -> "profile"
    Route.Dev -> "dev"
}

private fun transitionFor(from: Route, to: Route, forward: Boolean): ContentTransform {
    val fade = tween<Float>(260)
    return when {
        from is Route.StudioIntro -> fadeIn(tween(600)) togetherWith fadeOut(tween(600))
        from is Route.Splash -> fadeIn(tween(500)) togetherWith fadeOut(tween(400))
        to is Route.Play || from is Route.Play ->
            (fadeIn(tween(320)) + scaleIn(tween(380), initialScale = if (to is Route.Play) 1.08f else 0.94f)) togetherWith
                (fadeOut(tween(260)) + scaleOut(tween(300), targetScale = if (to is Route.Play) 0.94f else 1.06f))
        forward -> (slideInHorizontally(tween(320)) { it / 3 } + fadeIn(fade)) togetherWith (slideOutHorizontally(tween(320)) { -it / 4 } + fadeOut(fade))
        else -> (slideInHorizontally(tween(320)) { -it / 3 } + fadeIn(fade)) togetherWith (slideOutHorizontally(tween(320)) { it / 4 } + fadeOut(fade))
    }
}

@Composable
private fun ToastHost() {
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        Toasts.flow.collect { m ->
            msg = m
            delay(2200)
            if (msg == m) msg = null
        }
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 70.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(msg != null, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
            Box(
                Modifier.padding(horizontal = 24.dp)
                    .background(Brush.verticalGradient(listOf(Color(0xFF5A2FD0), Color(0xFF3A1A9A))), RoundedCornerShape(20.dp))
                    .border(2.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(msg ?: "", style = GT.body(16.sp), textAlign = TextAlign.Center)
            }
        }
    }
}

/** Debug-only simulated rewarded ad. Clearly labelled as a test, grants the reward after 3 seconds. */
@Composable
private fun TestAdOverlay(fake: FakeAdGateway) {
    val req by fake.request.collectAsState()
    AnimatedVisibility(req != null, enter = fadeIn(), exit = fadeOut()) {
        var remaining by remember(req) { mutableIntStateOf(3) }
        LaunchedEffect(req) {
            remaining = 3
            while (remaining > 0) { delay(1000); remaining-- }
        }
        val inf = rememberInfiniteTransition(label = "ad")
        val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "adspin")
        Box(Modifier.fillMaxSize().background(Color(0xF0070312)).noRippleClick { }, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.background(GC.Yellow, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("TEST AD  ·  DEBUG BUILD", style = GT.body(13.sp, Color.Black))
                }
                Canvas(Modifier.size(140.dp)) {
                    drawCircle(Brush.sweepGradient(listOf(GC.Pink, GC.Yellow, GC.Mint, GC.Sky, GC.Purple, GC.Pink)), style = Stroke(18f))
                    val a = Math.toRadians(spin.toDouble())
                    drawCircle(Color.White, 14f, center + Offset((kotlin.math.cos(a) * size.width * 0.38f).toFloat(), (kotlin.math.sin(a) * size.width * 0.38f).toFloat()))
                }
                StrokeText(if (remaining > 0) "Reward in $remaining…" else "Reward ready!", size = 28.sp)
                Text("Simulated rewarded ad. Real ads will be added later.", style = GT.body(14.sp, GC.Muted), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 40.dp))
                Spacer(Modifier.height(8.dp))
                if (remaining <= 0) {
                    GameButton("Claim Reward", onClick = { fake.finish(true) }, color = GC.Mint, modifier = Modifier.fillMaxWidth(0.7f), sfx = Sfx.REWARD)
                } else {
                    GameButton("Close", onClick = { fake.finish(false) }, color = Color(0xFF6B5A9A), modifier = Modifier.fillMaxWidth(0.5f), height = 48.dp, fontSize = 20.sp, sfx = Sfx.UI_BACK)
                }
            }
        }
    }
}
