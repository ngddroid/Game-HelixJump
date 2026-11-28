package com.ngstudio.spindledrop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.ngstudio.spindledrop.game.Sfx
import com.ngstudio.spindledrop.ui.components.GameButton
import com.ngstudio.spindledrop.ui.components.StrokeText
import com.ngstudio.spindledrop.ui.components.noRippleClick
import com.ngstudio.spindledrop.ui.theme.GC
import com.ngstudio.spindledrop.ui.theme.GT

/** Debug builds: renders the simulated rewarded-ad overlay. Release has an empty counterpart. */
@Composable
fun AdOverlay(services: AppServices) {
    (services.ads as? FakeAdGateway)?.let { TestAdOverlay(it) }
}

/** Debug-only simulated rewarded ad. Clearly labelled as a test, grants the reward after 3 seconds. */
@Composable
private fun TestAdOverlay(fake: FakeAdGateway) {
    val req by fake.request.collectAsState()
    AnimatedVisibility(req != null, enter = fadeIn(), exit = fadeOut()) {
        var remaining by remember(req) { mutableIntStateOf(3) }
        LaunchedEffect(req) {
            remaining = 3
            while (remaining > 0) { kotlinx.coroutines.delay(1000); remaining-- }
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
