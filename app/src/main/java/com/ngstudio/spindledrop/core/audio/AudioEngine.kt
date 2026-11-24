package com.ngstudio.spindledrop.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.SystemClock
import com.ngstudio.spindledrop.R
import com.ngstudio.spindledrop.game.Feedback
import com.ngstudio.spindledrop.game.Haptic
import com.ngstudio.spindledrop.game.Sfx
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

enum class Music(val res: Int) {
    MENU(R.raw.music_menu),
    PLAY_A(R.raw.music_play_a),
    PLAY_B(R.raw.music_play_b),
    BOSS(R.raw.music_boss),
    ENDLESS(R.raw.music_endless);

    companion object {
        fun forWorldMusic(name: String) = when (name) { "music_play_b" -> PLAY_B; else -> PLAY_A }
    }
}

/** Sound effects (SoundPool, low latency), music with crossfades, and haptics. Thread-safe. */
class AudioEngine(private val context: Context) : Feedback {
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val pool = SoundPool.Builder().setMaxStreams(14).setAudioAttributes(attrs).build()
    private val ids = ConcurrentHashMap<Sfx, IntArray>()
    private val lastPlayed = ConcurrentHashMap<Sfx, Long>()
    private val haptics = Haptics(context)
    private val main = Handler(Looper.getMainLooper())

    @Volatile var sfxEnabled = true
    @Volatile var sfxVolume = 1f
    @Volatile var hapticsEnabled = true
    @Volatile private var musicEnabled = true
    @Volatile private var musicVolume = 0.7f

    private fun res(s: Sfx): IntArray = when (s) {
        Sfx.BOUNCE -> intArrayOf(R.raw.sfx_bounce_1, R.raw.sfx_bounce_2, R.raw.sfx_bounce_3, R.raw.sfx_bounce_4)
        Sfx.RING_BREAK -> intArrayOf(R.raw.sfx_ring_break)
        Sfx.COMBO_NOTE -> intArrayOf(R.raw.sfx_combo_note)
        Sfx.BLAZE_IGNITE -> intArrayOf(R.raw.sfx_blaze_ignite)
        Sfx.BLAZE_SMASH -> intArrayOf(R.raw.sfx_blaze_smash)
        Sfx.DEATH -> intArrayOf(R.raw.sfx_death)
        Sfx.SHIELD_POP -> intArrayOf(R.raw.sfx_shield_pop)
        Sfx.POWERUP -> intArrayOf(R.raw.sfx_powerup)
        Sfx.COIN -> intArrayOf(R.raw.sfx_coin)
        Sfx.GEM -> intArrayOf(R.raw.sfx_gem)
        Sfx.GATE_HIT -> intArrayOf(R.raw.sfx_gate_hit)
        Sfx.GATE_BREAK -> intArrayOf(R.raw.sfx_gate_break)
        Sfx.VAULT_BREAK -> intArrayOf(R.raw.sfx_vault_break)
        Sfx.CRUMBLE -> intArrayOf(R.raw.sfx_crumble)
        Sfx.BOOST -> intArrayOf(R.raw.sfx_boost)
        Sfx.LEVEL_COMPLETE -> intArrayOf(R.raw.sfx_level_complete)
        Sfx.STAR -> intArrayOf(R.raw.sfx_star)
        Sfx.FAIL -> intArrayOf(R.raw.sfx_fail)
        Sfx.UI_TAP -> intArrayOf(R.raw.sfx_ui_tap)
        Sfx.UI_BACK -> intArrayOf(R.raw.sfx_ui_back)
        Sfx.UI_TOGGLE -> intArrayOf(R.raw.sfx_ui_toggle)
        Sfx.UI_OPEN -> intArrayOf(R.raw.sfx_ui_open)
        Sfx.SWOOSH -> intArrayOf(R.raw.sfx_swoosh)
        Sfx.PURCHASE -> intArrayOf(R.raw.sfx_purchase)
        Sfx.ERROR -> intArrayOf(R.raw.sfx_error)
        Sfx.REWARD -> intArrayOf(R.raw.sfx_reward)
        Sfx.CHEST_SHAKE -> intArrayOf(R.raw.sfx_chest_shake)
        Sfx.CHEST_OPEN -> intArrayOf(R.raw.sfx_chest_open)
        Sfx.WHEEL_TICK -> intArrayOf(R.raw.sfx_wheel_tick)
        Sfx.WHEEL_WIN -> intArrayOf(R.raw.sfx_wheel_win)
        Sfx.UNLOCK -> intArrayOf(R.raw.sfx_unlock)
        Sfx.COUNTDOWN_TICK -> intArrayOf(R.raw.sfx_countdown_tick)
    }

