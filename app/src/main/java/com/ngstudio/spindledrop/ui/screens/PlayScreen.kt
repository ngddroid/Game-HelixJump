package com.ngstudio.spindledrop.ui.screens

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngstudio.spindledrop.core.audio.Music
import com.ngstudio.spindledrop.core.data.Economy
import com.ngstudio.spindledrop.game.EngineCommand
import com.ngstudio.spindledrop.game.EngineListener
import com.ngstudio.spindledrop.game.GameEngine
import com.ngstudio.spindledrop.game.Haptic
import com.ngstudio.spindledrop.game.HudState
import com.ngstudio.spindledrop.game.Mode
import com.ngstudio.spindledrop.game.PlayStatus
import com.ngstudio.spindledrop.game.RunResult
import com.ngstudio.spindledrop.game.Sfx
import com.ngstudio.spindledrop.game.UiEvent
import com.ngstudio.spindledrop.game.level.Difficulty
import com.ngstudio.spindledrop.game.level.Mechanic
import com.ngstudio.spindledrop.game.level.PowerUpType
import com.ngstudio.spindledrop.game.render.ProceduralTextures
import com.ngstudio.spindledrop.game.theme.Worlds
import com.ngstudio.spindledrop.ui.LocalNav
import com.ngstudio.spindledrop.ui.Route
import com.ngstudio.spindledrop.ui.Toasts
import com.ngstudio.spindledrop.ui.components.CoinIcon
import com.ngstudio.spindledrop.ui.components.GameButton
import com.ngstudio.spindledrop.ui.components.GameDialog
import com.ngstudio.spindledrop.ui.components.GameProgressBar
import com.ngstudio.spindledrop.ui.components.GameSurface
import com.ngstudio.spindledrop.ui.components.GameToggle
import com.ngstudio.spindledrop.ui.components.GemIcon
import com.ngstudio.spindledrop.ui.components.ChestIcon
import com.ngstudio.spindledrop.ui.components.GlowPulse
import com.ngstudio.spindledrop.ui.components.ParticleImage
import com.ngstudio.spindledrop.ui.components.RoundButton
import com.ngstudio.spindledrop.ui.components.StarIcon
import com.ngstudio.spindledrop.ui.components.StrokeText
import com.ngstudio.spindledrop.ui.components.noRippleClick
import com.ngstudio.spindledrop.ui.theme.GC
import com.ngstudio.spindledrop.ui.theme.GT
import com.ngstudio.spindledrop.ui.theme.LocalServices
import com.ngstudio.spindledrop.ui.theme.darken
import com.ngstudio.spindledrop.ui.theme.lighten
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed class PlayDialog {
    data object None : PlayDialog()
    data object Pause : PlayDialog()
    data class Revive(val result: RunResult) : PlayDialog()
    data class Fail(val result: RunResult) : PlayDialog()
    data class Win(val result: RunResult, val summary: Economy.WinSummary?) : PlayDialog()
}

private data class Floater(val id: Long, val text: String, val color: Color, val big: Boolean)

fun musicFor(mode: Mode, level: Int): Music = when (mode) {
    Mode.ENDLESS -> Music.ENDLESS
    Mode.DAILY -> Music.PLAY_A
    else -> if (Difficulty.isBoss(level)) Music.BOSS else Music.forWorldMusic(Worlds.forLevel(level).music)
}

