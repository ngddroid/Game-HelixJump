package com.ngstudio.spindledrop.game.level

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Ring patterns that make towers feel designed rather than random. */
enum class Pattern { STAIRCASE, ZIGZAG, RANDOM, CHUTE, BREATHER, SANDWICH, PINWHEEL }

/**
 * Builds a tower ring by ring. Every ring is checked by the [FairnessSolver] as it is created;
 * if it cannot be passed fairly it is regenerated, and as a last resort replaced by a safe ring.
 * Works for finite levels and for endless streaming.
 */
class TowerBuilder(
    private val seed: Long,
    private val baseParams: LevelParams,
    private val paramsForRing: (Int) -> LevelParams = { baseParams },
    private val level: Int = baseParams.level,
    private val introduced: Mechanic? = null,
) {
    private val solver = FairnessSolver(
        omega = baseParams.solverOmega,
        safeMargin = baseParams.solverSafeMargin,
        gapMargin = baseParams.solverGapMargin,
        gravityScale = baseParams.gravityScale,
    )
    private var entries: List<FairnessSolver.Entry> = solver.startEntries()
    private var prevGaps: List<Pair<Float, Float>> = emptyList() // (centre, width) in ring-local degrees
    private var prevGapCenter = 0f
    private var pattern = Pattern.STAIRCASE
    private var patternLeft = 0
    private var stepSign = 1
    private var stepSize = 40f
    var powerUpsPlaced = 0
        private set
    var fallbackRings = 0
        private set
    var regeneratedRings = 0
        private set
    private var forcedChuteAt = -1

    fun forceChuteEndingAt(ringIndex: Int) {
        forcedChuteAt = ringIndex
    }

    /** Builds main ring [index]. */
    fun buildRing(index: Int): RingSpec {
        val p = paramsForRing(index)
        choosePatternIfNeeded(index, p)
        for (attempt in 0 until 12) {
            val rng = Rng(Rng.mix(seed, index.toLong(), attempt.toLong()))
            val ring = composeRing(index, p, rng, attempt)
            val next = solver.pass(ring, entries)
            if (next.isNotEmpty()) {
                if (attempt > 0) regeneratedRings++
                commit(ring, next)
                return ring
            }
        }
        // Fallback: a guaranteed-safe ring placed where the player can be.
        val ring = safeRing(index)
        val next = solver.pass(ring, entries)
        fallbackRings++
        commit(ring, if (next.isNotEmpty()) next else entries)
        return ring
    }

    fun buildSpecial(index: Int, kind: RingKind, gateHp: Int = 0, vaultMultiplier: Int = 0): RingSpec {
        val code = when (kind) {
            RingKind.GATE -> Cell.GATE
            RingKind.VAULT -> Cell.VAULT
            RingKind.FINISH -> Cell.FINISH
            RingKind.NORMAL -> Cell.NORMAL
        }
        val ring = RingSpec(index, kind, ByteArray(360) { code }, gateHp = gateHp, vaultMultiplier = vaultMultiplier)
        val next = solver.pass(ring, entries)
        commit(ring, if (next.isNotEmpty()) next else entries)
        return ring
    }

    private fun commit(ring: RingSpec, next: List<FairnessSolver.Entry>) {
        entries = next
        if (ring.kind == RingKind.NORMAL) {
            prevGaps = gapsOf(ring.cells).map { (s, w) -> Pair(Physics.wrap360(s + w / 2f + ring.baseRotation), w) }
            if (prevGaps.isNotEmpty()) prevGapCenter = prevGaps.first().first
        }
    }

    private fun choosePatternIfNeeded(index: Int, p: LevelParams) {
        if (forcedChuteAt >= 0 && index in (forcedChuteAt - 2)..forcedChuteAt) {
            pattern = Pattern.CHUTE
            patternLeft = 0
            return
        }
        if (patternLeft > 0) {
            patternLeft--
            return
        }
        val rng = Rng(Rng.mix(seed, 777L, index.toLong()))
        val d = p.d
        val hazards = p.hazardShare > 0f
        val options = mutableListOf(
            Pattern.STAIRCASE to 1.0f,
            Pattern.ZIGZAG to 0.8f,
            Pattern.RANDOM to (0.5f + d),
            Pattern.BREATHER to (0.15f + 0.4f * (1f - d)),
        )
        if (level >= Difficulty.UNLOCK_CHUTE || !p.warmup) options += Pattern.CHUTE to 0.30f
        if (hazards && !p.warmup) {
            options += Pattern.SANDWICH to (0.25f + d * 0.6f)
            options += Pattern.PINWHEEL to (0.20f + d * 0.6f)
        }
        pattern = rng.pickWeighted(options)
        patternLeft = when (pattern) {
            Pattern.CHUTE -> 2
            Pattern.BREATHER -> rng.rangeInt(1, 2)
            else -> rng.rangeInt(3, 7)
        }
        stepSign = rng.sign()
        stepSize = rng.range(0.35f, 0.9f) * p.maxShift
    }

    private fun composeRing(index: Int, p: LevelParams, rng: Rng, attempt: Int): RingSpec {
        val cells = ByteArray(360) { Cell.NORMAL }
        val easeFactor = 1f + attempt * 0.06f // later attempts are a little friendlier

        // ---- 1. Main gap ----
        var gapW = p.gapWidth * rng.range(0.88f, 1.12f) * easeFactor
        if (pattern == Pattern.BREATHER) gapW *= 1.15f
        if (pattern == Pattern.CHUTE) gapW = max(gapW, min(115f, p.gapWidth * 1.15f))
        gapW = gapW.coerceIn(30f, 125f)

        val shift: Float = when {
            index == 0 -> rng.sign() * rng.range(70f, 130f) // ring 0: start away from the gap
            pattern == Pattern.CHUTE -> rng.range(-4f, 4f)
            pattern == Pattern.STAIRCASE -> stepSign * stepSize * rng.range(0.85f, 1.15f)
            pattern == Pattern.ZIGZAG -> { stepSign = -stepSign; stepSign * stepSize * rng.range(0.8f, 1.2f) }
            pattern == Pattern.BREATHER -> rng.range(-0.3f, 0.3f) * p.maxShift
            else -> rng.range(-1f, 1f) * p.maxShift
        }
        val gapC = Physics.wrap360((if (index == 0) Physics.BALL_ANGLE else prevGapCenter) + shift)
        fill(cells, gapC - gapW / 2f, gapW, Cell.GAP)
        val gaps = mutableListOf(Pair(gapC, gapW))

        // ---- 2. Optional twin gap ----
        if (index > 0 && pattern != Pattern.CHUTE && rng.chance(p.twinGapChance)) {
            val c2 = Physics.wrap360(gapC + 180f + rng.range(-35f, 35f))
            val w2 = gapW * rng.range(0.7f, 0.9f)
            fill(cells, c2 - w2 / 2f, w2, Cell.GAP)
            gaps += Pair(c2, w2)
        }

        // Keep the starting point safe on the first ring.
        if (index == 0) fill(cells, Physics.BALL_ANGLE - 22f, 44f, Cell.NORMAL)

        // ---- 3. Hazards ----
        val protect = if (p.warmup) 38f else lerp(30f, 12f, p.d)
        val trap = !p.warmup && index > 0 && rng.chance(p.trapChance)
        val protectedZones = if (trap || index == 0) emptyList() else prevGaps.map { (c, w) -> Pair(c - w / 2f - protect, w + 2 * protect) }
        val startZone = if (index == 0) listOf(Pair(Physics.BALL_ANGLE - 30f, 60f)) else emptyList()

        val hazardsAllowed = p.hazardShare > 0f && pattern != Pattern.BREATHER &&
            !(pattern == Pattern.CHUTE && p.warmup) && !(p.warmup && index < 2)
        if (hazardsAllowed) {
            val solid = 360f - gaps.sumOf { it.second.toDouble() }.toFloat()
            var total = solid * p.hazardShare * rng.range(0.8f, 1.15f) / easeFactor
            if (pattern == Pattern.CHUTE) total *= 0.5f
            var pieces = if (p.hazardPieces <= 1) 1 else rng.rangeInt(1, p.hazardPieces)
            if (pattern == Pattern.PINWHEEL) { pieces = rng.rangeInt(3, 5); total *= 0.9f }
            val pieceLen = (total / pieces).coerceIn(14f, 100f)
            var placed = 0
            if (pattern == Pattern.SANDWICH) {
                // Hug both edges of the main gap.
                val len = min(pieceLen, 40f)
                tryPlace(cells, gapC - gapW / 2f - len, len, protectedZones + startZone, 0f).also { if (it) placed++ }
                tryPlace(cells, gapC + gapW / 2f, len, protectedZones + startZone, 0f).also { if (it) placed++ }
            }
            var tries = 0
            while (placed < pieces && tries < 40) {
                tries++
                val start = rng.range(0f, 360f)
                if (tryPlace(cells, start, pieceLen * rng.range(0.85f, 1.15f), protectedZones + startZone, 5f)) placed++
            }
            // Guarantee a comfortable home stretch of normal tiles.
            if (longestRun(cells, Cell.NORMAL) < 40) {
                for (i in 0 until 360) if (cells[i] == Cell.HAZARD) cells[i] = Cell.NORMAL
            }
        }

        // ---- 4. Mechanics ----
        val intro = introduced
        fun chanceFor(m: Mechanic, base: Float) = if (intro == m) max(base, 0.38f) else base

        var spin = 0f
        var baseRot = 0f
        var mirror = false
        var rhythm = 0f
        var rhythmPhase = 0f
        val sliders = mutableListOf<Slider>()

        if (index > 1 && !p.warmup) {
            if (rng.chance(chanceFor(Mechanic.CRUMBLE, p.crumbleChance))) {
                val run = randomRun(cells, Cell.NORMAL, 50, rng)
                if (run != null) fill(cells, run.first + 5f, min(run.second - 10f, rng.range(30f, 70f)), Cell.CRUMBLE)
            }
            if (rng.chance(chanceFor(Mechanic.BOOST, p.boostChance))) {
                val run = randomRun(cells, Cell.NORMAL, 50, rng)
                if (run != null) fill(cells, run.first + run.second / 2f - 12f, 24f, Cell.BOOST)
            }
            val hasHazard = cells.any { it == Cell.HAZARD }
            if (hasHazard && rng.chance(chanceFor(Mechanic.PULSE, p.pulseChance))) {
                for (i in 0 until 360) if (cells[i] == Cell.HAZARD) cells[i] = Cell.PULSE
                rhythm = p.rhythmPeriod * rng.range(0.9f, 1.15f)
            }
            if (rng.chance(chanceFor(Mechanic.SHUTTER, p.shutterChance))) {
                for (i in 0 until 360) if (cells[i] == Cell.GAP) cells[i] = Cell.SHUTTER
                if (rhythm == 0f) rhythm = p.rhythmPeriod * rng.range(0.95f, 1.2f)
            }
            if (rhythm > 0f) rhythmPhase = rng.nextFloat()
            if (rng.chance(chanceFor(Mechanic.SLIDER, p.sliderChance))) {
                val run = randomRun(cells, Cell.NORMAL, 80, rng)
                if (run != null) {
                    val len = rng.range(20f, 34f)
                    val amp = ((run.second - len) / 2f - 3f).coerceAtMost(70f)
                    if (amp > 8f) sliders += Slider(
                        center = Physics.wrap360(run.first + run.second / 2f),
                        length = len,
                        amplitude = amp,
                        period = p.sliderPeriod * rng.range(0.9f, 1.15f),
                        phase = rng.nextFloat(),
                    )
                }
            }
            if (rng.chance(chanceFor(Mechanic.SPIN, p.spinChance))) {
                val slow = if (intro == Mechanic.SPIN) 0.6f else 1f
                spin = rng.sign() * p.spinSpeed * rng.range(0.7f, 1.1f) * slow / easeFactor
                baseRot = rng.range(0f, 360f)
            } else if (rng.chance(chanceFor(Mechanic.MIRROR, p.mirrorChance))) {
                mirror = true
                baseRot = rng.range(0f, 360f)
            }
        }

        // ---- 5. Coins & power-ups (in gaps) ----
        val coins = mutableListOf<Float>()
        val realGaps = gapsOf(cells, includeShutter = true)
        for ((s, w) in realGaps) {
            if (rng.chance(p.coinRate)) {
                val n = if (w > 85f) 2 else 1
                for (k in 0 until n) coins += Physics.wrap360(s + w * (k + 1) / (n + 1))
            }
        }
        var powerUp: PowerUpType? = null
        var powerUpAngle = 0f
        if (p.powerUpChance > 0f && powerUpsPlaced < 2 && index > 3 && realGaps.isNotEmpty() &&
            rng.chance(p.powerUpChance * 0.12f)
        ) {
            powerUp = rng.pickWeighted(
                listOf(
                    PowerUpType.SHIELD to 1f,
                    PowerUpType.MAGNET to 0.9f,
                    PowerUpType.SLOWMO to (if (p.d > 0.25f) 0.8f else 0.3f),
                    PowerUpType.DRILL to 0.7f,
                )
            )
            val g = realGaps.first()
            powerUpAngle = Physics.wrap360(g.first + g.second / 2f)
            coins.removeAll { abs(Physics.wrap360(it - powerUpAngle + 180f) - 180f) < 15f }
            powerUpsPlaced++
        }

        return RingSpec(
            index = index,
            kind = RingKind.NORMAL,
            cells = cells,
            sliders = sliders,
            baseRotation = baseRot,
            spinSpeed = spin,
            mirror = mirror,
            rhythmPeriod = rhythm,
            rhythmPhase = rhythmPhase,
            coins = coins,
            powerUp = powerUp,
            powerUpAngle = powerUpAngle,
        )
    }

    /** A ring that is always passable: a wide gap exactly where the player can already be. */
    private fun safeRing(index: Int): RingSpec {
        val cells = ByteArray(360) { Cell.NORMAL }
        var bestBin = 0
        // Choose any reachable bin from the earliest entry.
        val e = entries.firstOrNull()
        if (e != null) {
            for (b in 0 until FairnessSolver.BINS) {
                val set = if (b < 64) (e.lo ushr b) and 1L else (e.hi ushr (b - 64)) and 1L
                if (set == 1L) { bestBin = b; break }
            }
        }
        val alpha = (bestBin + 0.5f) * FairnessSolver.BIN_DEG
        val gapC = Physics.wrap360(Physics.BALL_ANGLE - alpha)
        fill(cells, gapC - 50f, 100f, Cell.GAP)
        return RingSpec(index, RingKind.NORMAL, cells, coins = listOf(gapC))
    }

    companion object {
        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

        fun fill(cells: ByteArray, start: Float, length: Float, code: Byte) {
            val s = start.roundToInt()
            val n = length.roundToInt().coerceIn(0, 360)
            for (k in 0 until n) cells[((s + k) % 360 + 360) % 360] = code
        }

        private fun tryPlace(cells: ByteArray, start: Float, length: Float, forbidden: List<Pair<Float, Float>>, buffer: Float): Boolean {
            val s = start.roundToInt()
            val n = length.roundToInt()
            val b = buffer.roundToInt()
            for (k in -b until n + b) {
                val idx = ((s + k) % 360 + 360) % 360
                if (cells[idx] != Cell.NORMAL) return false
                for ((zs, zw) in forbidden) {
                    if (Physics.wrap360(idx - zs) < zw) return false
                }
            }
            for (k in 0 until n) cells[((s + k) % 360 + 360) % 360] = Cell.HAZARD
            return true
        }

        /** Returns runs of [code] as (start, length) pairs, handling wrap-around. */
        fun runsOf(cells: ByteArray, code: Byte): List<Pair<Int, Int>> {
            val res = mutableListOf<Pair<Int, Int>>()
            // Find a starting index that is not part of a run crossing 0.
            var origin = -1
            for (i in 0 until 360) if (cells[i] != code) { origin = i; break }
            if (origin == -1) return listOf(Pair(0, 360))
            var i = 0
            while (i < 360) {
                val idx = (origin + i) % 360
                if (cells[idx] == code) {
                    var len = 0
                    while (len < 360 && cells[(origin + i + len) % 360] == code) len++
                    res += Pair(idx, len)
                    i += len
                } else i++
            }
            return res
        }

        fun longestRun(cells: ByteArray, code: Byte): Int = runsOf(cells, code).maxOfOrNull { it.second } ?: 0

        private fun randomRun(cells: ByteArray, code: Byte, minLen: Int, rng: Rng): Pair<Float, Float>? {
            val runs = runsOf(cells, code).filter { it.second >= minLen }
            if (runs.isEmpty()) return null
            val r = rng.pick(runs)
            return Pair(r.first.toFloat(), r.second.toFloat())
        }

        /** Gaps as (start, width). */
        fun gapsOf(cells: ByteArray, includeShutter: Boolean = false): List<Pair<Float, Float>> {
            val tmp = if (includeShutter) ByteArray(360) { if (cells[it] == Cell.SHUTTER) Cell.GAP else cells[it] } else cells
            return runsOf(tmp, Cell.GAP).map { Pair(it.first.toFloat(), it.second.toFloat()) }
        }
    }
}

