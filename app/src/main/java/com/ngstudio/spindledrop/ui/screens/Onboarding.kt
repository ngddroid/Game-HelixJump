package com.ngstudio.spindledrop.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngstudio.spindledrop.game.Cosmetics
import com.ngstudio.spindledrop.game.EngineCommand
import com.ngstudio.spindledrop.game.GameEngine
import com.ngstudio.spindledrop.game.Mode
import com.ngstudio.spindledrop.game.Sfx
import com.ngstudio.spindledrop.game.theme.Catalog
import com.ngstudio.spindledrop.ui.LocalNav
import com.ngstudio.spindledrop.ui.Route
import com.ngstudio.spindledrop.ui.components.BallImage
import com.ngstudio.spindledrop.ui.components.ChestIcon
import com.ngstudio.spindledrop.ui.components.CoinIcon
import com.ngstudio.spindledrop.ui.components.GameButton
import com.ngstudio.spindledrop.ui.components.GameSurface
import com.ngstudio.spindledrop.ui.components.GemIcon
import com.ngstudio.spindledrop.ui.components.GlowPulse
import com.ngstudio.spindledrop.ui.components.StrokeText
import com.ngstudio.spindledrop.ui.components.noRippleClick
import com.ngstudio.spindledrop.ui.components.rememberClick
import com.ngstudio.spindledrop.ui.theme.GC
import com.ngstudio.spindledrop.ui.theme.GT
import com.ngstudio.spindledrop.ui.theme.LocalServices
import com.ngstudio.spindledrop.ui.theme.darken
import com.ngstudio.spindledrop.ui.theme.lighten
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

private data class OnbPage(val title: String, val body: String, val world: String, val accent: Color, val button: String)

/**
 * Onboarding: the real 3D game runs live behind every page (a different world per page),
 * with a swipeable glass card on top. No skip - the player steps through with NEXT.
 */