@Composable
fun PlayScreen(route: Route.Play) {
    val s = LocalServices.current
    val nav = LocalNav.current
    val profile by s.profile.state.collectAsState()
    val engine = remember { GameEngine(s.audio) }
    var level by remember { mutableIntStateOf(route.level) }
    var runId by remember { mutableIntStateOf(0) }
    var hud by remember { mutableStateOf(HudState(mode = route.mode, level = route.level)) }
    var dialog by remember { mutableStateOf<PlayDialog>(PlayDialog.None) }
    val floaters = remember { mutableStateListOf<Floater>() }
    var combo by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var tip by remember { mutableStateOf<Mechanic?>(null) }
    var gate by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var showHint by remember { mutableStateOf(false) }
    var startedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()

    fun finishRun(r: RunResult): Economy.WinSummary? {
        val secs = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
        var summary: Economy.WinSummary? = null
        s.profile.update { val (np, sm) = Economy.applyRun(it, r, secs); summary = sm; np }
        if (r.won) s.telemetry.levelComplete(r.mode.name, r.level, r.stars, secs * 1000, summary?.firstClear == true)
        else s.telemetry.levelFail(r.mode.name, r.level, r.ringsPassed)
        return summary
    }

    DisposableEffect(engine) {
        val h = Handler(Looper.getMainLooper())
        engine.listener = object : EngineListener {
            override fun onHud(hs: HudState) { h.post { hud = hs } }
            override fun onEvent(e: UiEvent) {
                h.post {
                    when (e) {
                        is UiEvent.Points -> {
                            floaters += Floater(System.nanoTime(), "+${e.points}", if (e.smashed) GC.Orange else GC.Mint, e.smashed)
                            if (floaters.size > 6) floaters.removeAt(0)
                        }
                        is UiEvent.ComboText -> combo = Pair(e.text, System.nanoTime())
                        is UiEvent.Tooltip -> if (e.mechanic.name !in s.profile.value.seenTips) {
                            tip = e.mechanic
                            s.profile.update { it.copy(seenTips = it.seenTips + e.mechanic.name) }
                        }
                        is UiEvent.PowerUp -> Toasts.show(when (e.type) {
                            PowerUpType.SHIELD -> "Shield! One free hit"
                            PowerUpType.MAGNET -> "Coin Magnet!"
                            PowerUpType.SLOWMO -> "Slow-Mo!"
                            PowerUpType.DRILL -> "Drill! Smash 5 rings"
                        })
                        is UiEvent.Vault -> floaters += Floater(System.nanoTime(), "x${e.multiplier}  +${e.coins}", GC.Yellow, true)
                        is UiEvent.GateHit -> gate = Pair(e.hpLeft, e.maxHp)
                        UiEvent.IntroDone -> { if (route.mode == Mode.LEVEL && level <= 3) showHint = true }
                        is UiEvent.Died -> {
                            val canRevive = e.result.revives == 0
                            dialog = if (canRevive) PlayDialog.Revive(e.result) else { finishRun(e.result); PlayDialog.Fail(e.result) }
                        }
                        is UiEvent.Won -> {
                            val sm = finishRun(e.result)
                            scope.launch { delay(1300); dialog = PlayDialog.Win(e.result, sm) }
                        }
                    }
                }
            }
        }
        onDispose { engine.listener = null }
    }

    LaunchedEffect(runId) {
        applyEngineSettings(engine, s.profile.value)
        engine.send(EngineCommand.SetAutoplay(com.ngstudio.spindledrop.BuildConfig.DEBUG && "DEV_AUTOPLAY" in s.profile.value.seenTips))
        when (route.mode) {
            Mode.LEVEL -> engine.send(EngineCommand.StartLevel(level))
            Mode.ENDLESS -> engine.send(EngineCommand.StartEndless(route.seed + runId))
            Mode.DAILY -> engine.send(EngineCommand.StartDaily(route.seed, route.level))
            Mode.PREVIEW -> Unit
        }
        engine.send(EngineCommand.SetPaused(false))
        s.audio.playMusic(musicFor(route.mode, level))
        if (route.mode != Mode.PREVIEW) s.telemetry.levelStart(route.mode.name, level)
        startedAt = System.currentTimeMillis()
        dialog = PlayDialog.None
        gate = null; combo = null; floaters.clear(); showHint = false
    }

    // Freeze the 3D scene behind pause / revive / fail (saves battery); the win screen keeps its celebration.
    val frozen = dialog is PlayDialog.Pause || dialog is PlayDialog.Revive || dialog is PlayDialog.Fail
    LaunchedEffect(frozen) {
        if (frozen) delay(350) // let the death/pause frame settle first
        engine.send(EngineCommand.SetPaused(frozen))
    }

    // Auto-pause when the app goes to the background.
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE && dialog == PlayDialog.None && hud.status == PlayStatus.PLAYING) dialog = PlayDialog.Pause
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    BackHandler {
        when (dialog) {
            PlayDialog.None -> { s.audio.sfx(Sfx.UI_OPEN); dialog = PlayDialog.Pause }
            PlayDialog.Pause -> dialog = PlayDialog.None
            else -> Unit
        }
    }

    fun goHome() { s.audio.sfx(Sfx.UI_BACK); nav.pop() }
    fun restart() { runId++ }

    Box(Modifier.fillMaxSize()) {
        var surfaceActive by remember { mutableStateOf(true) }
        LaunchedEffect(frozen) { if (frozen) { delay(400); surfaceActive = false } else surfaceActive = true }
        GameSurface(engine, Modifier.fillMaxSize(), active = surfaceActive)
        Hud(hud, route.mode, level, floaters, onPause = { if (dialog == PlayDialog.None) dialog = PlayDialog.Pause }, gate = gate)
        ComboBanner(combo)
        if (hud.blaze) BlazeBadge()
        SwipeHint(showHint && hud.status == PlayStatus.PLAYING && hud.progress < 0.08f)
        TipBanner(tip) { tip = null }

        // ---------------- dialogs ----------------
        val d = dialog
        GameDialog(d is PlayDialog.Pause, onDismiss = { dialog = PlayDialog.None }, title = "Paused", titleColor = GC.Sky) {
            PauseContent(onResume = { dialog = PlayDialog.None }, onRestart = { restart() }, onHome = { goHome() })
        }
        GameDialog(d is PlayDialog.Revive, onDismiss = null, title = "Keep Going?", titleColor = GC.Red) {
            if (d is PlayDialog.Revive) ReviveContent(
                result = d.result, coins = profile.coins,
                onRevive = { dialog = PlayDialog.None; engine.send(EngineCommand.Revive) },
                onDecline = { finishRun(d.result); s.audio.sfx(Sfx.FAIL); dialog = PlayDialog.Fail(d.result) },
            )
        }
        GameDialog(d is PlayDialog.Fail, onDismiss = null, title = if (route.mode == Mode.ENDLESS) "Run Over" else "Level Failed", titleColor = GC.Purple) {
            if (d is PlayDialog.Fail) FailContent(d.result, profile.stats.endlessBest, onRetry = { restart() }, onHome = { goHome() })
        }
        GameDialog(d is PlayDialog.Win, onDismiss = null, title = if (route.mode == Mode.DAILY) "Tower Cleared!" else "Level Cleared!", titleColor = GC.Mint) {
            if (d is PlayDialog.Win) WinContent(
                d.result, d.summary,
                onNext = {
                    if (route.mode == Mode.LEVEL && level < Difficulty.MAX_LEVEL) {
                        // Continue with the next playable level.
                        val next = level + 1
                        if (s.profile.value.isUnlocked(next)) { level = next; runId++ } else goHome()
                    } else goHome()
                },
                onHome = { goHome() },
                isLevel = route.mode == Mode.LEVEL,
            )
        }
    }
}