object LevelGenerator {
    const val GAME_SEED = 0x5917_D1E0_2026_0001L
    private const val CACHE_SIZE = 6
    private val cache = object : LinkedHashMap<Int, LevelSpec>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, LevelSpec>?) = size > CACHE_SIZE
    }

    fun generate(level: Int): LevelSpec {
        synchronized(cache) { cache[level]?.let { return it } }
        val spec = build(level)
        synchronized(cache) { cache[level] = spec }
        return spec
    }

    private fun build(level: Int): LevelSpec {
        val l = level.coerceIn(1, Difficulty.MAX_LEVEL)
        val p = Difficulty.params(l)
        val seed = Rng.mix(GAME_SEED, l.toLong())
        val intro = Difficulty.introducedAt(l)
        val builder = TowerBuilder(seed, p, level = l, introduced = intro)
        val boss = Difficulty.isBoss(l)
        val main = p.ringCount
        val rng = Rng(Rng.mix(seed, 99L))
        if (l == Difficulty.UNLOCK_CHUTE) builder.forceChuteEndingAt(main / 2)
        if (boss && (l == Difficulty.UNLOCK_GATE || rng.chance(0.6f))) builder.forceChuteEndingAt(main - 1)
        val rings = ArrayList<RingSpec>(main + 6)
        for (i in 0 until main) rings += builder.buildRing(i)
        var idx = main
        if (boss) rings += builder.buildSpecial(idx++, RingKind.GATE, gateHp = p.gateHp)
        val mults = if (p.vaultRings == 2) listOf(2, 3) else listOf(2, 3, 5)
        for (m in mults) rings += builder.buildSpecial(idx++, RingKind.VAULT, vaultMultiplier = m)
        rings += builder.buildSpecial(idx, RingKind.FINISH)
        return LevelSpec(
            level = l,
            difficulty = p.d,
            worldIndex = Difficulty.worldIndex(l),
            rings = rings,
            gravityScale = p.gravityScale,
            isBoss = boss,
            introduced = intro,
            progressRings = main + if (boss) 1 else 0,
            seed = seed,
        )
    }

    /** Diagnostics for tests and the dev panel. */
    data class Report(val level: Int, val d: Float, val rings: Int, val regenerated: Int, val fallbacks: Int, val solvable: Boolean)

    fun report(level: Int): Report {
        val l = level.coerceIn(1, Difficulty.MAX_LEVEL)
        val p = Difficulty.params(l)
        val seed = Rng.mix(GAME_SEED, l.toLong())
        val builder = TowerBuilder(seed, p, level = l, introduced = Difficulty.introducedAt(l))
        val boss = Difficulty.isBoss(l)
        val rng = Rng(Rng.mix(seed, 99L))
        if (l == Difficulty.UNLOCK_CHUTE) builder.forceChuteEndingAt(p.ringCount / 2)
        if (boss && (l == Difficulty.UNLOCK_GATE || rng.chance(0.6f))) builder.forceChuteEndingAt(p.ringCount - 1)
        val rings = ArrayList<RingSpec>()
        for (i in 0 until p.ringCount) rings += builder.buildRing(i)
        // Independent re-validation of the finished tower.
        val solver = FairnessSolver(p.solverOmega, p.solverSafeMargin, p.solverGapMargin, p.gravityScale)
        var e = solver.startEntries()
        var ok = true
        for (r in rings) {
            e = solver.pass(r, e)
            if (e.isEmpty()) { ok = false; break }
        }
        return Report(l, p.d, rings.size, builder.regeneratedRings, builder.fallbackRings, ok)
    }

    /** Daily Challenge: a special seeded tower for the given day. */
    fun daily(dayIndex: Long, playerLevel: Int): LevelSpec {
        val base = (playerLevel + 15).coerceIn(20, 600)
        val p = Difficulty.params(base).copy(ringCount = 32, coinRate = 0.8f)
        val seed = Rng.mix(GAME_SEED, 0xDA17L, dayIndex)
        val builder = TowerBuilder(seed, p, level = base)
        val rings = ArrayList<RingSpec>()
        for (i in 0 until p.ringCount) rings += builder.buildRing(i)
        var idx = p.ringCount
        for (m in listOf(2, 3, 5)) rings += builder.buildSpecial(idx++, RingKind.VAULT, vaultMultiplier = m)
        rings += builder.buildSpecial(idx, RingKind.FINISH)
        return LevelSpec(base, p.d, (dayIndex % Difficulty.WORLD_COUNT).toInt(), rings, p.gravityScale, false, null, p.ringCount, seed)
    }
}

/** Endless mode: rings are generated on demand, difficulty grows with depth. */
class EndlessTower(runSeed: Long) {
    private val base = Difficulty.endlessParams(0).copy(gravityScale = 1.08f, solverOmega = 260f, solverSafeMargin = 9f, solverGapMargin = 7f)
    private val builder = TowerBuilder(
        seed = Rng.mix(LevelGenerator.GAME_SEED, 0xE5D1E55L, runSeed),
        baseParams = base,
        paramsForRing = { i -> Difficulty.endlessParams(i).copy(gravityScale = base.gravityScale, coinRate = 0.5f) },
        level = 15,
    )
    val rings = ArrayList<RingSpec>()
    val gravityScale = base.gravityScale

    fun ensure(count: Int) {
        while (rings.size < count) rings += builder.buildRing(rings.size)
    }
}
