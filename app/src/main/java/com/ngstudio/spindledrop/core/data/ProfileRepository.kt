package com.ngstudio.spindledrop.core.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Local-only player save (no network). Writes are debounced and atomic. */
class ProfileRepository(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "profile.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<PlayerProfile> = _state
    private var saveJob: Job? = null

    val value: PlayerProfile get() = _state.value

    fun update(block: (PlayerProfile) -> PlayerProfile) {
        _state.update(block)
        scheduleSave()
    }

    private fun load(): PlayerProfile = try {
        if (file.baseFile.exists()) PlayerProfile.fromJson(String(file.readFully())) else PlayerProfile()
    } catch (e: Exception) {
        Log.w("Profile", "Failed to load profile, starting fresh", e)
        PlayerProfile()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(250)
            saveNow()
        }
    }

    fun saveNow() {
        val json = _state.value.toJson()
        synchronized(file) {
            val out = file.startWrite()
            try {
                out.write(json.toByteArray())
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                Log.w("Profile", "Save failed", e)
            }
        }
    }

    fun reset() {
        _state.value = PlayerProfile(onboardingDone = true)
        scope.launch { saveNow() }
    }
}
