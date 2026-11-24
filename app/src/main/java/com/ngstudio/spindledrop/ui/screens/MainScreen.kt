package com.ngstudio.spindledrop.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Toll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngstudio.spindledrop.core.audio.Music
import com.ngstudio.spindledrop.core.data.Economy
import com.ngstudio.spindledrop.core.data.PlayerProfile
import com.ngstudio.spindledrop.game.Cosmetics
import com.ngstudio.spindledrop.game.EngineCommand
import com.ngstudio.spindledrop.game.GameEngine
import com.ngstudio.spindledrop.game.Mode
import com.ngstudio.spindledrop.game.Quality
import com.ngstudio.spindledrop.game.Sfx
import com.ngstudio.spindledrop.game.level.Difficulty
import com.ngstudio.spindledrop.game.theme.Catalog
import com.ngstudio.spindledrop.game.theme.Worlds
import com.ngstudio.spindledrop.ui.LocalNav
import com.ngstudio.spindledrop.ui.Route
import com.ngstudio.spindledrop.ui.Toasts
import com.ngstudio.spindledrop.ui.components.Avatar
import com.ngstudio.spindledrop.ui.components.Badge
import com.ngstudio.spindledrop.ui.components.ChestIcon
import com.ngstudio.spindledrop.ui.components.CurrencyPill
import com.ngstudio.spindledrop.ui.components.GameButton
import com.ngstudio.spindledrop.ui.components.GameDialog
import com.ngstudio.spindledrop.ui.components.GameSurface
import com.ngstudio.spindledrop.ui.components.LockIcon
import com.ngstudio.spindledrop.ui.components.MenuBackground
import com.ngstudio.spindledrop.ui.components.RoundButton
import com.ngstudio.spindledrop.ui.components.StarIcon
import com.ngstudio.spindledrop.ui.components.StrokeText
import com.ngstudio.spindledrop.ui.components.noRippleClick
import com.ngstudio.spindledrop.ui.components.rememberClick
import com.ngstudio.spindledrop.ui.theme.GC
import com.ngstudio.spindledrop.ui.theme.GT
import com.ngstudio.spindledrop.ui.theme.LocalServices
import com.ngstudio.spindledrop.ui.theme.darken
import com.ngstudio.spindledrop.ui.theme.lighten
import com.ngstudio.spindledrop.ui.theme.toComposeColor

/** Shared state for dialogs opened from the main hub. */
class HubDialogs {
    var daily by mutableStateOf(false)
    var wheel by mutableStateOf(false)
    var chest by mutableStateOf(false)
    var unlockLevel by mutableIntStateOf(0)
}

fun cosmeticsOf(p: PlayerProfile) = Cosmetics(Catalog.ball(p.ball), Catalog.trail(p.trail), Catalog.splat(p.splat))

fun applyEngineSettings(engine: GameEngine, p: PlayerProfile) {
    val st = p.settings
    engine.send(EngineCommand.SetQuality(st.quality, st.autoQuality))
    engine.send(EngineCommand.SetSensitivity(st.sensitivity))
    engine.send(EngineCommand.SetCosmetics(cosmeticsOf(p)))
    engine.send(EngineCommand.SetThemeOverride(p.lockedTheme))
}

