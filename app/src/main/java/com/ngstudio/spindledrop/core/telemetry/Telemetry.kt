package com.ngstudio.spindledrop.core.telemetry

import android.content.Context
import android.os.Bundle
import androidx.core.os.bundleOf
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Thin wrapper over Firebase Analytics and Crashlytics so the rest of the app never
 * touches the Firebase SDK directly. Firebase itself auto-initialises from
 * `google-services.json` via its startup ContentProvider, so there is nothing to wire
 * up here beyond grabbing the singletons and toggling collection.
 *
 * All methods are safe to call off the main thread and never throw: the SDK calls are
 * fire-and-forget, and if Firebase failed to init (e.g. a bad config) we swallow it so
 * telemetry can never crash the game.
 */
class Telemetry(context: Context) {

    // Null if Firebase failed to start (bad config, a stripped component, …): the game keeps
    // running without telemetry instead of crashing on launch.
    private val analytics: FirebaseAnalytics? = runCatching { FirebaseAnalytics.getInstance(context) }.getOrNull()
    private val crashlytics: FirebaseCrashlytics? = runCatching { FirebaseCrashlytics.getInstance() }.getOrNull()

    /** Turn collection on/off. Call once at boot, and again if the user changes consent. */
    fun setCollectionEnabled(enabled: Boolean) = safe {
        analytics?.setAnalyticsCollectionEnabled(enabled)
        crashlytics?.isCrashlyticsCollectionEnabled = enabled
    }

    /** Record a bit of version context so crashes are easy to triage. */
    fun boot(versionName: String, versionCode: Int, debug: Boolean) = safe {
        crashlytics?.setCustomKey("version_name", versionName)
        crashlytics?.setCustomKey("version_code", versionCode)
        crashlytics?.setCustomKey("build_type", if (debug) "debug" else "release")
        analytics?.setUserProperty("app_version", versionName)
    }

    // ---- Analytics ---------------------------------------------------------

    fun logEvent(name: String, params: Bundle? = null) = safe { analytics?.logEvent(name, params) }

    /** A navigation to one of the app's screens. Mirrors GA4's screen_view. */
    fun screenView(screen: String) = safe {
        analytics?.logEvent(
            FirebaseAnalytics.Event.SCREEN_VIEW,
            bundleOf(
                FirebaseAnalytics.Param.SCREEN_NAME to screen,
                FirebaseAnalytics.Param.SCREEN_CLASS to screen,
            ),
        )
        // Leave a breadcrumb so any later crash shows where the player was.
        crashlytics?.log("screen: $screen")
        crashlytics?.setCustomKey("last_screen", screen)
    }

    /** A level/run has begun. */
    fun levelStart(mode: String, level: Int) = safe {
        crashlytics?.setCustomKey("mode", mode)
        crashlytics?.setCustomKey("level", level)
        logEvent("level_start", bundleOf("mode" to mode, "level" to level))
    }

    /** A level was cleared. */
    fun levelComplete(mode: String, level: Int, stars: Int, timeMs: Int, newRecord: Boolean) = safe {
        logEvent(
            FirebaseAnalytics.Event.LEVEL_END,
            bundleOf(
                "mode" to mode,
                FirebaseAnalytics.Param.LEVEL to level,
                "stars" to stars,
                "time_ms" to timeMs.toLong(),
                FirebaseAnalytics.Param.SUCCESS to 1,
                "new_record" to if (newRecord) 1 else 0,
            ),
        )
    }

    /** The ball was destroyed / the run ended in failure. */
    fun levelFail(mode: String, level: Int, ringsPassed: Int) = safe {
        logEvent(
            FirebaseAnalytics.Event.LEVEL_END,
            bundleOf(
                "mode" to mode,
                FirebaseAnalytics.Param.LEVEL to level,
                "rings_passed" to ringsPassed,
                FirebaseAnalytics.Param.SUCCESS to 0,
            ),
        )
    }

    // ---- Crashlytics -------------------------------------------------------

    /** Report a handled (non-fatal) exception. */
    fun recordException(t: Throwable) = safe { crashlytics?.recordException(t) }

    /** Add a breadcrumb to the next crash report. */
    fun log(message: String) = safe { crashlytics?.log(message) }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // Telemetry must never take the game down.
        }
    }
}
