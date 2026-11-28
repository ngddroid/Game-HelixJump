package com.ngstudio.spindledrop.core.ads

/**
 * Single integration point for rewarded ads. Real ads (e.g. AdMob) plug in here later.
 * Rules we follow: rewarded only, always started by the player, never during gameplay.
 *
 * The concrete gateway is chosen per build variant by `createAdGateway()`:
 * - debug  → FakeAdGateway (simulated ad, grants the reward) — lives in src/debug only.
 * - release → NoAdGateway (no ads; every "Watch Ad" button and ad-unlock is hidden).
 *
 * Because the simulated gateway and its overlay live in the debug source set, none of the
 * watch-ad / auto-unlock code is compiled into release builds at all.
 */
interface AdGateway {
    val isAvailable: Boolean
    fun showRewarded(placement: String, onResult: (rewarded: Boolean) -> Unit)
}

/** Release builds until real ads are integrated: no ads are shown and ad buttons are hidden. */
class NoAdGateway : AdGateway {
    override val isAvailable = false
    override fun showRewarded(placement: String, onResult: (Boolean) -> Unit) = onResult(false)
}
