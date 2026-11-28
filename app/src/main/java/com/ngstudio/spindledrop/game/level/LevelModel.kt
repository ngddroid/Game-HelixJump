package com.ngstudio.spindledrop.game.level

import kotlin.math.floor
import kotlin.math.sqrt

/** Shared physical constants. The simulation, renderer and fairness solver all use these. */
object Physics {
    const val RING_SPACING = 1.30f
    const val RING_THICKNESS = 0.26f
    const val RING_INNER = 0.56f
    const val RING_OUTER = 1.80f
    const val PILLAR_RADIUS = 0.50f
    const val BALL_RADIUS = 0.22f
    /** Distance of the ball centre from the tower axis. */
    const val BALL_ORBIT = 1.20f
    /** World angle (degrees) where the ball sits: +Z, facing the camera. */
    const val BALL_ANGLE = 90f
    const val BASE_GRAVITY = 24f
    const val BOUNCE_HEIGHT = 1.0f
    const val TERMINAL_SPEED = 12.5f
    /** Ball dies only if it is at least this many degrees inside a hazard. */
    const val HAZARD_GRACE = 4f
    const val COMBO_FOR_BLAZE = 3

    fun gravity(scale: Float) = BASE_GRAVITY * scale
    fun bounceSpeed(scale: Float) = sqrt(2f * gravity(scale) * BOUNCE_HEIGHT)
    fun bouncePeriod(scale: Float) = 2f * bounceSpeed(scale) / gravity(scale)

    /** Time and resulting speed for falling one ring spacing starting at downward speed [v0]. */
    fun fallOneRing(v0: Float, scale: Float): Pair<Float, Float> {
        val g = gravity(scale)
        val s = RING_SPACING
        val vt = TERMINAL_SPEED
        if (v0 >= vt) return Pair(s / vt, vt)
        val t1 = (vt - v0) / g
        val d1 = v0 * t1 + 0.5f * g * t1 * t1
        return if (d1 >= s) {
            val v = sqrt(v0 * v0 + 2f * g * s)
            Pair((v - v0) / g, v)
        } else {
            Pair(t1 + (s - d1) / vt, vt)
        }
    }

    fun wrap360(a: Float): Float {
        val r = a - 360f * floor(a / 360f)
        return if (r >= 360f) r - 360f else r
    }
}

/** Static cell codes stored in a ring's 360-cell array (1 cell = 1 degree). */
object Cell {
    const val GAP: Byte = 0
    const val NORMAL: Byte = 1
    const val HAZARD: Byte = 2
    const val CRUMBLE: Byte = 3
    const val BOOST: Byte = 4
    /** Gap that opens and closes on a rhythm (closed = safe panel). */
    const val SHUTTER: Byte = 5
    /** Hazard that is deadly only while lit. */
    const val PULSE: Byte = 6
    const val GATE: Byte = 7
    const val VAULT: Byte = 8
    const val FINISH: Byte = 9
}

/** Result of sampling a ring at an angle and time. */
enum class Contact { GAP, SAFE, HAZARD, BOOST, CRUMBLE, GATE, VAULT, FINISH }

enum class RingKind { NORMAL, GATE, VAULT, FINISH }

enum class PowerUpType { SHIELD, MAGNET, SLOWMO, DRILL }

enum class Mechanic(val title: String, val hint: String) {
    HAZARDS("Glowing tiles", "Don't land on the glowing striped tiles!"),
    BLAZE("Blaze chute", "Drop through 3 rings in a row to ignite Blaze and smash anything."),
    GATE("Guardian Gate", "Bounce to crack the gate - or Blaze straight through it!"),
    POWERUPS("Power-ups", "Grab power-ups floating in the gaps."),
    CRUMBLE("Crumble tiles", "Cracked tiles break after two bounces."),
    SPIN("Spinning rings", "Some rings spin on their own. Time your drop!"),
    SLIDER("Sliding hazards", "Watch out: some hazards slide along the ring."),
    PULSE("Pulse tiles", "Pulse tiles are only dangerous while lit."),
    SHUTTER("Shutter gates", "Shutter gaps open and close on a rhythm."),
    BOOST("Boost pads", "Hit a boost pad to rocket through two rings!"),
    MIRROR("Mirror rings", "Mirror rings turn the opposite way to your swipe.")
}

/** A hazard arc that glides back and forth over normal tiles. */
data class Slider(
    val center: Float,
    val length: Float,
    val amplitude: Float,
    val period: Float,
    val phase: Float,
) {
    fun startAt(t: Float): Float {
        val s = kotlin.math.sin((t / period + phase) * 2f * Math.PI.toFloat())
        return center + amplitude * s - length * 0.5f
    }
}

