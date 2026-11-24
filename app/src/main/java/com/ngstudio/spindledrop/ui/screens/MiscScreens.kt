package com.ngstudio.spindledrop.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ngstudio.spindledrop.BuildConfig
import com.ngstudio.spindledrop.game.Mode
import com.ngstudio.spindledrop.game.Quality
import com.ngstudio.spindledrop.game.Sfx
import com.ngstudio.spindledrop.game.level.Difficulty
import com.ngstudio.spindledrop.ui.LocalNav
import com.ngstudio.spindledrop.ui.Route
import com.ngstudio.spindledrop.ui.Toasts
import com.ngstudio.spindledrop.ui.components.Avatar
import com.ngstudio.spindledrop.ui.components.GameButton
import com.ngstudio.spindledrop.ui.components.GameDialog
import com.ngstudio.spindledrop.ui.components.MenuBackground
import com.ngstudio.spindledrop.ui.components.RoundButton
import com.ngstudio.spindledrop.ui.components.StarIcon
import com.ngstudio.spindledrop.ui.components.StrokeText
import com.ngstudio.spindledrop.ui.components.noRippleClick
import com.ngstudio.spindledrop.ui.components.rememberClick
import com.ngstudio.spindledrop.ui.theme.GC
import com.ngstudio.spindledrop.ui.theme.GT
import com.ngstudio.spindledrop.ui.theme.LocalServices
import com.ngstudio.spindledrop.ui.theme.lighten

@Composable
private fun ScreenScaffold(title: String, content: @Composable ColumnScope.() -> Unit) {
    val nav = LocalNav.current
    Box(Modifier.fillMaxSize()) {
        MenuBackground()
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(onClick = { nav.pop() }, color = GC.Violet, size = 46.dp, sfx = Sfx.UI_BACK) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(12.dp))
                StrokeText(title, size = 30.sp, align = TextAlign.Start)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), content = content)
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp)
            .background(Brush.verticalGradient(listOf(Color(0xCC4A2AA8), Color(0xCC2A1166))), RoundedCornerShape(24.dp))
            .border(1.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(24.dp)).padding(16.dp),
    ) {
        Text(title.uppercase(), style = GT.body(13.sp, GC.Yellow, FontWeight.Bold))
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String = "${(value / range.endInclusive * 100).toInt()}%", onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row { Text(label, style = GT.body(16.sp), modifier = Modifier.weight(1f)); Text(valueText, style = GT.body(14.sp, GC.Muted)) }
        Slider(value, onChange, valueRange = range, colors = SliderDefaults.colors(thumbColor = GC.Yellow, activeTrackColor = GC.Mint, inactiveTrackColor = Color(0x55FFFFFF)))
    }
}

@Composable
private fun LinkRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val click = rememberClick(Sfx.UI_TAP, onClick)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x33000000)).noRippleClick(click).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = GC.Sky, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = GT.body(16.sp, weight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = GC.Muted)
    }
}

// ============================================================================ settings

