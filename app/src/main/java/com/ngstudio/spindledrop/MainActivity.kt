package com.ngstudio.spindledrop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.ngstudio.spindledrop.notifications.Notifications
import com.ngstudio.spindledrop.ui.SpindleApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Play's in-app update flow reports its result here. */
    private val updateLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            services.updater.onFlowResult(it.resultCode)
        }

    /** Android 13+ notification permission prompt. We never block the game on the answer. */
    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var firstUpdateCheck = true

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()
        services.updater.bind(updateLauncher)
        if (savedInstanceState == null) logNotificationOpen(intent)
        requestNotificationPermission()
        setContent { SpindleApp(services) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        logNotificationOpen(intent)
    }

    /** Taps on our own reminders/pushes carry a source tag; FCM console campaigns are tracked by Firebase itself. */
    private fun logNotificationOpen(intent: Intent?) {
        val source = intent?.getStringExtra(Notifications.EXTRA_SOURCE) ?: return
        intent.removeExtra(Notifications.EXTRA_SOURCE)
        services.telemetry.logEvent("notification_tap", bundleOf("source" to source))
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        Notifications.appInForeground = true
        services.audio.onAppResume()
        // Ask Play about updates (prompts once per launch; later resumes just resume work in progress).
        val first = firstUpdateCheck
        firstUpdateCheck = false
        lifecycleScope.launch { services.updater.check(firstOfLaunch = first) }
    }

    override fun onPause() {
        Notifications.appInForeground = false
        services.audio.onAppPause()
        services.profile.saveNow()
        super.onPause()
    }

    override fun onDestroy() {
        services.updater.bind(null)
        super.onDestroy()
    }
}