// ============================================================================ HUD

@Composable
private fun Hud(h: HudState, mode: Mode, level: Int, floaters: List<Floater>, onPause: () -> Unit, gate: Pair<Int, Int>?) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(onClick = onPause, color = GC.Violet.copy(alpha = 0.9f), size = 44.dp, sfx = Sfx.UI_OPEN) {
                Icon(Icons.Rounded.Pause, "Pause", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(10.dp))
            if (mode == Mode.LEVEL || mode == Mode.DAILY) {
                LevelBadge(if (mode == Mode.DAILY) "D" else "$level", GC.Pink)
                GameProgressBar(h.progress, Modifier.weight(1f).padding(horizontal = 6.dp), color = GC.Mint, height = 16.dp)
                LevelBadge(if (mode == Mode.DAILY) "★" else "${level + 1}", Color(0xFF6B5A9A))
            } else {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("DEPTH", style = GT.body(12.sp, GC.Muted))
                    StrokeText("${h.depth}", size = 24.sp, strokeWidth = 3.dp)
                }
            }
            Spacer(Modifier.width(10.dp))
            Row(Modifier.background(Color(0x88140530), RoundedCornerShape(16.dp)).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                CoinIcon(22.dp); Spacer(Modifier.width(4.dp)); StrokeText("${h.coins}", size = 16.sp, strokeWidth = 2.dp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(contentAlignment = Alignment.TopCenter) {
            val sc by animateFloatAsState(1f, label = "score")
            StrokeText("%,d".format(h.score), size = 52.sp, strokeWidth = 5.dp, modifier = Modifier.scale(sc))
            for (f in floaters) key(f.id) { FloaterText(f) }
        }
        if (h.isBoss && gate != null) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("GATE", style = GT.display(14.sp, GC.Red.lighten(0.3f)))
                Spacer(Modifier.width(6.dp))
                GameProgressBar(gate.first.toFloat() / gate.second.coerceAtLeast(1), Modifier.width(150.dp), color = GC.Red, height = 12.dp)
            }
        }
        Spacer(Modifier.height(6.dp))
        // Active power-ups.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (h.shield) PowerChip(ProceduralTextures.Tile.SHIELD.toInt(), "")
            if (h.magnet > 0f) PowerChip(ProceduralTextures.Tile.MAGNET.toInt(), "${h.magnet.toInt() + 1}s")
            if (h.slow > 0f) PowerChip(ProceduralTextures.Tile.HOURGLASS.toInt(), "${h.slow.toInt() + 1}s")
            if (h.drill > 0) PowerChip(ProceduralTextures.Tile.DRILL.toInt(), "x${h.drill}")
        }
        if (h.fps > 0 && LocalServices.current.profile.value.seenTips.contains("DEV_FPS")) {
            Text("${h.fps} fps", style = GT.body(12.sp, GC.Yellow))
        }
    }
}

