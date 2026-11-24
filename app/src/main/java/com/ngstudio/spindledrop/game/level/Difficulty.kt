package com.ngstudio.spindledrop.game.level

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The difficulty script for all 10,000 levels.
 *
 * Levels 1..10 are the Warm-up band (hand-tuned, guaranteed to be winnable).
 * From level 11 the difficulty index D rises strictly every single level up to 10,000:
 *   D = 0.12 + 0.70 * (1 - e^(-(L-10)/380))^0.8 + 0.18 * log10(L/10) / 3
 */
object Difficulty {
    const val MAX_LEVEL = 10_000
    const val WORLD_COUNT = 12
    const val LEVELS_PER_CHAPTER = 10

    /** Level where each mechanic first appears (that level introduces it gently). */
    const val UNLOCK_HAZARDS = 3
    const val UNLOCK_CHUTE = 7
    const val UNLOCK_GATE = 10
    const val UNLOCK_POWERUPS = 12
    const val UNLOCK_CRUMBLE = 15
    const val UNLOCK_SPIN = 21
    const val UNLOCK_SLIDER = 31
    const val UNLOCK_PULSE = 41
    const val UNLOCK_SHUTTER = 51
    const val UNLOCK_BOOST = 61
    const val UNLOCK_MIRROR = 81

    fun d(level: Int): Float {
        val l = level.coerceIn(1, MAX_LEVEL)
        if (l <= 10) return 0.02f + 0.08f * (l - 1) / 9f
        val x = (l - 10).toDouble()
        val main = 0.70 * (1.0 - exp(-x / 380.0)).pow(0.8)
        val mastery = 0.18 * log10(l / 10.0) / 3.0
        return (0.12 + main + mastery).toFloat()
    }

    fun isBoss(level: Int) = level % LEVELS_PER_CHAPTER == 0
    fun chapter(level: Int) = (level - 1) / LEVELS_PER_CHAPTER
    fun worldIndex(level: Int) = chapter(level) % WORLD_COUNT
    /** Remix cycle: 0 for the first 120 levels, 1 for the next 120, and so on. */
    fun remixCycle(level: Int) = chapter(level) / WORLD_COUNT
    /** Mastery tier: 0 below level 1001, then 1..9. */
    fun masteryTier(level: Int) = ((level - 1) / 1000).coerceIn(0, 9)

