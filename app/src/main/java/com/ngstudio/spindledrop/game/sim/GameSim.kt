package com.ngstudio.spindledrop.game.sim

import com.ngstudio.spindledrop.game.level.Cell
import com.ngstudio.spindledrop.game.level.Contact
import com.ngstudio.spindledrop.game.level.EndlessTower
import com.ngstudio.spindledrop.game.level.LevelSpec
import com.ngstudio.spindledrop.game.level.Physics
import com.ngstudio.spindledrop.game.level.PowerUpType
import com.ngstudio.spindledrop.game.level.RingKind
import com.ngstudio.spindledrop.game.level.RingSpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Where rings come from: a finite level or an endless stream. */
interface TowerSource {
    val gravityScale: Float
    /** Total rings, or -1 for endless. */
    val size: Int
    fun ring(index: Int): RingSpec?
    fun prepare(upTo: Int) {}
}

class LevelSource(val spec: LevelSpec) : TowerSource {
    override val gravityScale get() = spec.gravityScale
    override val size get() = spec.rings.size
    override fun ring(index: Int) = spec.rings.getOrNull(index)
}

class EndlessSource(seed: Long) : TowerSource {
    private val tower = EndlessTower(seed)
    override val gravityScale get() = tower.gravityScale
    override val size get() = -1
    override fun ring(index: Int): RingSpec? {
        if (index < 0) return null
        tower.ensure(index + 1)
        return tower.rings[index]
    }
    override fun prepare(upTo: Int) = tower.ensure(upTo)
}

enum class SimStatus { PLAYING, DEAD, WON }

sealed class SimEvent {
    data class Bounce(val ring: Int, val theta: Float, val onCrumble: Boolean) : SimEvent()
    data class RingPassed(val ring: Int, val combo: Int, val smashed: Boolean, val points: Int, val theta: Float) : SimEvent()
    data class BlazeStart(val ring: Int) : SimEvent()
    data class Coin(val ring: Int, val angle: Float, val value: Int) : SimEvent()
    data class PowerUp(val type: PowerUpType, val ring: Int) : SimEvent()
    data object ShieldPop : SimEvent()
    data class CrumbleCrack(val ring: Int, val start: Int, val length: Int, val broke: Boolean) : SimEvent()
    data class GateHit(val ring: Int, val hpLeft: Int, val maxHp: Int) : SimEvent()
    data class Vault(val ring: Int, val multiplier: Int, val coins: Int) : SimEvent()
    data class Boost(val ring: Int) : SimEvent()
    data class Death(val ring: Int, val theta: Float) : SimEvent()
    data class Win(val ring: Int) : SimEvent()
}

/**
 * The deterministic game rules. Runs on the GL thread with fixed 240 Hz sub-steps.
 * Uses the same contact rules as the fairness solver, plus edge grace.
 */
class GameSim(val source: TowerSource, val level: Int, val endless: Boolean = false) {
    val gravityScale = source.gravityScale
    private val g = Physics.gravity(gravityScale)
    private val vBounce = Physics.bounceSpeed(gravityScale)

    var status = SimStatus.PLAYING
        private set
    /** Simulation time (slowed by Slow-Mo). */
    var time = 0f
        private set
    /** Tower angle in degrees, driven by the player. */
    var alpha = 0f
    var ballY = Physics.BOUNCE_HEIGHT + Physics.BALL_RADIUS
        private set
    var ballVy = 0f
        private set
    /** Index of the ring the ball will touch next. */
    var nextRing = 0
        private set
    var combo = 0
        private set
    private var boostBlaze = false
    var drillLeft = 0
        private set
    var shieldCharges = 0
        private set
    var reviveShield = 0f
        private set
    var magnetTime = 0f
        private set
    var slowTime = 0f
        private set
    var score = 0
        private set
    var coins = 0
        private set
    var ringsPassed = 0
        private set
    var blazeTriggered = false
        private set
    var revives = 0
        private set
    var bestCombo = 0
        private set
    var ringsSmashed = 0
        private set
    /** Time since the last bounce (for squash/stretch). */
    var sinceBounce = 1f
        private set
    var lastContactRing = 0
        private set

    val blazeActive: Boolean get() = combo >= Physics.COMBO_FOR_BLAZE || boostBlaze || drillLeft > 0
    val shieldActive: Boolean get() = shieldCharges > 0 || reviveShield > 0f

    val events = ArrayList<SimEvent>(16)

    private val broken = HashSet<Int>()
    private val cellOverrides = HashMap<Int, ByteArray>()
    private val slidersOff = HashSet<Int>()
    private val crumbleHits = HashMap<Long, Int>()
    private val gateHp = HashMap<Int, Int>()
    private val collectedCoins = HashSet<Long>()
    private val collectedPowerUps = HashSet<Int>()