@Composable
fun MainScreen() {
    val s = LocalServices.current
    val nav = LocalNav.current
    val profile by s.profile.state.collectAsState()
    val dialogs = remember { HubDialogs() }
    val engine = remember { GameEngine(s.audio) }
    LaunchedEffect(Unit) {
        s.profile.update { Economy.refreshDaily(it) }
        s.audio.playMusic(Music.MENU)
    }
    LaunchedEffect(profile.ball, profile.trail, profile.splat, profile.lockedTheme, profile.settings) { applyEngineSettings(engine, profile) }
    LaunchedEffect(profile.currentLevel) { engine.send(EngineCommand.Preview(profile.currentLevel)) }
    val tab = nav.tab
    LaunchedEffect(tab) { engine.send(EngineCommand.SetPaused(tab != 0)) }
    var exitAsk by remember { mutableStateOf(false) }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    // Back: other tab -> Home tab; Home tab -> confirm before leaving the game.
    androidx.activity.compose.BackHandler {
        s.audio.sfx(Sfx.UI_BACK)
        if (nav.tab != 0) nav.tab = 0 else exitAsk = true
    }

    Box(Modifier.fillMaxSize()) {
        // Live 3D tower behind the Home tab (paused on other tabs).
        GameSurface(engine, Modifier.fillMaxSize(), active = tab == 0)
        if (tab != 0) MenuBackground()
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(tab, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) }, label = "tab") { t ->
                when (t) {
                    0 -> HomeTab(profile, dialogs)
                    1 -> LevelsTab(profile, dialogs)
                    2 -> ShopTab(profile)
                    3 -> ThemesTab(profile)
                    else -> QuestsTab(profile, dialogs)
                }
            }
            val chromeAlpha by animateFloatAsState(if (com.ngstudio.spindledrop.ui.components.DialogTracker.open > 0) 0f else 1f, tween(180), label = "chrome")
            if (chromeAlpha > 0.01f) {
                Box(Modifier.graphicsLayer { alpha = chromeAlpha }) { TopBar(profile, solid = tab != 0) }
                DockBar(tab, profile, Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = chromeAlpha }) { nav.tab = it }
            }
        }
        DailyRewardDialog(dialogs.daily, profile) { dialogs.daily = false }
        WheelDialog(dialogs.wheel, profile) { dialogs.wheel = false }
        ChestDialog(dialogs.chest, profile) { dialogs.chest = false }
        UnlockLevelDialog(dialogs.unlockLevel, profile) { dialogs.unlockLevel = 0 }
        GameDialog(exitAsk, onDismiss = { exitAsk = false }, title = "Leaving?", titleColor = GC.Purple) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Your progress is saved. See you soon!", style = GT.body(16.sp), textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GameButton("Stay", onClick = { exitAsk = false }, color = GC.Mint, modifier = Modifier.weight(1f), height = 52.dp, fontSize = 20.sp)
                    GameButton("Quit", onClick = { exitAsk = false; activity?.finish() }, color = GC.Red, modifier = Modifier.weight(1f), height = 52.dp, fontSize = 20.sp, sfx = Sfx.UI_BACK)
                }
            }
        }
    }
}

/** Height of the top bar below the status/cut-out inset. */
val TopBarHeight = 64.dp

/** Space from the top of the screen to just below the top bar (tabs start their scroll area here). */
@Composable
fun topBarSpace(): Dp = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + TopBarHeight

// ============================================================================ top bar