@Composable
private fun LevelBadge(text: String, color: Color) {
    Box(
        Modifier.size(38.dp).background(Brush.verticalGradient(listOf(color.lighten(0.3f), color)), CircleShape).border(2.5.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) { StrokeText(text, size = if (text.length > 3) 11.sp else 16.sp, strokeWidth = 2.dp, maxLines = 1) }
}

@Composable
private fun PowerChip(tile: Int, label: String) {
    Row(Modifier.background(Color(0x99140530), RoundedCornerShape(16.dp)).padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        ParticleImage(tile, 26.dp)
        if (label.isNotEmpty()) { Spacer(Modifier.width(3.dp)); Text(label, style = GT.display(14.sp)) }
    }
}

@Composable
private fun FloaterText(f: Floater) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(f.id) { a.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val t = a.value
    StrokeText(
        f.text, size = if (f.big) 30.sp else 24.sp, color = f.color, strokeWidth = 3.dp,
        modifier = Modifier.offset(x = if (f.big) 0.dp else 70.dp, y = (40 - 70 * t).dp).graphicsLayer {
            alpha = if (t < 0.7f) 1f else 1f - (t - 0.7f) / 0.3f
            val s = if (t < 0.15f) 0.6f + t / 0.15f * 0.5f else 1.1f - (t - 0.15f) * 0.15f
            scaleX = s; scaleY = s
        },
    )
}

@Composable
private fun ComboBanner(combo: Pair<String, Long>?) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(combo) {
        if (combo != null) { visible = true; delay(900); visible = false }
    }
    Box(Modifier.fillMaxSize().padding(top = 200.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(visible, enter = scaleIn(spring(dampingRatio = 0.4f, stiffness = 600f), initialScale = 0.3f) + fadeIn(), exit = fadeOut(tween(200))) {
            val text = combo?.first ?: ""
            StrokeText(
                text, size = 44.sp,
                brush = Brush.verticalGradient(listOf(Color.White, if (text == "SMASH!" || text == "Blazing!") GC.Orange else GC.Yellow)),
                strokeWidth = 5.dp, modifier = Modifier.graphicsLayer { rotationZ = -4f },
            )
        }
    }
}

@Composable
private fun BlazeBadge() {
    val inf = rememberInfiniteTransition(label = "blaze")
    val s by inf.animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(220), RepeatMode.Reverse), label = "bs")
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 150.dp, end = 14.dp), contentAlignment = Alignment.TopEnd) {
        Row(
            Modifier.scale(s).background(Brush.horizontalGradient(listOf(GC.Orange, GC.Red)), RoundedCornerShape(16.dp))
                .border(2.dp, GC.Yellow, RoundedCornerShape(16.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.LocalFireDepartment, null, tint = GC.Yellow, modifier = Modifier.size(22.dp))
            StrokeText("BLAZE", size = 18.sp, strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun SwipeHint(visible: Boolean) {
    val inf = rememberInfiniteTransition(label = "hint")
    val x by inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "hx")
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(bottom = 120.dp), contentAlignment = Alignment.BottomCenter) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(220.dp, 70.dp)) {
                    val cx = size.width / 2 + x * size.width * 0.35f
                    for (k in 1..6) drawCircle(Color.White.copy(alpha = 0.07f * (7 - k)), 22f, Offset(cx - x * k * 12f, size.height / 2))
                    drawCircle(Color.White, 26f, Offset(cx, size.height / 2))
                    drawCircle(GC.Pink, 26f, Offset(cx, size.height / 2), style = Stroke(6f))
                }
                StrokeText("Swipe to spin the tower!", size = 24.sp)
            }
        }
    }
}