    fun isBroken(ring: Int) = ring in broken
    fun cellsFor(ring: RingSpec): ByteArray = cellOverrides[ring.index] ?: ring.cells
    fun hasOverride(ring: Int) = cellOverrides.containsKey(ring)
    fun slidersEnabled(ring: Int) = ring !in slidersOff
    fun gateHpLeft(ring: RingSpec) = gateHp[ring.index] ?: ring.gateHp
    fun coinCollected(ring: Int, k: Int) = collectedCoins.contains(ring.toLong() * 64 + k)
    fun powerUpCollected(ring: Int) = ring in collectedPowerUps

    fun ringRotation(ring: RingSpec): Float = ring.rotation(alpha, time)

    /** Number of hits on the crumble run starting at [start] (0, 1). */
    fun crumbleLevel(ring: Int, start: Int): Int = crumbleHits[ring.toLong() * 1000 + start] ?: 0

    /** Invulnerability used by the home-screen demo tower. */
    fun setDemoShield() { reviveShield = 1000f }

    /** Advances by [dt] real seconds. */
    fun update(dt: Float) {
        if (status != SimStatus.PLAYING) return
        if (endless) source.prepare(nextRing + 24)
        var remaining = min(dt, 0.1f)
        while (remaining > 1e-6f) {
            val h = min(remaining, STEP)
            step(h)
            remaining -= h
            if (status != SimStatus.PLAYING) break
        }
        magnetTime = max(0f, magnetTime - dt)
        slowTime = max(0f, slowTime - dt)
        reviveShield = max(0f, reviveShield - dt)
        sinceBounce += dt
    }

    private fun step(h: Float) {
        val dt = h * (if (slowTime > 0f) SLOW_FACTOR else 1f)
        time += dt
        val prevBottom = ballY - Physics.BALL_RADIUS
        ballVy -= g * dt
        if (ballVy < -Physics.TERMINAL_SPEED) ballVy = -Physics.TERMINAL_SPEED
        ballY += ballVy * dt
        val ring = source.ring(nextRing) ?: return
        val top = ring.y
        if (ballVy < 0f && ballY - Physics.BALL_RADIUS <= top && prevBottom > top) {
            contact(ring)
        }
    }

    private fun contact(ring: RingSpec) {
        lastContactRing = ring.index
        val theta = Physics.wrap360(Physics.BALL_ANGLE - ring.rotation(alpha, time))
        val blaze = blazeActive
        when (ring.kind) {
            RingKind.FINISH -> {
                bounce(ring, theta, false)
                status = SimStatus.WON
                events += SimEvent.Win(ring.index)
                return
            }
            RingKind.VAULT -> {
                val value = ring.vaultMultiplier * (6 + level / 25)
                coins += value
                events += SimEvent.Vault(ring.index, ring.vaultMultiplier, value)
                pass(ring, theta, smashed = true, countProgress = false)
                return
            }
            RingKind.GATE -> {
                if (blaze) {
                    consumeBlaze()
                    gateHp[ring.index] = 0
                    events += SimEvent.GateHit(ring.index, 0, ring.gateHp)
                    pass(ring, theta, smashed = true, countProgress = true)
                } else {
                    val hp = (gateHp[ring.index] ?: ring.gateHp) - 1
                    gateHp[ring.index] = hp
                    events += SimEvent.GateHit(ring.index, hp.coerceAtLeast(0), ring.gateHp)
                    if (hp <= 0) pass(ring, theta, smashed = true, countProgress = true)
                    else bounce(ring, theta, false)
                }
                return
            }
            RingKind.NORMAL -> Unit
        }
        val c = ring.contactWithGrace(theta, time, cellOverrides[ring.index], ring.index !in slidersOff)
        when {
            c == Contact.GAP -> pass(ring, theta, smashed = false, countProgress = true)
            blaze -> {
                consumeBlaze()
                pass(ring, theta, smashed = true, countProgress = true)
            }
            c == Contact.BOOST -> {
                events += SimEvent.Boost(ring.index)
                pass(ring, theta, smashed = true, countProgress = true)
                boostBlaze = true
            }
            c == Contact.SAFE -> bounce(ring, theta, false)
            c == Contact.CRUMBLE -> {
                bounce(ring, theta, true)
                crackCrumble(ring, theta)
            }
            c == Contact.HAZARD -> {
                if (shieldActive) {
                    if (reviveShield <= 0f) shieldCharges = max(0, shieldCharges - 1)
                    events += SimEvent.ShieldPop
                    bounce(ring, theta, false)
                } else {
                    die(ring, theta)
                }
            }
            else -> bounce(ring, theta, false)
        }
    }

    private fun consumeBlaze() {
        when {
            drillLeft > 0 -> drillLeft--
            boostBlaze -> boostBlaze = false
            else -> combo = 0
        }
    }

    private fun bounce(ring: RingSpec, theta: Float, onCrumble: Boolean) {
        ballY = ring.y + Physics.BALL_RADIUS
        ballVy = vBounce
        combo = 0
        boostBlaze = false
        sinceBounce = 0f
        events += SimEvent.Bounce(ring.index, theta, onCrumble)
    }

