package com.ngstudio.spindledrop.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngstudio.spindledrop.core.data.PlayerProfile
import com.ngstudio.spindledrop.game.Mode
import com.ngstudio.spindledrop.game.Sfx
import com.ngstudio.spindledrop.game.level.Difficulty
import com.ngstudio.spindledrop.game.theme.Worlds
import com.ngstudio.spindledrop.ui.LocalNav
import com.ngstudio.spindledrop.ui.Route
import com.ngstudio.spindledrop.ui.Toasts
import com.ngstudio.spindledrop.ui.components.GameButton
import com.ngstudio.spindledrop.ui.components.GameDialog
import com.ngstudio.spindledrop.ui.components.LockIcon
import com.ngstudio.spindledrop.ui.components.RoundButton
import com.ngstudio.spindledrop.ui.components.StarIcon
import com.ngstudio.spindledrop.ui.components.StrokeText
import com.ngstudio.spindledrop.ui.components.WorldPreview
import com.ngstudio.spindledrop.ui.components.noRippleClick
import com.ngstudio.spindledrop.ui.components.rememberClick
import com.ngstudio.spindledrop.ui.theme.GC
import com.ngstudio.spindledrop.ui.theme.GT
import com.ngstudio.spindledrop.ui.theme.LocalServices
import com.ngstudio.spindledrop.ui.theme.darken
import com.ngstudio.spindledrop.ui.theme.lighten
import com.ngstudio.spindledrop.ui.theme.toComposeColor
import kotlinx.coroutines.launch

private const val CHAPTERS = Difficulty.MAX_LEVEL / Difficulty.LEVELS_PER_CHAPTER

@Composable
fun LevelsTab(p: PlayerProfile, dialogs: HubDialogs) {
    val nav = LocalNav.current
    val current = p.currentLevel
    val state = rememberLazyListState(initialFirstVisibleItemIndex = Difficulty.chapter(current))
    val scope = rememberCoroutineScope()
    var goTo by remember { mutableStateOf(false) }
    val open: (Int) -> Unit = { lv ->
        if (p.isUnlocked(lv)) nav.push(Route.Play(Mode.LEVEL, lv)) else dialogs.unlockLevel = lv
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = topBarSpace())) {
            LevelsHeaderBar(
                current = current,
                onGoTo = { goTo = true },
                onCurrent = { scope.launch { state.animateScrollToItem(Difficulty.chapter(current)) } },
            )
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(top = 4.dp, bottom = DockSpace + 24.dp, start = 14.dp, end = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(CHAPTERS, key = { it }) { ch -> ChapterCard(ch, p, open) }
            }
        }
        GoToLevelDialog(goTo, onClose = { goTo = false }) { lv ->
            goTo = false
            scope.launch { state.animateScrollToItem(Difficulty.chapter(lv)) }
            open(lv)
        }
    }
}

/** Sticky bar at the top of the level map: jump to any level, or back to your current one. */
@Composable
private fun LevelsHeaderBar(current: Int, onGoTo: () -> Unit, onCurrent: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HeaderPill("Go to level", Icons.Rounded.Search, GC.Purple, Modifier.weight(1f), onGoTo)
        HeaderPill("My level: $current", Icons.Rounded.MyLocation, GC.Pink, Modifier.weight(1f), onCurrent)
    }
}

@Composable
private fun HeaderPill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val click = rememberClick(Sfx.UI_TAP, onClick)
    Row(
        modifier.height(44.dp).clip(RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(color.lighten(0.25f), color.darken(0.1f))))
            .border(1.5.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(22.dp))
            .noRippleClick(click).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        StrokeText(text, size = 15.sp, strokeWidth = 2.dp, stroke = color.darken(0.6f), maxLines = 1)
    }
}

