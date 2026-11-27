package com.ngstudio.spindledrop

import android.app.Application
import android.content.Context
import com.ngstudio.spindledrop.core.ads.AdGateway
import com.ngstudio.spindledrop.core.ads.FakeAdGateway
import com.ngstudio.spindledrop.core.ads.NoAdGateway
import com.ngstudio.spindledrop.core.audio.AudioEngine
import com.ngstudio.spindledrop.core.data.ProfileRepository
import com.ngstudio.spindledrop.core.telemetry.Telemetry
import com.ngstudio.spindledrop.game.level.LevelGenerator
import com.ngstudio.spindledrop.game.render.BallThumbnails
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App-wide services (simple manual dependency container). */
class AppServices(app: Application) {
    val profile = ProfileRepository(app)
    val audio = AudioEngine(app)
    val ads: AdGateway = if (BuildConfig.DEBUG) FakeAdGateway() else NoAdGateway()
    /** Firebase Analytics + Crashlytics facade. Never throws. */
    val telemetry = Telemetry(app)
    private val _ready = MutableStateFlow(false)
    /** True once sounds are loaded and the first level is generated. */
    val ready: StateFlow<Boolean> = _ready

    init {
        telemetry.setCollectionEnabled(true)
        telemetry.boot(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.DEBUG)
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