@Composable
private fun TopBar(p: PlayerProfile, solid: Boolean) {
    val nav = LocalNav.current
    val bg = if (solid) Brush.verticalGradient(0f to Color(0xFF1C0B4E), 0.82f to Color(0xF21C0B4E), 1f to Color(0x001C0B4E))
             else Brush.verticalGradient(listOf(Color(0xAA0C0420), Color.Transparent))
    Row(
        Modifier.fillMaxWidth().background(bg).safeDrawingPadding().height(TopBarHeight).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val click = rememberClick { nav.push(Route.Profile) }
        Box(Modifier.noRippleClick(click)) {
            Avatar(p.avatar, 46.dp)
            Box(Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 4.dp).background(GC.Yellow, RoundedCornerShape(8.dp)).border(1.5.dp, GC.Ink, RoundedCornerShape(8.dp)).padding(horizontal = 5.dp)) {
                Text("${p.currentLevel}", style = GT.display(11.sp, GC.Ink))
            }
        }
        Spacer(Modifier.width(10.dp))
        CurrencyPill(p.coins)
        Spacer(Modifier.width(8.dp))
        CurrencyPill(p.gems, gem = true)
        Spacer(Modifier.weight(1f))
        RoundButton(onClick = { nav.push(Route.Settings) }, color = GC.Violet, size = 44.dp) {
            Icon(Icons.Rounded.Settings, null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }
}

// ============================================================================ floating dock (bottom navigation)

/** Height reserved at the bottom of every tab for the floating dock. */
val DockSpace = 104.dp

private data class TabItem(val label: String, val icon: ImageVector, val color: Color)

@Composable
private fun DockBar(selected: Int, p: PlayerProfile, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val items = listOf(
        TabItem("Home", Icons.Rounded.Home, GC.Pink),
        TabItem("Levels", Icons.Rounded.Map, GC.Sky),
        TabItem("Shop", Icons.Rounded.ShoppingBag, GC.Orange),
        TabItem("Themes", Icons.Rounded.Palette, GC.Mint),
        TabItem("Quests", Icons.Rounded.EmojiEvents, GC.Purple),
    )
    val questBadge = p.daily.missions.any { it.done && !it.claimed } || Economy.canClaimLogin(p) ||
        Economy.achievements.any { it.id !in p.achievementsClaimed && it.value(p) >= it.target }
    val shape = RoundedCornerShape(30.dp)
    val tileColor by animateColorAsState(items[selected].color, tween(260), label = "tileColor")
    Box(
        modifier
            .safeDrawingPadding()
            .padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
            .fillMaxWidth()
            .height(78.dp)
            .shadow(20.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .background(Brush.verticalGradient(listOf(Color(0xFF3B1C93), Color(0xFF1B0A52))), shape)
            .border(1.5.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.40f), Color.White.copy(alpha = 0.06f))), shape),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(7.dp)) {
            val slot = maxWidth / items.size
            val x by animateDpAsState(slot * selected, spring(dampingRatio = 0.68f, stiffness = 520f), label = "tileX")
            val tileShape = RoundedCornerShape(23.dp)
            // The sliding 3D tile (stays fully inside the dock, so nothing is ever clipped).
            Box(Modifier.offset(x = x).width(slot).fillMaxHeight().padding(horizontal = 2.dp)) {
                Box(Modifier.fillMaxSize().padding(top = 4.dp).background(tileColor.darken(0.45f), tileShape))
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight().padding(bottom = 4.dp)
                        .clip(tileShape)
                        .background(Brush.verticalGradient(listOf(tileColor.lighten(0.32f), tileColor, tileColor.darken(0.1f))))
                        .border(1.5.dp, Color.White.copy(alpha = 0.5f), tileShape)
                        .drawBehind {
                            drawRoundRect(
                                Color.White.copy(alpha = 0.28f),
                                topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.12f, size.height * 0.07f),
                                size = androidx.compose.ui.geometry.Size(size.width * 0.76f, size.height * 0.26f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * 0.13f),
                            )
                        },
                )
            }
            Row(Modifier.fillMaxSize()) {
                items.forEachIndexed { i, it ->
                    val sel = i == selected
                    val sc by animateFloatAsState(if (sel) 1.12f else 1f, spring(dampingRatio = 0.45f, stiffness = 600f), label = "iconScale")
                    val lift by animateDpAsState(if (sel) (-2).dp else 0.dp, spring(dampingRatio = 0.5f), label = "iconLift")
                    val click = rememberClick(Sfx.UI_TAP) { onSelect(i) }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().noRippleClick(click).padding(bottom = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Box(Modifier.offset(y = lift).scale(sc)) {
                            Icon(it.icon, it.label, tint = if (sel) Color.White else it.color.copy(alpha = 0.55f).compositeOverDock(), modifier = Modifier.size(27.dp))
                            val badge = (i == 4 && questBadge) || (i == 0 && p.chests > 0)
                            if (badge && !sel) Box(Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-3).dp).size(11.dp).background(GC.Red, CircleShape).border(2.dp, Color(0xFF2A1170), CircleShape))
                        }
                        Spacer(Modifier.height(2.dp))
                        if (sel) StrokeText(it.label, size = 12.sp, strokeWidth = 2.dp, stroke = it.color.darken(0.55f), maxLines = 1)
                        else Text(it.label, style = GT.display(12.sp, GC.Muted.copy(alpha = 0.85f)), maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Blend a translucent tab colour over the dock so unselected icons are soft but tinted. */
private fun Color.compositeOverDock(): Color {
    val base = Color(0xFF2C1478)
    return Color(
        red = red * alpha + base.red * (1 - alpha),
        green = green * alpha + base.green * (1 - alpha),
        blue = blue * alpha + base.blue * (1 - alpha),
    ).lighten(0.35f)
}

// ============================================================================ home tab

@Composable
private fun HomeTab(p: PlayerProfile, dialogs: HubDialogs) {
    val nav = LocalNav.current
    val level = p.currentLevel
    val world = Worlds.forLevel(level)
    val chapter = Difficulty.chapter(level)
    Box(Modifier.fillMaxSize()) {
        // Legibility gradients over the live 3D tower.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x880C0420), 0.25f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color(0xEE0C0420))))
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // World chip.
            Row(
                Modifier.background(Brush.horizontalGradient(listOf(world.uiA.toComposeColor(), world.uiB.toComposeColor())), RoundedCornerShape(20.dp))
                    .border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StrokeText(Worlds.displayName(level), size = 16.sp, strokeWidth = 2.dp, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Text("Ch. ${chapter + 1}", style = GT.body(13.sp, Color.White.copy(alpha = 0.9f)))
            }
            Spacer(Modifier.weight(1f))
            // Side quick buttons.
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.Bottom) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    QuickButton("Daily", Icons.Rounded.CardGiftcard, GC.Pink, badge = Economy.canClaimLogin(p)) { dialogs.daily = true }
                    QuickButton("Spin", Icons.Rounded.Toll, GC.Yellow, badge = Economy.canSpin(p), locked = p.maxCleared < Economy.WHEEL_UNLOCK,
                        lockText = "Clear level ${Economy.WHEEL_UNLOCK}") { dialogs.wheel = true }
                    if (p.chests > 0) ChestQuick(p.chests) { dialogs.chest = true }
                }
                Spacer(Modifier.weight(1f))
                Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.End) {
                    QuickButton("Endless", Icons.Rounded.AllInclusive, GC.Sky, locked = p.maxCleared < Economy.ENDLESS_UNLOCK,
                        lockText = "Clear level ${Economy.ENDLESS_UNLOCK}") { nav.push(Route.Play(Mode.ENDLESS, seed = System.currentTimeMillis())) }
                    val done = p.daily.challengeDoneDay == Economy.today()
                    QuickButton(if (done) "Done!" else "Daily Tower", Icons.Rounded.EventAvailable, GC.Purple, badge = !done && p.maxCleared >= Economy.DAILY_CHALLENGE_UNLOCK,
                        locked = p.maxCleared < Economy.DAILY_CHALLENGE_UNLOCK, lockText = "Clear level ${Economy.DAILY_CHALLENGE_UNLOCK}") {
                        nav.push(Route.Play(Mode.DAILY, level = p.maxCleared, seed = Economy.today()))
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            // Level hero.
            Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(Difficulty.flames(level)) {
                    Icon(Icons.Rounded.LocalFireDepartment, null, tint = GC.Orange, modifier = Modifier.size(22.dp))
                }
                if (Difficulty.isBoss(level)) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.background(GC.Red, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) { Text("BOSS GATE", style = GT.display(13.sp)) }
                }
            }
            StrokeText("LEVEL $level", size = 54.sp, brush = Brush.verticalGradient(listOf(Color.White, Color(0xFFFFE7A3))), strokeWidth = 5.dp)
            Spacer(Modifier.height(4.dp))
            val chapterStars = (chapter * 10 + 1..chapter * 10 + 10).sumOf { p.stars[it] }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StarIcon(true, 22.dp)
                Spacer(Modifier.width(4.dp))
                Text("$chapterStars / 30 in this chapter", style = GT.body(14.sp, Color.White.copy(alpha = 0.9f)))
            }
            Spacer(Modifier.height(16.dp))
            val inf = rememberInfiniteTransition(label = "play")
            val pulse by inf.animateFloat(1f, 1.05f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pp")
            GameButton(
                "PLAY", onClick = { nav.push(Route.Play(Mode.LEVEL, level)) },
                color = GC.Mint, modifier = Modifier.fillMaxWidth(0.72f).scale(pulse), height = 76.dp, fontSize = 40.sp, shine = true, sfx = Sfx.SWOOSH,
            )
            Spacer(Modifier.height(DockSpace))
        }
    }
}