@Composable
private fun TipBanner(m: Mechanic?, onDone: () -> Unit) {
    LaunchedEffect(m) { if (m != null) { delay(4200); onDone() } }
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(bottom = 24.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(m != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xEE5A2FD0), Color(0xEE2A1170))), RoundedCornerShape(22.dp))
                    .border(2.dp, GC.Yellow, RoundedCornerShape(22.dp)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("NEW!", style = GT.display(14.sp, GC.Yellow))
                StrokeText(m?.title ?: "", size = 24.sp)
                Text(m?.hint ?: "", style = GT.body(15.sp), textAlign = TextAlign.Center)
            }
        }
    }
}

// ============================================================================ dialog contents

@Composable
private fun PauseContent(onResume: () -> Unit, onRestart: () -> Unit, onHome: () -> Unit) {
    val s = LocalServices.current
    val p by s.profile.state.collectAsState()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        GameButton("RESUME", onClick = onResume, color = GC.Mint, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GameButton("Restart", onClick = onRestart, color = GC.Sky, modifier = Modifier.weight(1f), height = 52.dp, fontSize = 20.sp,
                leading = { Icon(Icons.Rounded.Refresh, null, tint = Color.White) })
            GameButton("Home", onClick = onHome, color = GC.Purple, modifier = Modifier.weight(1f), height = 52.dp, fontSize = 20.sp,
                leading = { Icon(Icons.Rounded.Home, null, tint = Color.White) })
        }
        Spacer(Modifier.height(14.dp))
        ToggleRow("Music", p.settings.music) { v -> s.profile.update { it.copy(settings = it.settings.copy(music = v)) } }
        ToggleRow("Sound", p.settings.sfx) { v -> s.profile.update { it.copy(settings = it.settings.copy(sfx = v)) } }
        ToggleRow("Vibration", p.settings.vibration) { v -> s.profile.update { it.copy(settings = it.settings.copy(vibration = v)) } }
    }
}