    fun introducedAt(level: Int): Mechanic? = when (level) {
        UNLOCK_HAZARDS -> Mechanic.HAZARDS
        UNLOCK_CHUTE -> Mechanic.BLAZE
        UNLOCK_GATE -> Mechanic.GATE
        UNLOCK_POWERUPS -> Mechanic.POWERUPS
        UNLOCK_CRUMBLE -> Mechanic.CRUMBLE
        UNLOCK_SPIN -> Mechanic.SPIN
        UNLOCK_SLIDER -> Mechanic.SLIDER
        UNLOCK_PULSE -> Mechanic.PULSE
        UNLOCK_SHUTTER -> Mechanic.SHUTTER
        UNLOCK_BOOST -> Mechanic.BOOST
        UNLOCK_MIRROR -> Mechanic.MIRROR
        else -> null
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

    /** Probability ramp for a mechanic that unlocks at [unlock]. */
    private fun ramp(level: Int, unlock: Int, base: Float, max: Float, scale: Float, tierBonus: Float): Float {
        if (level < unlock) return 0f
        val p = base + (d(level) - d(unlock)) * scale + tierBonus
        return p.coerceIn(0f, max)
    }

    fun params(level: Int): LevelParams {
        val l = level.coerceIn(1, MAX_LEVEL)
        val dd = d(l)
        val tier = masteryTier(l)
        val tierBonus = tier * 0.015f
        val speedMul = 1f + tier * 0.05f
        val warm = l <= 10
        val ringCount = if (warm) 10 + l else (16 + 30 * dd.toDouble().pow(0.9)).roundToInt()
        return LevelParams(
            level = l,
            d = dd,
            warmup = warm,
            ringCount = ringCount,
            gapWidth = lerp(110f, 36f, dd),
            hazardShare = if (l < UNLOCK_HAZARDS) 0f else lerp(0.08f, 0.52f, dd),
            hazardPieces = if (l < UNLOCK_HAZARDS) 0 else min(4, 1 + (dd * 3.6f).toInt()),
            maxShift = lerp(45f, 190f, dd),
            trapChance = if (dd < 0.2f) 0f else lerp(0f, 0.55f, (dd - 0.2f) / 0.8f),
            twinGapChance = if (warm) (if (l >= 5) 0.15f else 0f) else lerp(0.18f, 0.08f, dd),
            crumbleChance = ramp(l, UNLOCK_CRUMBLE, 0.18f, 0.30f, 0.4f, tierBonus),
            spinChance = ramp(l, UNLOCK_SPIN, 0.12f, 0.38f, 0.55f, tierBonus),
            spinSpeed = lerp(14f, 52f, dd) * speedMul,
            sliderChance = ramp(l, UNLOCK_SLIDER, 0.12f, 0.30f, 0.45f, tierBonus),
            sliderPeriod = lerp(3.4f, 1.9f, dd) / speedMul,
            pulseChance = ramp(l, UNLOCK_PULSE, 0.12f, 0.26f, 0.40f, tierBonus),
            shutterChance = ramp(l, UNLOCK_SHUTTER, 0.10f, 0.22f, 0.35f, tierBonus),
            rhythmPeriod = lerp(2.8f, 1.7f, dd) / speedMul,
            boostChance = ramp(l, UNLOCK_BOOST, 0.10f, 0.14f, 0.10f, 0f),
            mirrorChance = ramp(l, UNLOCK_MIRROR, 0.06f, 0.16f, 0.25f, tierBonus),
            gravityScale = lerp(1.0f, 1.22f, dd),
            solverOmega = lerp(150f, 420f, dd),
            solverSafeMargin = lerp(13f, 6f, dd),
            solverGapMargin = lerp(10f, 5f, dd),
            coinRate = 0.30f,
            powerUpChance = if (l < UNLOCK_POWERUPS) 0f else 0.55f,
            gateHp = if (l == UNLOCK_GATE) 3 else (3 + (dd * 4f).toInt()),
            vaultRings = if (l < 5) 2 else 3,
            masteryTier = tier,
        )
    }

    /** Endless mode: difficulty grows with depth (rings passed). */
    fun endlessParams(depth: Int): LevelParams {
        val equivalentLevel = (15 + depth / 3).coerceAtMost(MAX_LEVEL)
        return params(equivalentLevel).copy(ringCount = Int.MAX_VALUE)
    }

    /** Smooth helper used by the UI difficulty meter (0..5 flames). */
    fun flames(level: Int): Int = (1 + (d(level) * 4.999f)).toInt().coerceIn(1, 5)

    @Suppress("unused")
    private fun safeLn(x: Double) = if (x <= 0) 0.0 else ln(x)
}

data class LevelParams(
    val level: Int,
    val d: Float,
    val warmup: Boolean,
    val ringCount: Int,
    val gapWidth: Float,
    val hazardShare: Float,
    val hazardPieces: Int,
    val maxShift: Float,
    val trapChance: Float,
    val twinGapChance: Float,
    val crumbleChance: Float,
    val spinChance: Float,
    val spinSpeed: Float,
    val sliderChance: Float,
    val sliderPeriod: Float,
    val pulseChance: Float,
    val shutterChance: Float,
    val rhythmPeriod: Float,
    val boostChance: Float,
    val mirrorChance: Float,
    val gravityScale: Float,
    val solverOmega: Float,
    val solverSafeMargin: Float,
    val solverGapMargin: Float,
    val coinRate: Float,
    val powerUpChance: Float,
    val gateHp: Int,
    val vaultRings: Int,
    val masteryTier: Int,
)
