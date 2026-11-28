package com.ngstudio.spindledrop

import android.app.Application
import android.content.Context
import com.ngstudio.spindledrop.core.ads.AdGateway
import com.ngstudio.spindledrop.core.ads.createAdGateway
import com.ngstudio.spindledrop.core.audio.AudioEngine
import com.ngstudio.spindledrop.core.data.ProfileRepository
import com.ngstudio.spindledrop.core.telemetry.Telemetry
import com.ngstudio.spindledrop.core.update.AppUpdater
import com.ngstudio.spindledrop.notifications.DailyReminders
import com.ngstudio.spindledrop.notifications.Notifications
import com.ngstudio.spindledrop.push.Push
import com.ngstudio.spindledrop.game.level.LevelGenerator
import com.ngstudio.spindledrop.game.render.BallThumbnails
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App-wide services (simple manual dependency container). */
class AppServices(app: Application) {
    val profile = ProfileRepository(app)
    val audio = AudioEngine(app)
    // Watch-ad features and ad-based instant unlocks are DEBUG ONLY. The simulated gateway lives in
    // the debug source set; release links the no-op NoAdGateway, so none of that code ships. The
    // factory is chosen per variant (src/debug vs src/release). Wire a real ad SDK here later.
    val ads: AdGateway = createAdGateway()
    /** Firebase Analytics + Crashlytics facade. Never throws. */
    val telemetry = Telemetry(app)
    /** Google Play in-app updates. MainActivity binds its result launcher. */
    val updater = AppUpdater(app, telemetry)
    private val _ready = MutableStateFlow(false)
    /** True once sounds are loaded and the first level is generated. */
    val ready: StateFlow<Boolean> = _ready

    init {
        telemetry.setCollectionEnabled(true)
        telemetry.boot(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.DEBUG)
        // Notifications: channels, FCM topic subscription, and the daily 9 AM / 7 PM reminders.
        Notifications.createChannels(app)
        Push.init(debug = BuildConfig.DEBUG)
        DailyReminders.scheduleAll(app)
        Thread({
            audio.load()
            LevelGenerator.generate(profile.value.currentLevel)
            _ready.value = true
        }, "Warmup").start()
        BallThumbnails.start(app)
        val s = profile.value.settings
        audio.configure(s.music, s.musicVolume, s.sfx, s.sfxVolume, s.vibration)
    }
}

class SpindleApp : Application() {
    lateinit var services: AppServices
        private set

    override fun onCreate() {
        super.onCreate()
        services = AppServices(this)
    }
}

val Context.services: AppServices get() = (applicationContext as SpindleApp).services