class RingSpec(
    // Reassigned once during generation so vertical stacking stays sequential after bonus rings
    // are interleaved into the tower (see LevelGenerator); treat as read-only everywhere else.
    var index: Int,
    val kind: RingKind,
    /** 360 cells, one per degree, in ring-local angles. */
    val cells: ByteArray,
    val sliders: List<Slider> = emptyList(),
    /** Base rotation offset (degrees). */
    val baseRotation: Float = 0f,
    /** Own spin speed (deg/s), added to the tower rotation. */
    val spinSpeed: Float = 0f,
    /** Mirror rings rotate opposite to the player's swipe. */
    val mirror: Boolean = false,
    /** Rhythm used by SHUTTER and PULSE cells (seconds, 0 = none). */
    val rhythmPeriod: Float = 0f,
    val rhythmPhase: Float = 0f,
    val gateHp: Int = 0,
    /** Ring-local angles of coins floating in gaps. */
    val coins: List<Float> = emptyList(),
    val powerUp: PowerUpType? = null,
    val powerUpAngle: Float = 0f,
    val vaultMultiplier: Int = 0,
) {
    val isDynamic: Boolean
        get() = spinSpeed != 0f || mirror || sliders.isNotEmpty() || rhythmPeriod > 0f

    val y: Float get() = -index * Physics.RING_SPACING

    /** Ring rotation in world degrees for tower angle [alpha] at sim time [t]. */
    fun rotation(alpha: Float, t: Float): Float =
        (if (mirror) -alpha else alpha) + baseRotation + spinSpeed * t

    /** Shutter cells are open (a gap) during the first 45% of each rhythm cycle. */
    fun shutterOpen(t: Float): Boolean {
        if (rhythmPeriod <= 0f) return false
        val p = frac(t / rhythmPeriod + rhythmPhase)
        return p < 0.45f
    }

    /** Pulse cells are lit (deadly) during the middle 50% of the cycle; before that they flicker as a warning. */
    fun pulseLit(t: Float): Boolean {
        if (rhythmPeriod <= 0f) return false
        val p = frac(t / rhythmPeriod + rhythmPhase)
        return p >= 0.40f && p < 0.90f
    }

    /** 0..1 warning ramp before a pulse lights up (for rendering). */
    fun pulseWarning(t: Float): Float {
        if (rhythmPeriod <= 0f) return 0f
        val p = frac(t / rhythmPeriod + rhythmPhase)
        return if (p in 0.25f..0.40f) (p - 0.25f) / 0.15f else 0f
    }

    fun sliderCovers(theta: Float, t: Float): Boolean {
        for (s in sliders) {
            val st = s.startAt(t)
            if (Physics.wrap360(theta - st) < s.length) return true
        }
        return false
    }

    /** Raw contact type for a single local angle (no grace/margins), using [cellsOverride] for crumble state. */
    fun contactAt(theta: Float, t: Float, cellsOverride: ByteArray? = null, useSliders: Boolean = true): Contact {
        val c = (cellsOverride ?: cells)[Physics.wrap360(theta).toInt() % 360]
        val base = when (c) {
            Cell.GAP -> Contact.GAP
            Cell.NORMAL -> Contact.SAFE
            Cell.HAZARD -> Contact.HAZARD
            Cell.CRUMBLE -> Contact.CRUMBLE
            Cell.BOOST -> Contact.BOOST
            Cell.SHUTTER -> if (shutterOpen(t)) Contact.GAP else Contact.SAFE
            Cell.PULSE -> if (pulseLit(t)) Contact.HAZARD else Contact.SAFE
            Cell.GATE -> Contact.GATE
            Cell.VAULT -> Contact.VAULT
            Cell.FINISH -> Contact.FINISH
            else -> Contact.SAFE
        }
        if (useSliders && (base == Contact.SAFE || base == Contact.CRUMBLE) && sliders.isNotEmpty() && sliderCovers(theta, t)) {
            return Contact.HAZARD
        }
        return base
    }

    /**
     * Contact used by the live game. A hazard only kills when the ball centre is at least
     * [Physics.HAZARD_GRACE] degrees inside it ("edge grace"); otherwise the safe neighbour wins.
     */
    fun contactWithGrace(theta: Float, t: Float, cellsOverride: ByteArray? = null, useSliders: Boolean = true): Contact {
        val c = contactAt(theta, t, cellsOverride, useSliders)
        if (c != Contact.HAZARD) return c
        val g = Physics.HAZARD_GRACE
        val left = contactAt(theta - g, t, cellsOverride, useSliders)
        val right = contactAt(theta + g, t, cellsOverride, useSliders)
        if (left != Contact.HAZARD && left != Contact.GAP) return left
        if (right != Contact.HAZARD && right != Contact.GAP) return right
        return Contact.HAZARD
    }

    companion object {
        fun frac(x: Float): Float = x - floor(x)
    }
}

class LevelSpec(
    val level: Int,
    val difficulty: Float,
    val worldIndex: Int,
    val rings: List<RingSpec>,
    val gravityScale: Float,
    val isBoss: Boolean,
    val introduced: Mechanic?,
    /** Number of rings that count for the progress bar (main rings + gate). */
    val progressRings: Int,
    val seed: Long,
) {
    val ringCount get() = rings.size
}