@Composable
fun SettingsScreen() {
    val s = LocalServices.current
    val nav = LocalNav.current
    val p by s.profile.state.collectAsState()
    var reset by remember { mutableIntStateOf(0) }
    var devTaps by remember { mutableIntStateOf(0) }
    fun upd(block: (com.ngstudio.spindledrop.core.data.Settings) -> com.ngstudio.spindledrop.core.data.Settings) =
        s.profile.update { it.copy(settings = block(it.settings)) }
    Box(Modifier.fillMaxSize()) {
        ScreenScaffold("Settings") {
            Card("Sound & Feel") {
                ToggleRow("Music", p.settings.music) { v -> upd { it.copy(music = v) } }
                ToggleRow("Sound effects", p.settings.sfx) { v -> upd { it.copy(sfx = v) } }
                ToggleRow("Vibration", p.settings.vibration) { v -> upd { it.copy(vibration = v) } }
                SliderRow("Music volume", p.settings.musicVolume, 0f..1f) { v -> upd { it.copy(musicVolume = v) } }
                SliderRow("Sound volume", p.settings.sfxVolume, 0f..1f) { v -> upd { it.copy(sfxVolume = v) } }
            }
            Card("Controls & Graphics") {
                SliderRow("Spin sensitivity", p.settings.sensitivity, 0.5f..1.8f, valueText = "%.1fx".format(p.settings.sensitivity)) { v -> upd { it.copy(sensitivity = v) } }
                Text("Graphics quality", style = GT.body(16.sp))
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().background(Color(0x55000000), RoundedCornerShape(18.dp)).padding(4.dp)) {
                    val opts = listOf("Auto" to null, "Low" to Quality.LOW, "Medium" to Quality.MEDIUM, "High" to Quality.HIGH)
                    for ((label, q) in opts) {
                        val sel = if (q == null) p.settings.autoQuality else !p.settings.autoQuality && p.settings.quality == q
                        val click = rememberClick(Sfx.UI_TOGGLE) {
                            upd { if (q == null) it.copy(autoQuality = true, quality = Quality.MEDIUM) else it.copy(autoQuality = false, quality = q) }
                        }
                        Box(
                            Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(14.dp))
                                .background(if (sel) Brush.verticalGradient(listOf(GC.Sky.lighten(0.2f), GC.Sky)) else SolidColor(Color.Transparent))
                                .noRippleClick(click),
                            contentAlignment = Alignment.Center,
                        ) { Text(label, style = GT.display(15.sp)) }
                    }
                }
                Text("Auto lowers effects if your phone gets slow, to keep the game smooth.", style = GT.body(12.sp, GC.Muted), modifier = Modifier.padding(top = 6.dp))
            }
            Card("Legal") {
                LinkRow("Terms of Use", Icons.Rounded.Description) { nav.push(Route.Legal(true)) }
                LinkRow("Privacy Policy", Icons.Rounded.Shield) { nav.push(Route.Legal(false)) }
            }
            Card("Progress") {
                GameButton("Reset progress", onClick = { reset = 1 }, color = GC.Red, modifier = Modifier.fillMaxWidth(), height = 50.dp, fontSize = 19.sp)
            }
            Text(
                "Spindle Drop v${BuildConfig.VERSION_NAME}",
                style = GT.body(13.sp, GC.Muted), textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(16.dp).noRippleClick {
                    if (BuildConfig.DEBUG) { devTaps++; if (devTaps >= 5) { devTaps = 0; nav.push(Route.Dev) } }
                },
            )
            Spacer(Modifier.height(24.dp))
        }
        GameDialog(reset > 0, onDismiss = { reset = 0 }, title = "Reset?", titleColor = GC.Red) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(if (reset == 1) "This deletes all levels, stars, coins and items on this device." else "Are you really sure? This cannot be undone.",
                    style = GT.body(16.sp), textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                GameButton(if (reset == 1) "Yes, reset" else "Delete everything", onClick = {
                    if (reset == 1) reset = 2 else { s.profile.reset(); reset = 0; Toasts.show("Progress reset"); nav.resetTo(Route.Main) }
                }, color = GC.Red, modifier = Modifier.fillMaxWidth(), height = 52.dp, fontSize = 20.sp)
                Spacer(Modifier.height(8.dp))
                GameButton("Cancel", onClick = { reset = 0 }, color = GC.Mint, modifier = Modifier.fillMaxWidth(), height = 48.dp, fontSize = 18.sp)
            }
        }
    }
}

// ============================================================================ legal (themed HTML from assets)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LegalScreen(terms: Boolean) {
    val nav = LocalNav.current
    Box(Modifier.fillMaxSize().background(GC.Night)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(onClick = { nav.pop() }, color = GC.Violet, size = 46.dp, sfx = Sfx.UI_BACK) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(12.dp))
                StrokeText(if (terms) "Terms of Use" else "Privacy Policy", size = 26.sp, align = TextAlign.Start)
            }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(GC.Night.toArgb())
                        settings.javaScriptEnabled = false
                        settings.allowContentAccess = false
                        webViewClient = object : WebViewClient() {
                            // Keep navigation inside the bundled pages.
                            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean =
                                request?.url?.scheme != "file"
                        }
                        loadUrl(if (terms) "file:///android_asset/legal/terms.html" else "file:///android_asset/legal/privacy.html")
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ============================================================================ profile

