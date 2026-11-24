package com.ngstudio.spindledrop.core.ads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single integration point for rewarded ads. Real ads (e.g. AdMob) plug in here later.
 * Rules we follow: rewarded only, always started by the player, never during gameplay.
 */
interface AdGateway {
    val isAvailable: Boolean
    fun showRewarded(placement: String, onResult: (rewarded: Boolean) -> Unit)
}

data class AdRequest(val placement: String, val onResult: (Boolean) -> Unit)

/** Debug builds: shows a clearly-labelled simulated ad (see TestAdOverlay) and grants the reward. */
class FakeAdGateway : AdGateway {
    private val _request = MutableStateFlow<AdRequest?>(null)
    val request: StateFlow<AdRequest?> = _request
    override val isAvailable = true

    override fun showRewarded(placement: String, onResult: (Boolean) -> Unit) {
        _request.value = AdRequest(placement, onResult)
    }

    fun finish(rewarded: Boolean) {
        val r = _request.value ?: return
        _request.value = null
        r.onResult(rewarded)
    }
}

/** Release builds until real ads are integrated: no ads are shown and ad buttons are hidden. */
class NoAdGateway : AdGateway {
    override val isAvailable = false
    override fun showRewarded(placement: String, onResult: (Boolean) -> Unit) = onResult(false)
}