@Composable
private fun ChapterCard(ch: Int, p: PlayerProfile, onLevel: (Int) -> Unit) {
    val first = ch * 10 + 1
    val world = Worlds.forLevel(first)
    val a = world.uiA.toComposeColor()
    val b = world.uiB.toComposeColor()
    val stars = (first until first + 10).sumOf { p.stars[it] }
    val shape = RoundedCornerShape(26.dp)
    val chapterLocked = !p.isUnlocked(first) && (first until first + 10).none { p.isUnlocked(it) }
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.verticalGradient(listOf(a.darken(0.35f), Color(0xFF1E0B4C))))
            .border(2.dp, Brush.verticalGradient(listOf(b.lighten(0.2f).copy(alpha = 0.8f), Color.White.copy(alpha = 0.08f))), shape),
    ) {
        Box(Modifier.fillMaxWidth().height(92.dp)) {
            WorldPreview(world, Modifier.fillMaxSize(), showTower = false)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xDD140530), Color(0x33140530)))))
            Column(Modifier.align(Alignment.CenterStart).padding(start = 16.dp)) {
                Text("CHAPTER ${ch + 1}", style = GT.body(13.sp, Color.White.copy(alpha = 0.8f), androidx.compose.ui.text.font.FontWeight.Bold))
                StrokeText(Worlds.displayName(first), size = 22.sp, strokeWidth = 3.dp, maxLines = 1, align = TextAlign.Start)
                Text("Levels $first – ${first + 9}", style = GT.body(13.sp, Color.White.copy(alpha = 0.75f)))
            }
            Row(Modifier.align(Alignment.TopEnd).padding(10.dp).background(Color(0x88000000), RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                StarIcon(true, 18.dp)
                Spacer(Modifier.width(4.dp))
                Text("$stars/30", style = GT.display(14.sp))
            }
            if (chapterLocked) LockIcon(30.dp, Modifier.align(Alignment.BottomEnd).padding(10.dp))
        }
        // Snake path of 10 nodes: row 1 left->right, row 2 right->left.
        Box(Modifier.fillMaxWidth().height(170.dp).padding(horizontal = 8.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val cw = size.width / 5f
                val y1 = size.height * 0.27f; val y2 = size.height * 0.73f
                val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 12f))
                val col = Color.White.copy(alpha = 0.25f)
                drawLine(col, Offset(cw * 0.5f, y1), Offset(cw * 4.5f, y1), 8f, StrokeCap.Round, dash)
                drawLine(col, Offset(cw * 4.5f, y1), Offset(cw * 4.5f, y2), 8f, StrokeCap.Round, dash)
                drawLine(col, Offset(cw * 4.5f, y2), Offset(cw * 0.5f, y2), 8f, StrokeCap.Round, dash)
            }
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                Row(Modifier.fillMaxWidth()) { for (k in 0 until 5) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { LevelNode(first + k, p, a, onLevel) } }
                Row(Modifier.fillMaxWidth()) { for (k in 9 downTo 5) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { LevelNode(first + k, p, a, onLevel) } }
            }
        }
    }
}

@Composable
private fun LevelNode(level: Int, p: PlayerProfile, worldColor: Color, onLevel: (Int) -> Unit) {
    val s = LocalServices.current
    val boss = Difficulty.isBoss(level)
    val unlocked = p.isUnlocked(level)
    val cleared = level <= p.maxCleared || p.stars[level] > 0
    val isCurrent = level == p.currentLevel
    val size = if (boss) 60.dp else 52.dp
    val pulse = if (isCurrent) {
        val inf = rememberInfiniteTransition(label = "node")
        inf.animateFloat(1f, 1.12f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "np").value
    } else 1f
    val fill = when {
        isCurrent -> GC.Yellow
        cleared -> worldColor.lighten(0.1f)
        unlocked -> GC.Sky
        else -> Color(0xFF4A3F6E)
    }
    val click = rememberClick(if (unlocked) Sfx.UI_TAP else Sfx.ERROR) { onLevel(level) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.noRippleClick(click)) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.scale(pulse).size(size).offset(y = 3.dp).background(fill.darken(0.45f), CircleShape))
            Box(
                Modifier.scale(pulse).size(size).clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(fill.lighten(0.35f), fill)))
                    .border(2.5.dp, if (isCurrent) Color.White else fill.darken(0.3f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (unlocked) StrokeText("$level", size = if (level >= 1000) 13.sp else if (level >= 100) 16.sp else 20.sp, strokeWidth = 2.dp, maxLines = 1,
                    stroke = fill.darken(0.6f))
                else LockIcon(22.dp)
            }
            if (boss) Icon(Icons.Rounded.WorkspacePremium, null, tint = GC.Yellow, modifier = Modifier.size(24.dp).align(Alignment.TopCenter).offset(y = (-16).dp))
        }
        Row(Modifier.height(14.dp).padding(top = 2.dp)) {
            if (cleared) for (k in 1..3) StarIcon(p.stars[level] >= k, 12.dp)
        }
        if (s.ads.isAvailable && !unlocked && level == p.currentLevel + 1) Unit
    }
}

@Composable
private fun GoToLevelDialog(visible: Boolean, onClose: () -> Unit, onGo: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    GameDialog(visible, onDismiss = onClose, title = "Go to Level", titleColor = GC.Sky) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Enter a level between 1 and 10,000", style = GT.body(15.sp, GC.Muted))
            Spacer(Modifier.height(12.dp))
            BasicTextField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() }.take(5) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = GT.display(34.sp).copy(textAlign = TextAlign.Center),
                cursorBrush = SolidColor(GC.Yellow),
                modifier = Modifier.fillMaxWidth().background(Color(0x55000000), RoundedCornerShape(18.dp))
                    .border(2.dp, GC.Sky, RoundedCornerShape(18.dp)).padding(vertical = 12.dp),
            )
            Spacer(Modifier.height(16.dp))
            GameButton("GO", onClick = {
                val lv = text.toIntOrNull()
                if (lv == null || lv !in 1..Difficulty.MAX_LEVEL) Toasts.show("Please enter 1 to 10,000") else onGo(lv)
            }, color = GC.Sky, modifier = Modifier.fillMaxWidth(), height = 54.dp)
        }
    }
}
