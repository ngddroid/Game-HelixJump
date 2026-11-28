package com.ngstudio.spindledrop.core.ads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AdRequest(val placement: String, val onResult: (Boolean) -> Unit)

/**
 * Debug builds only: shows a clearly-labelled simulated ad (see TestAdOverlay) and grants the reward.
 * This class exists only in the debug source set, so it is never compiled into a release build.
 */
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

/** Variant factory (debug): watch-ad features are available via the simulated gateway. */
fun createAdGateway(): AdGateway = FakeAdGateway()