@Composable
fun OnboardingScreen() {
    val s = LocalServices.current
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val pages = remember {
        listOf(
            OnbPage("Swipe to Spin", "Drag left or right to turn the tower and line up a gap under the ball.", "candy", GC.Pink, "NEXT"),
            OnbPage("Dodge the Glow", "Glowing striped tiles are dangerous. Bounce only on the safe ones.", "neon", GC.Sky, "NEXT"),
            OnbPage("Ignite Blaze", "Fall through 3 rings in a row and your ball catches fire, smashing whatever it hits.", "lava", GC.Orange, "NEXT"),
            OnbPage("Collect & Unlock", "Earn coins, gems and chests. Unlock balls, trails, splats and 12 worlds.", "crystal", GC.Purple, "NEXT"),
            OnbPage("Pick Your Ball", "Choose a starter ball. You can change it any time in the Shop.", "tropic", GC.Mint, "LET'S PLAY!"),
        )
    }
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val page = pager.currentPage
    var chosen by remember { mutableStateOf(Catalog.starterBalls[0]) }
    val engine = remember { GameEngine(s.audio) }

    LaunchedEffect(Unit) {
        val st = s.profile.value.settings
        engine.send(EngineCommand.SetQuality(st.quality, st.autoQuality))
        engine.send(EngineCommand.Preview(1))
    }
    LaunchedEffect(page) { engine.send(EngineCommand.SetThemeOverride(pages[page].world)) }
    LaunchedEffect(chosen) { engine.send(EngineCommand.SetCosmetics(Cosmetics(Catalog.ball(chosen), Catalog.trail("comet"), Catalog.splat("paint")))) }

    BackHandler {
        if (page > 0) scope.launch { pager.animateScrollToPage(page - 1) } else (ctx as? android.app.Activity)?.finish()
    }

    val accent by animateColorAsState(pages[page].accent, tween(400), label = "accent")
    Box(Modifier.fillMaxSize().background(GC.Night)) {
        GameSurface(engine, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0xCC0A0420), 0.2f to Color.Transparent, 0.45f to Color.Transparent, 0.62f to Color(0x990A0420), 1f to Color(0xF50A0420)),
            ),
        )
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // ---- Header: brand + step counter + story-style progress ----
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                StrokeText("SPIRAL SMASH", size = 20.sp, brush = Brush.horizontalGradient(listOf(GC.Yellow, GC.Pink)), strokeWidth = 3.dp, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Box(Modifier.background(Color(0x66000000), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                    Text("${page + 1} / ${pages.size}", style = GT.display(14.sp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in pages.indices) {
                    val fill by animateFloatAsState(if (i <= page) 1f else 0f, tween(450, easing = FastOutSlowInEasing), label = "seg$i")
                    Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.22f))) {
                        Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(Brush.horizontalGradient(listOf(accent.lighten(0.3f), accent))))
                    }
                }
            }
            Spacer(Modifier.weight(1f))

            // ---- Floating page visual ----
            Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    page,
                    transitionSpec = { (fadeIn(tween(300)) + scaleIn(tween(350), initialScale = 0.7f)) togetherWith (fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 1.2f)) },
                    label = "visual",
                ) { p ->
                    when (p) {
                        0 -> SwipeVisual(pages[0].accent)
                        1 -> HazardVisual()
                        2 -> BlazeVisual()
                        3 -> RewardsVisual()
                        else -> Box(Modifier.fillMaxSize()) // the live 3D scene shows the chosen ball
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            // ---- Glass card ----
            val cardShape = RoundedCornerShape(32.dp)
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xE8301670), Color(0xF5150636))), cardShape)
                    .border(2.dp, Brush.verticalGradient(listOf(accent.copy(alpha = 0.9f), Color.White.copy(alpha = 0.08f))), cardShape)
                    .padding(top = 20.dp, bottom = 18.dp),
            ) {
                HorizontalPager(pager, Modifier.fillMaxWidth().height(if (page == pages.size - 1) 232.dp else 150.dp)) { i ->
                    val off = abs((pager.currentPage - i) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 22.dp).graphicsLayer { alpha = 1f - off * 0.8f },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        StrokeText(pages[i].title, size = 34.sp, brush = Brush.verticalGradient(listOf(Color.White, pages[i].accent.lighten(0.45f))), strokeWidth = 4.dp, maxLines = 1)
                        Spacer(Modifier.height(8.dp))
                        Text(pages[i].body, style = GT.body(17.sp, Color.White.copy(alpha = 0.9f)), textAlign = TextAlign.Center)
                        if (i == pages.size - 1) {
                            Spacer(Modifier.height(14.dp))
                            BallPickerRow(chosen) { chosen = it }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val last = page == pages.size - 1
                GameButton(
                    pages[page].button,
                    onClick = {
                        if (!last) scope.launch { pager.animateScrollToPage(page + 1) }
                        else {
                            s.profile.update { it.copy(onboardingDone = true, ownedBalls = it.ownedBalls + chosen, ball = chosen) }
                            nav.resetTo(Route.Main, Route.Play(Mode.LEVEL, 1))
                        }
                    },
                    color = pages[page].accent,
                    modifier = Modifier.padding(horizontal = 22.dp).fillMaxWidth(),
                    shine = last,
                    sfx = if (last) Sfx.REWARD else Sfx.SWOOSH,
                    leading = if (!last) null else ({ Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Color.White, modifier = Modifier.size(28.dp)) }),
                )
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun SwipeVisual(color: Color) {
    val inf = rememberInfiniteTransition(label = "swipe")
    val t by inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "t")
    Box(Modifier.width(260.dp).height(78.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize().background(Color(0x66000000), RoundedCornerShape(39.dp))
                .border(2.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(39.dp)),
        )
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp).size(28.dp))
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).size(28.dp))
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2 + t * size.width * 0.27f
            for (k in 1..5) drawCircle(color.copy(alpha = 0.12f * (6 - k)), size.height * 0.28f, Offset(cx - t * k * 10f, size.height / 2))
        }
        Box(Modifier.offset(x = (t * 70).dp)) {
            Box(Modifier.size(58.dp).background(Brush.verticalGradient(listOf(color.lighten(0.3f), color)), CircleShape).border(3.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.TouchApp, null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
private fun HazardVisual() {
    val inf = rememberInfiniteTransition(label = "hz")
    val pulse by inf.animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "p")
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        TileChip("SAFE", GC.Mint, Icons.Rounded.Check, striped = false, scale = 1f)
        TileChip("DANGER", Color(0xFFFF2E88), Icons.Rounded.Close, striped = true, scale = pulse)
    }
}

@Composable
private fun TileChip(label: String, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, striped: Boolean, scale: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.scale(scale)) {
        Box(Modifier.size(112.dp, 74.dp)) {
            Box(Modifier.fillMaxSize().padding(top = 6.dp).background(color.darken(0.45f), RoundedCornerShape(22.dp)))
            Box(
                Modifier.fillMaxWidth().height(68.dp).clip(RoundedCornerShape(22.dp))
                    .background(Brush.verticalGradient(listOf(color.lighten(0.3f), color)))
                    .border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (striped) Canvas(Modifier.fillMaxSize()) {
                    var x = -size.height
                    while (x < size.width) {
                        drawLine(Color.Black.copy(alpha = 0.18f), Offset(x, size.height), Offset(x + size.height, 0f), 14f)
                        x += 34f
                    }
                }
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(38.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        StrokeText(label, size = 16.sp, strokeWidth = 2.dp, color = color.lighten(0.4f))
    }
}

@Composable
private fun BlazeVisual() {
    val inf = rememberInfiniteTransition(label = "blaze")
    val flick by inf.animateFloat(0.9f, 1.12f, infiniteRepeatable(tween(260), RepeatMode.Reverse), label = "f")
    val bob by inf.animateFloat(0f, 6.283f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "b")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
            GlowPulse(GC.Orange, Modifier.size(140.dp))
            Icon(Icons.Rounded.LocalFireDepartment, null, tint = GC.Yellow, modifier = Modifier.size(104.dp).scale(flick))
            Icon(Icons.Rounded.LocalFireDepartment, null, tint = GC.Orange.copy(alpha = 0.85f), modifier = Modifier.size(74.dp).offset(y = 10.dp).scale(flick * 0.96f))
        }
        Column(Modifier.offset(y = (sin(bob) * 5).dp)) {
            StrokeText("x3", size = 44.sp, brush = Brush.verticalGradient(listOf(Color.White, GC.Yellow)), strokeWidth = 4.dp)
            StrokeText("BLAZE!", size = 26.sp, color = GC.Orange.lighten(0.2f), strokeWidth = 3.dp)
        }
    }
}

@Composable
private fun RewardsVisual() {
    val inf = rememberInfiniteTransition(label = "rw")
    val t by inf.animateFloat(0f, 6.283f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "t")
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
        val items: List<@Composable () -> Unit> = listOf(
            { CoinIcon(56.dp) },
            { GemIcon(54.dp) },
            { ChestIcon(84.dp, open = 0.4f) },
            { BallImage(Catalog.ball("galaxy"), 60.dp) },
            { BallImage(Catalog.ball("gold"), 60.dp) },
        )
        items.forEachIndexed { i, item ->
            Box(Modifier.offset(y = (-abs(sin(t + i * 0.9f)) * 18).dp)) { item() }
        }
    }
}

