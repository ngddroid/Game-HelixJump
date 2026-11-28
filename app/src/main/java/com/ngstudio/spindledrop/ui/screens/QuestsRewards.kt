package com.ngstudio.spindledrop.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngstudio.spindledrop.core.data.Economy
import com.ngstudio.spindledrop.core.data.PlayerProfile
import com.ngstudio.spindledrop.game.Haptic
import com.ngstudio.spindledrop.game.Sfx
import com.ngstudio.spindledrop.game.theme.Catalog
import com.ngstudio.spindledrop.ui.Toasts
import com.ngstudio.spindledrop.ui.components.BallImage
import com.ngstudio.spindledrop.ui.components.ChestIcon
import com.ngstudio.spindledrop.ui.components.CoinIcon
import com.ngstudio.spindledrop.ui.components.GameButton
import com.ngstudio.spindledrop.ui.components.GameDialog
import com.ngstudio.spindledrop.ui.components.GameProgressBar
import com.ngstudio.spindledrop.ui.components.GemIcon
import com.ngstudio.spindledrop.ui.components.GlowPulse
import com.ngstudio.spindledrop.ui.components.StarIcon
import com.ngstudio.spindledrop.ui.components.StrokeText
import com.ngstudio.spindledrop.ui.components.TrailPreview
import com.ngstudio.spindledrop.ui.components.drawCoin
import com.ngstudio.spindledrop.ui.theme.GC
import com.ngstudio.spindledrop.ui.theme.GT
import com.ngstudio.spindledrop.ui.theme.LocalServices
import com.ngstudio.spindledrop.ui.theme.darken
import com.ngstudio.spindledrop.ui.theme.lighten
import com.ngstudio.spindledrop.ui.theme.toColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

// ============================================================================ quests tab