@Composable
private fun QuickButton(label: String, icon: ImageVector, color: Color, badge: Boolean = false, locked: Boolean = false, lockText: String = "", onClick: () -> Unit) {
    val s = LocalServices.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RoundButton(
            onClick = { if (locked) { s.audio.sfx(Sfx.ERROR); Toasts.show("Unlocks after you $lockText") } else onClick() },
            color = if (locked) Color(0xFF5D5480) else color, size = 58.dp, badge = badge && !locked,
        ) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(32.dp))
            if (locked) LockIcon(20.dp, Modifier.align(Alignment.BottomEnd))
        }
        Spacer(Modifier.height(2.dp))
        StrokeText(label, size = 13.sp, strokeWidth = 2.dp, maxLines = 1)
    }
}

@Composable
private fun ChestQuick(count: Int, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "cq")
    val r by inf.animateFloat(-6f, 6f, infiniteRepeatable(tween(300), RepeatMode.Reverse), label = "cqr")
    val click = rememberClick(Sfx.CHEST_SHAKE, onClick)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.noRippleClick(click)) {
        Box {
            ChestIcon(58.dp, Modifier.graphicsLayer { rotationZ = r })
            Box(Modifier.align(Alignment.TopEnd).background(GC.Red, CircleShape).border(2.dp, Color.White, CircleShape).padding(horizontal = 6.dp)) {
                Text("$count", style = GT.display(12.sp))
            }
        }
        StrokeText("Chest", size = 13.sp, strokeWidth = 2.dp)
    }
}