@Composable
private fun ChosenBallVisual(id: String) {
    // The live 3D scene already shows the chosen ball; this chip just names it.
    val b = Catalog.ball(id)
    val inf = rememberInfiniteTransition(label = "cb")
    val bob by inf.animateFloat(0f, 6.283f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "bob")
    AnimatedContent(id, transitionSpec = { scaleIn(spring(dampingRatio = 0.45f)) togetherWith fadeOut(tween(120)) }, label = "chip") { _ ->
        Row(
            Modifier.offset(y = (sin(bob) * 4).dp)
                .background(Brush.horizontalGradient(listOf(GC.Mint.darken(0.2f), GC.Sky.darken(0.2f))), RoundedCornerShape(24.dp))
                .border(2.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                .padding(start = 8.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BallImage(b, 40.dp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("YOUR BALL", style = GT.body(11.sp, Color.White.copy(alpha = 0.8f)))
                StrokeText(b.name, size = 20.sp, strokeWidth = 2.dp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun BallPickerRow(chosen: String, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        for (id in Catalog.starterBalls) {
            val b = Catalog.ball(id)
            val sel = id == chosen
            val sc by animateFloatAsState(if (sel) 1.1f else 0.92f, spring(dampingRatio = 0.45f), label = "pick$id")
            val click = rememberClick(Sfx.UI_TOGGLE) { onPick(id) }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.scale(sc).noRippleClick(click)) {
                Box(
                    Modifier.size(76.dp)
                        .background(if (sel) GC.Mint.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.06f), RoundedCornerShape(24.dp))
                        .border(3.dp, if (sel) GC.Mint else Color.White.copy(alpha = 0.18f), RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center,
                ) { BallImage(b, 62.dp) }
                Spacer(Modifier.height(4.dp))
                Text(b.name, style = GT.display(13.sp, if (sel) GC.Mint.lighten(0.3f) else Color.White.copy(alpha = 0.8f)))
            }
        }
    }
}