@Composable
fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = GT.body(17.sp), modifier = Modifier.weight(1f))
        GameToggle(value, onChange)
    }
}

@Composable
private fun ReviveContent(result: RunResult, coins: Int, onRevive: () -> Unit, onDecline: () -> Unit) {
    val s = LocalServices.current
    var showNo by remember { mutableStateOf(false) }
    val countdown = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        launch { delay(1500); showNo = true }
        countdown.animateTo(0f, tween(6000, easing = LinearEasing))
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(126.dp)) {
                drawArc(Color(0x44FFFFFF), 0f, 360f, false, style = Stroke(12f))
                drawArc(GC.Yellow, -90f, 360f * countdown.value, false, style = Stroke(12f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
            GlowPulse(GC.Red, Modifier.size(110.dp))
            Icon(Icons.Rounded.Favorite, null, tint = GC.Red, modifier = Modifier.size(72.dp))
        }
        if (result.progress > 0f) Text("You made it ${(result.progress * 100).toInt()}% of the way!", style = GT.body(16.sp), textAlign = TextAlign.Center)
        Text("Revive with a 2-second shield. Hazards on this ring are cleared.", style = GT.body(13.sp, GC.Muted), textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        GameButton("%,d".format(Economy.REVIVE_COST), onClick = {
            if (coins >= Economy.REVIVE_COST) {
                s.profile.update { it.copy(coins = it.coins - Economy.REVIVE_COST) }
                s.audio.sfx(Sfx.PURCHASE)
                onRevive()
            } else { s.audio.sfx(Sfx.ERROR); Toasts.show("Not enough coins") }
        }, color = GC.Mint, modifier = Modifier.fillMaxWidth(), leading = { CoinIcon(30.dp) }, sub = "Revive", shine = true)
        if (s.ads.isAvailable) {
            Spacer(Modifier.height(8.dp))
            GameButton("Watch Ad", onClick = { s.ads.showRewarded("revive") { ok -> if (ok) onRevive() } }, color = GC.Yellow,
                modifier = Modifier.fillMaxWidth(), height = 54.dp, fontSize = 20.sp, sub = "Free revive")
        }
        Spacer(Modifier.height(10.dp))
        AnimatedVisibility(showNo || countdown.value <= 0f) {
            Text("No thanks", style = GT.display(18.sp, GC.Muted), modifier = Modifier.padding(8.dp).noRippleClick(onDecline))
        }
    }
}

@Composable
private fun FailContent(r: RunResult, endlessBest: Int, onRetry: () -> Unit, onHome: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (r.mode == Mode.ENDLESS) {
            StrokeText("Depth ${r.depth}", size = 40.sp, color = GC.Sky)
            Text(if (r.depth >= endlessBest) "New best!" else "Best: $endlessBest", style = GT.body(16.sp, GC.Yellow))
        } else {
            Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(116.dp)) {
                    drawArc(Color(0x44FFFFFF), 0f, 360f, false, style = Stroke(16f))
                    drawArc(Brush.sweepGradient(listOf(GC.Pink, GC.Yellow, GC.Pink)), -90f, 360f * r.progress, false, style = Stroke(16f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
                StrokeText("${(r.progress * 100).toInt()}%", size = 30.sp)
            }
            Text("So close! Try again?", style = GT.body(16.sp))
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Score ", style = GT.body(15.sp, GC.Muted)); StrokeText("%,d".format(r.score), size = 20.sp, strokeWidth = 2.dp)
            Spacer(Modifier.width(14.dp)); CoinIcon(20.dp); Text(" +${r.coins}", style = GT.display(16.sp))
        }
        Spacer(Modifier.height(14.dp))
        GameButton("TRY AGAIN", onClick = onRetry, color = GC.Mint, modifier = Modifier.fillMaxWidth(), shine = true,
            leading = { Icon(Icons.Rounded.Refresh, null, tint = Color.White, modifier = Modifier.size(30.dp)) })
        Spacer(Modifier.height(8.dp))
        GameButton("HOME", onClick = onHome, color = GC.Purple, modifier = Modifier.fillMaxWidth(), height = 50.dp, fontSize = 20.sp, sfx = Sfx.UI_BACK)
    }
}

@Composable
private fun WinContent(r: RunResult, summary: Economy.WinSummary?, onNext: () -> Unit, onHome: () -> Unit, isLevel: Boolean) {
    val s = LocalServices.current
    var shownStars by remember { mutableIntStateOf(0) }
    var doubled by remember { mutableStateOf(false) }
    val total = summary?.total ?: r.coins
    val counter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        for (k in 1..r.stars) {
            delay(320)
            shownStars = k
            s.audio.sfx(Sfx.STAR, rate = 1f + (k - 1) * 0.12f)
            s.audio.haptic(Haptic.LIGHT)
        }
        counter.animateTo(total.toFloat(), tween(900, easing = FastOutSlowInEasing))
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (isLevel) Row(verticalAlignment = Alignment.Bottom) {
            for (k in 1..3) {
                val sc by animateFloatAsState(if (shownStars >= k) 1f else 0.6f, spring(dampingRatio = 0.35f, stiffness = 500f), label = "st$k")
                StarIcon(shownStars >= k, if (k == 2) 76.dp else 60.dp, Modifier.scale(sc).padding(horizontal = 2.dp).offset(y = if (k == 2) (-8).dp else 0.dp))
            }
        }
        Text(when (r.stars) { 3 -> "Perfect run!"; 2 -> "Great - no revive!"; else -> "Cleared!" }, style = GT.body(16.sp, GC.Yellow))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoinIcon(40.dp)
            Spacer(Modifier.width(6.dp))
            StrokeText("+%,d".format(counter.value.toInt() * (if (doubled) 2 else 1)), size = 36.sp, color = GC.Yellow)
        }
        if (summary != null && summary.gems > 0) Row(verticalAlignment = Alignment.CenterVertically) { GemIcon(26.dp); Text("  +${summary.gems} gems", style = GT.display(18.sp)) }
        if (summary?.chest == true) Row(verticalAlignment = Alignment.CenterVertically) { ChestIcon(40.dp); Text("  Boss chest earned!", style = GT.display(16.sp, GC.Orange)) }
        Text("Score %,d · Best combo ${r.bestCombo}".format(r.score), style = GT.body(14.sp, GC.Muted))
        Spacer(Modifier.height(12.dp))
        GameButton(if (isLevel) "NEXT LEVEL" else "CONTINUE", onClick = onNext, color = GC.Mint, modifier = Modifier.fillMaxWidth(), shine = true, sfx = Sfx.SWOOSH)
        if (s.ads.isAvailable && !doubled && total > 0) {
            Spacer(Modifier.height(8.dp))
            GameButton("x2 COINS", onClick = {
                s.ads.showRewarded("double_coins") { ok ->
                    if (ok) { doubled = true; s.profile.update { it.copy(coins = it.coins + total) }; s.audio.sfx(Sfx.REWARD) }
                }
            }, color = GC.Yellow, modifier = Modifier.fillMaxWidth(), height = 52.dp, fontSize = 20.sp, sub = "Watch ad")
        }
        Spacer(Modifier.height(8.dp))
        GameButton("HOME", onClick = onHome, color = GC.Purple, modifier = Modifier.fillMaxWidth(), height = 48.dp, fontSize = 18.sp, sfx = Sfx.UI_BACK)
    }
}

@Suppress("unused") private val darkRef = GC.Pink.darken(0.1f)