@Composable
fun ProfileScreen() {
    val s = LocalServices.current
    val p by s.profile.state.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(p.name) }
    ScreenScaffold("Profile") {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(p.avatar, 110.dp)
            Spacer(Modifier.height(10.dp))
            if (editing) {
                BasicTextField(
                    name, { name = it.take(16) }, singleLine = true,
                    textStyle = GT.display(26.sp).copy(textAlign = TextAlign.Center), cursorBrush = SolidColor(GC.Yellow),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(0.7f).background(Color(0x55000000), RoundedCornerShape(16.dp)).border(2.dp, GC.Yellow, RoundedCornerShape(16.dp)).padding(10.dp),
                )
                Spacer(Modifier.height(8.dp))
                GameButton("Save", onClick = { s.profile.update { it.copy(name = name.ifBlank { "Player" }) }; editing = false }, color = GC.Mint,
                    modifier = Modifier.fillMaxWidth(0.5f), height = 46.dp, fontSize = 18.sp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.noRippleClick { editing = true }) {
                    StrokeText(p.name, size = 30.sp)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.Edit, "Edit name", tint = GC.Muted)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Choose your avatar", style = GT.body(13.sp, GC.Muted))
            Spacer(Modifier.height(6.dp))
            for (row in 0..1) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 5.dp)) {
                    for (i in row * 4 until row * 4 + 4) {
                        val sel = i == p.avatar
                        val click = rememberClick { s.profile.update { it.copy(avatar = i) } }
                        Box(
                            Modifier.size(58.dp).border(if (sel) 3.dp else 1.5.dp, if (sel) GC.Yellow else Color.White.copy(alpha = 0.18f), CircleShape).padding(5.dp).noRippleClick(click),
                            contentAlignment = Alignment.Center,
                        ) { Avatar(i, 46.dp) }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        val st = p.stats
        val rows = listOf(
            "Level" to "${p.currentLevel}", "Stars" to "${p.stars.total}",
            "Levels cleared" to "${st.levelsCleared}", "Perfect levels" to "${st.perfectLevels}",
            "Rings passed" to "%,d".format(st.ringsPassed), "Rings smashed" to "%,d".format(st.ringsSmashed),
            "Best combo" to "${st.bestCombo}", "Blazes" to "${st.blazes}",
            "Endless best" to "${st.endlessBest}", "Daily towers" to "${st.dailyChallenges}",
            "Chests opened" to "${st.chestsOpened}", "Play time" to "${st.playSeconds / 3600}h ${(st.playSeconds / 60) % 60}m",
        )
        for (pair in rows.chunked(2)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((k, v) in pair) {
                    Column(
                        Modifier.weight(1f).background(Brush.verticalGradient(listOf(Color(0xCC4A2AA8), Color(0xCC2A1166))), RoundedCornerShape(18.dp))
                            .border(1.5.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(18.dp)).padding(12.dp),
                    ) {
                        Text(k, style = GT.body(13.sp, GC.Muted))
                        StrokeText(v, size = 24.sp, strokeWidth = 2.dp, align = TextAlign.Start, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ============================================================================ debug dev panel

@Composable
fun DevPanelScreen() {
    val s = LocalServices.current
    val nav = LocalNav.current
    val p by s.profile.state.collectAsState()
    var levelText by remember { mutableStateOf("100") }
    ScreenScaffold("Dev Panel") {
        Card("Debug tools (debug build only)") {
            Text("Jump to level", style = GT.body(15.sp))
            BasicTextField(
                levelText, { v -> levelText = v.filter { it.isDigit() }.take(5) }, singleLine = true,
                textStyle = GT.display(24.sp).copy(textAlign = TextAlign.Center), cursorBrush = SolidColor(GC.Yellow),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().background(Color(0x55000000), RoundedCornerShape(14.dp)).padding(8.dp),
            )
            Spacer(Modifier.height(8.dp))
            GameButton("Play level", onClick = {
                val l = levelText.toIntOrNull()?.coerceIn(1, Difficulty.MAX_LEVEL) ?: 1
                s.profile.update { it.copy(adUnlocked = it.adUnlocked + l) }
                nav.push(Route.Play(Mode.LEVEL, l))
            }, color = GC.Mint, modifier = Modifier.fillMaxWidth(), height = 50.dp, fontSize = 20.sp)
            Spacer(Modifier.height(8.dp))
            GameButton("+10,000 coins  +200 gems", onClick = { s.profile.update { it.copy(coins = it.coins + 10000, gems = it.gems + 200) } },
                color = GC.Yellow, modifier = Modifier.fillMaxWidth(), height = 50.dp, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            GameButton("+3 chests", onClick = { s.profile.update { it.copy(chests = it.chests + 3) } }, color = GC.Orange, modifier = Modifier.fillMaxWidth(), height = 50.dp, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            GameButton("Clear levels up to 20", onClick = { s.profile.update { it.copy(maxCleared = maxOf(it.maxCleared, 20)) } }, color = GC.Sky, modifier = Modifier.fillMaxWidth(), height = 50.dp, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            GameButton("Reset daily (wheel, login, missions)", onClick = {
                s.profile.update { it.copy(daily = it.daily.copy(claimedDay = -1, wheelDay = -1, missionsDay = -1, challengeDoneDay = -1)) }
            }, color = GC.Purple, modifier = Modifier.fillMaxWidth(), height = 50.dp, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            val fps = "DEV_FPS" in p.seenTips
            ToggleRow("Show FPS in game", fps) { v -> s.profile.update { it.copy(seenTips = if (v) it.seenTips + "DEV_FPS" else it.seenTips - "DEV_FPS") } }
            val bot = "DEV_AUTOPLAY" in p.seenTips
            ToggleRow("Autoplay bot (plays levels)", bot) { v -> s.profile.update { it.copy(seenTips = if (v) it.seenTips + "DEV_AUTOPLAY" else it.seenTips - "DEV_AUTOPLAY") } }
            ToggleRow("Replay onboarding next launch", !p.onboardingDone) { v -> s.profile.update { it.copy(onboardingDone = !v) } }
        }
        Card("Difficulty curve") {
            for (l in listOf(1, 10, 11, 50, 100, 500, 1000, 5000, 10000)) {
                val pr = Difficulty.params(l)
                Row(Modifier.fillMaxWidth()) {
                    Text("L$l", style = GT.body(13.sp), modifier = Modifier.width(64.dp))
                    Text("D=%.2f rings=${pr.ringCount} gap=${pr.gapWidth.toInt()}° hz=${(pr.hazardShare * 100).toInt()}%%".format(pr.d), style = GT.body(13.sp, GC.Muted))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { StarIcon(true, 18.dp); Text(" Stars total: ${p.stars.total}", style = GT.body(14.sp)) }
        Spacer(Modifier.height(24.dp))
    }
}