// ============================================================================ unlock dialog

@Composable
fun UnlockLevelDialog(level: Int, p: PlayerProfile, onClose: () -> Unit) {
    val s = LocalServices.current
    val nav = LocalNav.current
    var lastLevel by remember { mutableIntStateOf(1) }
    if (level > 0) lastLevel = level
    GameDialog(level > 0, onDismiss = onClose, title = "Locked", titleColor = GC.Purple) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            LockIcon(64.dp)
            Spacer(Modifier.height(10.dp))
            StrokeText("Level $lastLevel", size = 32.sp)
            Spacer(Modifier.height(8.dp))
            if (s.ads.isAvailable) {
                Text("Watch a short ad to unlock this level right now!", style = GT.body(16.sp), textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                GameButton("Watch Ad", onClick = {
                    s.ads.showRewarded("unlock_level") { ok ->
                        if (ok) {
                            s.profile.update { it.copy(adUnlocked = it.adUnlocked + lastLevel) }
                            s.audio.sfx(Sfx.UNLOCK)
                            Toasts.show("Level $lastLevel unlocked!")
                        }
                    }
                    onClose()
                }, color = GC.Yellow, modifier = Modifier.fillMaxWidth(), sub = "Unlock instantly")
                Spacer(Modifier.height(10.dp))
            } else {
                Text("Clear Level ${p.currentLevel} to keep climbing towards this level.", style = GT.body(16.sp), textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
            }
            GameButton("Play Level ${p.currentLevel}", onClick = { onClose(); nav.push(Route.Play(Mode.LEVEL, p.currentLevel)) },
                color = GC.Mint, modifier = Modifier.fillMaxWidth(), height = 52.dp, fontSize = 20.sp)
        }
    }
}

@Suppress("unused")
private val unusedQuality = Quality.MEDIUM

@Suppress("unused")
private fun Dp.half() = this / 2

@Suppress("unused")
private val darkenRef = GC.Pink.darken(0.1f)