    private fun pass(ring: RingSpec, theta: Float, smashed: Boolean, countProgress: Boolean) {
        broken += ring.index
        if (countProgress) ringsPassed++
        if (smashed) ringsSmashed++
        if (!smashed) {
            combo++
            if (combo > bestCombo) bestCombo = combo
        }
        val mult = max(1, combo)
        val points = (1 + level / 40) * mult * (if (smashed) 2 else 1)
        score += points
        // Coins floating in this ring's gaps.
        for ((k, a) in ring.coins.withIndex()) {
            val d = abs(Physics.wrap360(a - theta + 180f) - 180f)
            if (d < COIN_REACH || magnetTime > 0f || smashed) {
                val key = ring.index.toLong() * 64 + k
                if (collectedCoins.add(key)) {
                    coins += COIN_VALUE
                    events += SimEvent.Coin(ring.index, a, COIN_VALUE)
                }
            }
        }
        val pu = ring.powerUp
        if (pu != null && ring.index !in collectedPowerUps) {
            val d = abs(Physics.wrap360(ring.powerUpAngle - theta + 180f) - 180f)
            if (d < POWERUP_REACH || magnetTime > 0f || smashed) {
                collectedPowerUps += ring.index
                activate(pu)
                events += SimEvent.PowerUp(pu, ring.index)
            }
        }
        events += SimEvent.RingPassed(ring.index, combo, smashed, points, theta)
        if (!smashed && combo == Physics.COMBO_FOR_BLAZE) {
            blazeTriggered = true
            events += SimEvent.BlazeStart(ring.index)
        }
        nextRing = ring.index + 1
    }

    private fun activate(type: PowerUpType) {
        when (type) {
            PowerUpType.SHIELD -> shieldCharges = 1
            PowerUpType.MAGNET -> magnetTime = 8f
            PowerUpType.SLOWMO -> slowTime = 5f
            PowerUpType.DRILL -> drillLeft = 5
        }
    }

    private fun crackCrumble(ring: RingSpec, theta: Float) {
        val cells = cellOverrides.getOrPut(ring.index) { ring.cells.copyOf() }
        val center = theta.toInt() % 360
        // Find the crumble run around the contact point (the grace rule may have mapped us onto it).
        var idx = -1
        for (o in 0..6) {
            if (cells[(center + o) % 360] == Cell.CRUMBLE) { idx = (center + o) % 360; break }
            if (cells[(center - o + 360) % 360] == Cell.CRUMBLE) { idx = (center - o + 360) % 360; break }
        }
        if (idx < 0) return
        var start = idx
        var guard = 0
        while (cells[(start - 1 + 360) % 360] == Cell.CRUMBLE && guard++ < 360) start = (start - 1 + 360) % 360
        var len = 0
        while (len < 360 && cells[(start + len) % 360] == Cell.CRUMBLE) len++
        val key = ring.index.toLong() * 1000 + start
        val hits = (crumbleHits[key] ?: 0) + 1
        crumbleHits[key] = hits
        val broke = hits >= 2
        if (broke) for (k in 0 until len) cells[(start + k) % 360] = Cell.GAP
        events += SimEvent.CrumbleCrack(ring.index, start, len, broke)
    }

    private fun die(ring: RingSpec, theta: Float) {
        ballY = ring.y + Physics.BALL_RADIUS
        ballVy = 0f
        status = SimStatus.DEAD
        events += SimEvent.Death(ring.index, theta)
    }

    /** Revive: 2 s shield, hazards removed from the current ring, ball bounces again. */
    fun revive() {
        if (status != SimStatus.DEAD) return
        revives++
        val ring = source.ring(nextRing) ?: return
        val cells = cellOverrides.getOrPut(ring.index) { ring.cells.copyOf() }
        for (i in 0 until 360) if (cells[i] == Cell.HAZARD || cells[i] == Cell.PULSE) cells[i] = Cell.NORMAL
        slidersOff += ring.index
        reviveShield = 2.0f
        combo = 0
        boostBlaze = false
        ballY = ring.y + Physics.BALL_RADIUS
        ballVy = vBounce
        sinceBounce = 0f
        status = SimStatus.PLAYING
    }

    /** Stars: 1 clear, 2 no revive, 3 no revive + Blaze triggered. */
    fun stars(): Int = when {
        status != SimStatus.WON -> 0
        revives > 0 -> 1
        blazeTriggered -> 3
        else -> 2
    }

    /** Fraction of the tower completed (for the HUD progress bar). */
    fun progress(progressRings: Int): Float =
        if (progressRings <= 0) 0f else (ringsPassed.toFloat() / progressRings).coerceIn(0f, 1f)

    companion object {
        const val STEP = 1f / 240f
        const val SLOW_FACTOR = 0.6f
        const val COIN_REACH = 22f
        const val POWERUP_REACH = 26f
        const val COIN_VALUE = 2
    }
}