@Composable
fun QuestsTab(p: PlayerProfile, dialogs: HubDialogs) {
    val s = LocalServices.current
    LazyColumn(
        Modifier.fillMaxSize().padding(top = topBarSpace()),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = DockSpace + 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Section("Daily Reward", GC.Pink) {
                Text("Day ${p.daily.streak.coerceIn(1, 7)} of 7 · Come back every day for bigger rewards!", style = GT.body(14.sp, GC.Muted))
                Spacer(Modifier.height(10.dp))
                GameButton(if (Economy.canClaimLogin(p)) "CLAIM TODAY" else "CLAIMED ✓", onClick = { dialogs.daily = true },
                    color = if (Economy.canClaimLogin(p)) GC.Pink else Color(0xFF6B5A9A), modifier = Modifier.fillMaxWidth(), height = 50.dp, fontSize = 20.sp,
                    shine = Economy.canClaimLogin(p))
            }
        }
        item {
            Section("Daily Missions", GC.Sky) {
                p.daily.missions.forEachIndexed { i, m ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Color(0x44000000), RoundedCornerShape(16.dp)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.title, style = GT.body(15.sp, weight = FontWeight.SemiBold))
                            Spacer(Modifier.height(6.dp))
                            GameProgressBar(m.progress.toFloat() / m.target, Modifier.fillMaxWidth(), color = GC.Sky, height = 12.dp)
                            Text("${m.progress} / ${m.target}", style = GT.body(12.sp, GC.Muted))
                        }
                        Spacer(Modifier.width(10.dp))
                        when {
                            m.claimed -> Icon(Icons.Rounded.CheckCircle, null, tint = GC.Mint, modifier = Modifier.size(36.dp))
                            m.done -> GameButton(if (m.rewardGems > 0) "${m.rewardGems}" else "${m.rewardCoins}", onClick = {
                                s.audio.sfx(Sfx.REWARD); s.profile.update { Economy.claimMission(it, i) }
                            }, color = GC.Mint, modifier = Modifier.width(96.dp), height = 42.dp, fontSize = 18.sp,
                                leading = { if (m.rewardGems > 0) GemIcon(20.dp) else CoinIcon(22.dp) }, shine = true)
                            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                                if (m.rewardGems > 0) GemIcon(20.dp) else CoinIcon(22.dp)
                                Spacer(Modifier.width(4.dp))
                                Text(if (m.rewardGems > 0) "${m.rewardGems}" else "${m.rewardCoins}", style = GT.display(16.sp))
                            }
                        }
                    }
                }
                Text("New missions every day.", style = GT.body(12.sp, GC.Muted))
            }
        }
        item {
            Section("Trophy Road", GC.Yellow) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StarIcon(true, 26.dp)
                    Spacer(Modifier.width(6.dp))
                    StrokeText("${p.stars.total} stars", size = 20.sp, strokeWidth = 2.dp)
                }
                Spacer(Modifier.height(8.dp))
                val firstUnclaimed = (0 until 400).firstOrNull { it !in p.trophyClaimed } ?: 0
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = (firstUnclaimed - 1).coerceAtLeast(0))
                LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(400) { i ->
                        val m = Economy.milestone(i)
                        val claimed = i in p.trophyClaimed
                        val reached = p.stars.total >= m.stars
                        Column(
                            Modifier.width(92.dp).background(
                                if (reached && !claimed) Brush.verticalGradient(listOf(GC.Yellow.darken(0.2f), GC.Orange.darken(0.4f))) else Brush.verticalGradient(listOf(Color(0x55000000), Color(0x33000000))),
                                RoundedCornerShape(18.dp),
                            ).border(1.5.dp, if (reached) GC.Yellow else Color.White.copy(alpha = 0.15f), RoundedCornerShape(18.dp)).padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) { StarIcon(true, 14.dp); Text(" ${m.stars}", style = GT.display(13.sp)) }
                            Spacer(Modifier.height(6.dp))
                            when {
                                m.chest -> ChestIcon(40.dp)
                                m.gems > 0 -> GemIcon(34.dp)
                                else -> CoinIcon(36.dp)
                            }
                            Text(m.label, style = GT.display(14.sp))
                            Spacer(Modifier.height(4.dp))
                            when {
                                claimed -> Icon(Icons.Rounded.CheckCircle, null, tint = GC.Mint, modifier = Modifier.size(24.dp))
                                reached -> GameButton("GET", onClick = { s.audio.sfx(Sfx.REWARD); s.profile.update { Economy.claimMilestone(it, m) } },
                                    color = GC.Mint, height = 30.dp, fontSize = 14.sp, corner = 12.dp, modifier = Modifier.fillMaxWidth())
                                else -> Text("Locked", style = GT.body(12.sp, GC.Muted))
                            }
                        }
                    }
                }
            }
        }
        item {
            Section("Achievements", GC.Purple) {
                Economy.achievements.forEach { a ->
                    val v = a.value(p)
                    val done = v >= a.target
                    val claimed = a.id in p.achievementsClaimed
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.title, style = GT.body(14.sp, weight = FontWeight.SemiBold))
                            GameProgressBar((v.toFloat() / a.target).coerceIn(0f, 1f), Modifier.fillMaxWidth().padding(top = 4.dp), color = GC.Purple, height = 10.dp)
                        }
                        Spacer(Modifier.width(10.dp))
                        when {
                            claimed -> Icon(Icons.Rounded.CheckCircle, null, tint = GC.Mint, modifier = Modifier.size(30.dp))
                            done -> GameButton("${a.gems}", onClick = { s.audio.sfx(Sfx.REWARD); s.profile.update { Economy.claimAchievement(it, a) } },
                                color = GC.Mint, modifier = Modifier.width(84.dp), height = 38.dp, fontSize = 16.sp, leading = { GemIcon(18.dp) }, shine = true)
                            else -> Row(verticalAlignment = Alignment.CenterVertically) { GemIcon(18.dp); Text(" ${a.gems}", style = GT.display(14.sp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, color: Color, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(color.darken(0.55f), Color(0xFF1C0A4A))), RoundedCornerShape(24.dp))
            .border(1.5.dp, color.copy(alpha = 0.5f), RoundedCornerShape(24.dp)).padding(16.dp),
    ) {
        StrokeText(title, size = 24.sp, color = color.lighten(0.3f), align = TextAlign.Start)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

// ============================================================================ daily reward

@Composable
fun DailyRewardDialog(visible: Boolean, p: PlayerProfile, onClose: () -> Unit) {
    val s = LocalServices.current
    GameDialog(visible, onDismiss = onClose, title = "Daily Reward", titleColor = GC.Pink, width = 380.dp) {
        val today = p.daily.streak.coerceIn(1, 7)
        val canClaim = Economy.canClaimLogin(p)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            for (row in listOf(1..4, 5..7)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    for (d in row) {
                        val past = d < today || (d == today && !canClaim)
                        val isToday = d == today && canClaim
                        Column(
                            Modifier.weight(if (d == 7) 2f else 1f).height(92.dp)
                                .background(
                                    when { isToday -> Brush.verticalGradient(listOf(GC.Yellow, GC.Orange)); past -> Brush.verticalGradient(listOf(GC.Mint.darken(0.3f), GC.Mint.darken(0.5f))); else -> Brush.verticalGradient(listOf(Color(0x55000000), Color(0x33000000))) },
                                    RoundedCornerShape(16.dp),
                                ).border(2.dp, if (isToday) Color.White else Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp)).padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Day $d", style = GT.display(13.sp))
                            if (d == 7) Row { ChestIcon(36.dp); GemIcon(26.dp) } else CoinIcon(32.dp)
                            if (past) Icon(Icons.Rounded.CheckCircle, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            else Text(if (d == 7) "+10" else "${Economy.loginRewards[d - 1]}", style = GT.display(14.sp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            GameButton(if (canClaim) "CLAIM" else "Come back tomorrow", onClick = {
                if (canClaim) {
                    var label = ""
                    s.profile.update { val (np, l) = Economy.claimLogin(it); label = l; np }
                    s.audio.sfx(Sfx.REWARD); s.audio.haptic(Haptic.SUCCESS)
                    Toasts.show("You got $label!")
                }
                onClose()
            }, color = if (canClaim) GC.Mint else Color(0xFF6B5A9A), modifier = Modifier.fillMaxWidth(), shine = canClaim, fontSize = if (canClaim) 26.sp else 18.sp)
        }
    }
}

// ============================================================================ lucky wheel

@Composable
fun WheelDialog(visible: Boolean, p: PlayerProfile, onClose: () -> Unit) {
    val s = LocalServices.current
    val rot = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var spinning by remember { mutableStateOf(false) }
    var prizeIdx by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        var lastSeg = 0
        snapshotFlow { rot.value }.collect { v ->
            val seg = floor(v / 45f).toInt()
            if (seg != lastSeg) { lastSeg = seg; if (spinning) { s.audio.sfx(Sfx.WHEEL_TICK, volume = 0.7f); s.audio.haptic(Haptic.TICK) } }
        }
    }
    fun spin(free: Boolean) {
        if (spinning) return
        val idx = Economy.spinIndex(System.nanoTime())
        spinning = true
        prizeIdx = -1
        scope.launch {
            val base = rot.value - (rot.value % 360f)
            val target = base + 360f * 6 + (360f - (idx * 45f + 22.5f))
            rot.animateTo(target, tween(4200, easing = CubicBezierEasing(0.15f, 0.85f, 0.25f, 1f)))
            spinning = false
            prizeIdx = idx
            val prize = Economy.wheel[idx]
            s.profile.update { Economy.applyPrize(it, prize, markSpin = free) }
            s.audio.sfx(Sfx.WHEEL_WIN); s.audio.haptic(Haptic.SUCCESS)
        }
    }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelStyle = GT.display(15.sp).copy(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f), 3f))
    GameDialog(visible, onDismiss = { if (!spinning) { prizeIdx = -1; onClose() } }, title = "Lucky Spin", titleColor = GC.Yellow, width = 380.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
                GlowPulse(GC.Yellow, Modifier.size(290.dp))
                Canvas(Modifier.size(260.dp)) {
                    val r = size.minDimension / 2
                    drawCircle(GC.Ink, r)
                    rotate(rot.value) {
                        for ((i, pr) in Economy.wheel.withIndex()) {
                            val col = pr.color.toColor()
                            drawArc(Brush.radialGradient(listOf(col.lighten(0.25f), col), center = center, radius = r), -90f + i * 45f, 45f, true,
                                topLeft = Offset(center.x - r * 0.94f, center.y - r * 0.94f), size = Size(r * 1.88f, r * 1.88f))
                            // Icon + amount per segment, rotated to point at the centre.
                            rotate(i * 45f + 22.5f, pivot = center) {
                                val ic = Offset(center.x, center.y - r * 0.68f)
                                when {
                                    pr.chest -> {
                                        val w = r * 0.26f; val h = r * 0.2f
                                        drawRoundRect(Color(0xFF6B3A1E), topLeft = Offset(ic.x - w / 2, ic.y - h / 2 + h * 0.1f), size = Size(w, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f))
                                        drawRoundRect(Color(0xFFC07A45), topLeft = Offset(ic.x - w / 2, ic.y - h / 2), size = Size(w, h * 0.45f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.2f))
                                        drawRect(GC.Yellow, topLeft = Offset(ic.x - w * 0.08f, ic.y - h / 2), size = Size(w * 0.16f, h * 1.05f))
                                    }
                                    pr.gems > 0 -> {
                                        val g = r * 0.13f
                                        val gem = Path().apply {
                                            moveTo(ic.x - g * 0.6f, ic.y - g * 0.7f); lineTo(ic.x + g * 0.6f, ic.y - g * 0.7f); lineTo(ic.x + g, ic.y - g * 0.15f)
                                            lineTo(ic.x, ic.y + g); lineTo(ic.x - g, ic.y - g * 0.15f); close()
                                        }
                                        drawPath(gem, Brush.verticalGradient(listOf(Color(0xFFBDF2FF), Color(0xFF1E88E5)), startY = ic.y - g, endY = ic.y + g))
                                        drawPath(gem, Color(0xFF0D47A1), style = Stroke(r * 0.015f))
                                    }
                                    else -> translateDraw(ic, r * 0.13f) { drawCoin() }
                                }
                                val label = measurer.measure(if (pr.chest) "CHEST" else if (pr.gems > 0) "${pr.gems}" else "${pr.coins}", labelStyle)
                                drawText(label, topLeft = Offset(center.x - label.size.width / 2f, center.y - r * 0.44f - label.size.height / 2f))
                            }
                        }
                        for (k in 0 until 8) {
                            val a = Math.toRadians((-90 + k * 45).toDouble()).toFloat()
                            drawLine(Color.White.copy(alpha = 0.6f), center, Offset(center.x + cos(a) * r * 0.94f, center.y + sin(a) * r * 0.94f), 4f)
                            drawCircle(GC.Yellow, r * 0.035f, Offset(center.x + cos(a) * r * 0.97f, center.y + sin(a) * r * 0.97f))
                        }
                    }
                    drawCircle(GC.Yellow, r, style = Stroke(r * 0.07f))
                    drawCircle(Brush.radialGradient(listOf(Color.White, GC.Pink)), r * 0.14f)
                    // Pointer.
                    val ptr = Path().apply { moveTo(center.x - r * 0.1f, 0f); lineTo(center.x + r * 0.1f, 0f); lineTo(center.x, r * 0.2f); close() }
                    drawPath(ptr, GC.Red); drawPath(ptr, Color.White, style = Stroke(4f))
                }
            }
            // Labels below for clarity.
            if (prizeIdx >= 0) {
                val pr = Economy.wheel[prizeIdx]
                StrokeText(
                    when { pr.chest -> "You won a CHEST!"; pr.gems > 0 -> "You won ${pr.gems} gems!"; else -> "You won ${pr.coins} coins!" },
                    size = 24.sp, color = GC.Yellow,
                )
            } else Text("Spin once a day for free!", style = GT.body(15.sp, GC.Muted))
            Spacer(Modifier.height(12.dp))
            val canFree = Economy.canSpin(p)
            when {
                canFree -> GameButton("SPIN", onClick = { spin(true) }, color = GC.Mint, modifier = Modifier.fillMaxWidth(), shine = !spinning, enabled = !spinning)
                com.ngstudio.spindledrop.BuildConfig.DEBUG && s.ads.isAvailable -> GameButton("SPIN AGAIN", onClick = { s.ads.showRewarded("wheel") { ok -> if (ok) spin(false) } }, color = GC.Sky,
                    modifier = Modifier.fillMaxWidth(), enabled = !spinning, sub = "Watch ad")
                else -> GameButton("Back tomorrow", onClick = onClose, color = Color(0xFF6B5A9A), modifier = Modifier.fillMaxWidth(), fontSize = 20.sp)
            }
        }
    }
}

private inline fun androidx.compose.ui.graphics.drawscope.DrawScope.translateDraw(c: Offset, half: Float, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    drawContext.canvas.save()
    drawContext.transform.translate(c.x - half, c.y - half)
    val old = drawContext.size
    drawContext.size = Size(half * 2, half * 2)
    block()
    drawContext.size = old
    drawContext.canvas.restore()
}

// ============================================================================ chest

@Composable
fun ChestDialog(visible: Boolean, p: PlayerProfile, onClose: () -> Unit) {
    val s = LocalServices.current
    var phase by remember { mutableIntStateOf(0) } // 0 closed, 1 opening, 2 revealed
    var reward by remember { mutableStateOf<Economy.ChestReward?>(null) }
    val open = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(visible) { if (visible) { phase = 0; reward = null; open.snapTo(0f) } }
    val inf = rememberInfiniteTransition(label = "chest")
    val shake by inf.animateFloat(-5f, 5f, infiniteRepeatable(tween(120), RepeatMode.Reverse), label = "cs")
    val rays by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "rays")
    GameDialog(visible, onDismiss = if (phase == 1) null else onClose, title = "Treasure Chest", titleColor = GC.Orange) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                if (phase == 2) Canvas(Modifier.size(220.dp)) {
                    rotate(rays) {
                        for (k in 0 until 12) drawArc(GC.Yellow.copy(alpha = 0.25f), k * 30f, 12f, true)
                    }
                }
                when (val r = reward) {
                    null -> ChestIcon(150.dp, Modifier.graphicsLayer { rotationZ = if (phase == 0 && p.chests > 0) shake * 0.6f else 0f }, open = open.value)
                    is Economy.ChestReward.Coins -> Column(horizontalAlignment = Alignment.CenterHorizontally) { CoinIcon(96.dp); StrokeText("+${r.amount}", size = 36.sp, color = GC.Yellow) }
                    is Economy.ChestReward.Gems -> Column(horizontalAlignment = Alignment.CenterHorizontally) { GemIcon(90.dp); StrokeText("+${r.amount}", size = 36.sp, color = GC.Sky) }
                    is Economy.ChestReward.Item -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (r.kind == "ball") BallImage(Catalog.ball(r.id), 120.dp) else TrailPreview(Catalog.trail(r.id), Modifier.size(110.dp))
                        StrokeText(r.name, size = 26.sp, color = GC.Yellow)
                        Text("New ${r.kind} unlocked!", style = GT.body(14.sp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            when {
                phase == 2 -> GameButton("AWESOME!", onClick = onClose, color = GC.Mint, modifier = Modifier.fillMaxWidth())
                p.chests > 0 -> GameButton("OPEN", onClick = {
                    if (phase != 0) return@GameButton
                    phase = 1
                    scope.launch {
                        s.audio.sfx(Sfx.CHEST_SHAKE)
                        delay(500)
                        s.audio.sfx(Sfx.CHEST_OPEN); s.audio.haptic(Haptic.SUCCESS)
                        open.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 120f))
                        var got: Economy.ChestReward? = null
                        s.profile.update { val (np, rw) = Economy.openChest(it, System.nanoTime()); got = rw; np }
                        reward = got
                        phase = 2
                        s.audio.sfx(Sfx.REWARD)
                    }
                }, color = GC.Orange, modifier = Modifier.fillMaxWidth(), shine = true, sfx = Sfx.UI_OPEN)
                else -> {
                    Text("No chests right now. Beat boss levels to earn more!", style = GT.body(15.sp), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    GameButton("OK", onClick = onClose, color = GC.Purple, modifier = Modifier.fillMaxWidth(), height = 50.dp)
                }
            }
        }
    }
}

@Suppress("unused") private val piRef = PI
@Suppress("unused") private fun Modifier.unusedScale() = this.scale(1f).offset(0.dp)