    /** Loads every effect. Call from a background thread. */
    fun load() {
        for (s in Sfx.entries) ids[s] = res(s).map { pool.load(context, it, 1) }.toIntArray()
    }

    override fun sfx(s: Sfx, rate: Float, volume: Float) {
        if (!sfxEnabled) return
        val arr = ids[s] ?: return
        val now = SystemClock.uptimeMillis()
        val minGap = when (s) { Sfx.WHEEL_TICK -> 25L; Sfx.COIN, Sfx.BOUNCE -> 35L; else -> 20L }
        val last = lastPlayed[s] ?: 0L
        if (now - last < minGap) return
        lastPlayed[s] = now
        val v = (volume * sfxVolume).coerceIn(0f, 1f)
        pool.play(arr[if (arr.size == 1) 0 else Random.nextInt(arr.size)], v, v, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    override fun haptic(h: Haptic) {
        if (hapticsEnabled) haptics.play(h)
    }

    // ------------------------------------------------------------------ music
    private var current: MediaPlayer? = null
    private var currentTrack: Music? = null
    private var wanted: Music? = null
    private var paused = false

    fun configure(music: Boolean, musicVol: Float, sfx: Boolean, sfxVol: Float, vibration: Boolean) {
        sfxEnabled = sfx; sfxVolume = sfxVol; hapticsEnabled = vibration
        main.post {
            musicEnabled = music; musicVolume = musicVol
            if (!music) stopMusicNow() else { current?.setVolume(musicVol, musicVol); wanted?.let { if (current == null) startTrack(it) } }
        }
    }

    fun playMusic(track: Music) = main.post {
        wanted = track
        if (!musicEnabled || paused) return@post
        if (currentTrack == track && current != null) return@post
        startTrack(track)
    }

    private fun startTrack(track: Music) {
        val old = current
        val mp = try {
            MediaPlayer.create(context, track.res)?.apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                isLooping = true
                setVolume(0f, 0f)
                start()
            }
        } catch (e: Exception) { null } ?: return
        current = mp
        currentTrack = track
        fade(mp, 0f, musicVolume, 700) {}
        if (old != null) fade(old, musicVolume, 0f, 500) { runCatching { old.stop(); old.release() } }
    }

    private fun fade(mp: MediaPlayer, from: Float, to: Float, ms: Long, done: () -> Unit) {
        val start = SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                val t = ((SystemClock.uptimeMillis() - start).toFloat() / ms).coerceIn(0f, 1f)
                val v = from + (to - from) * t
                runCatching { mp.setVolume(v, v) }
                if (t < 1f) main.postDelayed(this, 30) else done()
            }
        }
        main.post(r)
    }

    private fun stopMusicNow() {
        current?.let { runCatching { it.stop(); it.release() } }
        current = null
        currentTrack = null
    }

    fun onAppPause() = main.post {
        paused = true
        current?.let { runCatching { it.pause() } }
    }

    fun onAppResume() = main.post {
        paused = false
        if (!musicEnabled) return@post
        val c = current
        if (c != null) runCatching { c.start() } else wanted?.let { startTrack(it) }
    }
}

class Haptics(context: Context) {
    private val vib: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun play(h: Haptic) {
        val v = vib ?: return
        if (!v.hasVibrator()) return
        try {
            val effect = when (h) {
                Haptic.TICK -> if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK) else VibrationEffect.createOneShot(6, 50)
                Haptic.LIGHT -> if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK) else VibrationEffect.createOneShot(10, 90)
                Haptic.MEDIUM -> VibrationEffect.createOneShot(22, 160)
                Haptic.HEAVY -> if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK) else VibrationEffect.createOneShot(35, 255)
                Haptic.SUCCESS -> VibrationEffect.createWaveform(longArrayOf(0, 30, 60, 40, 60, 70), intArrayOf(0, 180, 0, 200, 0, 255), -1)
                Haptic.FAIL -> VibrationEffect.createWaveform(longArrayOf(0, 60, 40, 90), intArrayOf(0, 255, 0, 160), -1)
            }
            v.vibrate(effect)
        } catch (_: Exception) {
        }
    }
}
