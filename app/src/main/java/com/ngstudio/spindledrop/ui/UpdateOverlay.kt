package com.ngstudio.spindledrop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngstudio.spindledrop.core.update.UpdateUi
import com.ngstudio.spindledrop.ui.components.GameButton
import com.ngstudio.spindledrop.ui.components.GameDialog
import com.ngstudio.spindledrop.ui.components.GameProgressBar
import com.ngstudio.spindledrop.ui.components.StrokeText
import com.ngstudio.spindledrop.ui.theme.GC
import com.ngstudio.spindledrop.ui.theme.GT
import com.ngstudio.spindledrop.ui.theme.LocalServices

/** In-app update prompts: a themed "update available" dialog and download/ready banners. */
@Composable
fun UpdateOverlay() {
    val updater = LocalServices.current.updater
    val ui by updater.ui.collectAsState()

    GameDialog(ui is UpdateUi.Available, onDismiss = { updater.later() }, title = "Update available", titleColor = GC.Mint) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "A new version of Spindle Drop is ready on Google Play, with fresh content and fixes. Update now?",
                style = GT.body(16.sp), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GameButton("Later", onClick = { updater.later() }, color = Color(0xFF6B5A9A), modifier = Modifier.weight(1f), height = 52.dp, fontSize = 19.sp)
                GameButton("Update", onClick = { updater.acceptFlexible() }, color = GC.Mint, modifier = Modifier.weight(1f), height = 52.dp, fontSize = 19.sp)
            }
        }
    }

    GameDialog(ui is UpdateUi.Failed, onDismiss = { updater.dismissFailed() }, title = "Update failed", titleColor = GC.Red) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("The update couldn't be installed. You can try again from Google Play.", style = GT.body(16.sp), textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            GameButton("OK", onClick = { updater.dismissFailed() }, color = GC.Mint, modifier = Modifier.fillMaxWidth(0.6f), height = 50.dp, fontSize = 19.sp)
        }
    }

    // Bottom banners for background download + ready-to-install.
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) {
        val banner = ui is UpdateUi.Downloading || ui is UpdateUi.ReadyToInstall || ui is UpdateUi.Installing
        AnimatedVisibility(banner, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            Box(
                Modifier.padding(16.dp).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xFF3B1C93), Color(0xFF1B0A52))), RoundedCornerShape(22.dp))
                    .border(2.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(22.dp))
                    .padding(16.dp),
            ) {
                when (val u = ui) {
                    is UpdateUi.Downloading -> Column {
                        Text("Downloading update…", style = GT.body(15.sp))
                        Spacer(Modifier.height(8.dp))
                        GameProgressBar(u.progress, Modifier.fillMaxWidth(), color = GC.Mint, height = 14.dp)
                    }
                    is UpdateUi.Installing -> Text("Installing update…", style = GT.body(15.sp))
                    is UpdateUi.ReadyToInstall -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            StrokeText("Update ready", size = 18.sp)
                            Text("Restart to finish installing.", style = GT.body(13.sp, GC.Muted))
                        }
                        Spacer(Modifier.height(0.dp))
                        GameButton("Restart", onClick = { updater.completeUpdate() }, color = GC.Mint, height = 46.dp, fontSize = 17.sp)
                    }
                    else -> Unit
                }
            }
        }
    }
}
