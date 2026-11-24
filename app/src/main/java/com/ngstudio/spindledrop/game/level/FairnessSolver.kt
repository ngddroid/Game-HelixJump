package com.ngstudio.spindledrop.game.level

import kotlin.math.roundToInt

/**
 * Proves that a tower can be completed by a skilled-but-human player.
 *
 * The tower angle is split into 128 bins. For every ring we track which tower angles the
 * player can be at when the ball touches that ring (and when, and with what combo). Between
 * touches the player can turn the tower by at most `omega * dt * EFFICIENCY` degrees. Landings
 * must be safe across a whole margin window, so the player never needs pixel precision.
 *
 * Used ring-by-ring while generating: a ring that cannot be passed is regenerated.
 */
class FairnessSolver(
    private val omega: Float,
    private val safeMargin: Float,
    private val gapMargin: Float,
    private val gravityScale: Float,
) {
    class Entry(val t: Float, val combo: Int, val vf: Int, val lo: Long, val hi: Long)

    private val bounceTime = Physics.bouncePeriod(gravityScale)
    private val speeds = FloatArray(MAX_VF + 2)
    private val fallTimes = FloatArray(MAX_VF + 2)

    init {
        speeds[0] = Physics.bounceSpeed(gravityScale)
        for (k in 0..MAX_VF) {
            val (dt, v) = Physics.fallOneRing(speeds[k], gravityScale)
            fallTimes[k] = dt
            speeds[k + 1] = v
        }
    }

    /** Entry states for the first ring: ball dropped from rest at bounce height, tower at angle 0. */
    fun startEntries(): List<Entry> {
        val t0 = bounceTime * 0.5f
        val bin = angleToBin(0f)
        var lo = 0L
        var hi = 0L
        if (bin < 64) lo = 1L shl bin else hi = 1L shl (bin - 64)
        val (dl, dh) = dilate(lo, hi, rotationBins(t0))
        return listOf(Entry(t0, 0, 0, dl, dh))
    }

    /**
     * Processes one ring. Returns the entry states for the next ring, or an empty list if the
     * ring cannot be passed fairly. For VAULT/FINISH rings, returns [entries] unchanged (always passable).
     */
    fun pass(ring: RingSpec, entries: List<Entry>): List<Entry> {
        if (ring.kind == RingKind.VAULT || ring.kind == RingKind.FINISH) return entries
        val out = HashMap<Long, LongArray>() // key -> [lo, hi, tBits]
        val staticMasks = if (ring.isDynamic) null else masks(ring, 0f)
        val dynCache = HashMap<Int, LongArray>()

        for (e in entries) {
            var lo = e.lo
            var hi = e.hi
            var t = e.t
            var combo = e.combo
            var vf = e.vf
            var bounces = 0
            var gateHp = ring.gateHp
            while ((lo or hi) != 0L && bounces <= MAX_BOUNCES) {
                val blaze = combo >= Physics.COMBO_FOR_BLAZE
                if (ring.kind == RingKind.GATE) {
                    if (blaze || gateHp <= 1) {
                        addExit(out, t, if (blaze) 0 else 1, vf, lo, hi)
                        break
                    }
                    gateHp--
                    val (dl, dh) = dilate(lo, hi, rotationBins(bounceTime))
                    lo = dl; hi = dh
                    t += bounceTime; combo = 0; vf = 0; bounces++
                    continue
                }
                if (blaze) {
                    // Blaze smashes whatever it touches.
                    addExit(out, t, 0, vf, lo, hi)
                    break
                }
                val m = staticMasks ?: dynCache.getOrPut((t / 0.01f).roundToInt()) { masks(ring, t) }
                val fLo = lo and m[0]
                val fHi = hi and m[1]
                if ((fLo or fHi) != 0L) addExit(out, t, combo + 1, vf, fLo, fHi)
                val bLo = lo and m[4]
                val bHi = hi and m[5]
                if ((bLo or bHi) != 0L) addExit(out, t, Physics.COMBO_FOR_BLAZE, vf, bLo, bHi)
                val sLo = lo and m[2]
                val sHi = hi and m[3]
                if ((sLo or sHi) == 0L) break
                val (dl, dh) = dilate(sLo, sHi, rotationBins(bounceTime))
                lo = dl; hi = dh
                t += bounceTime; combo = 0; vf = 0; bounces++
            }
        }
        if (out.isEmpty()) return emptyList()
        // Convert exits into next-ring entries (apply the fall to the next ring + rotation during the fall).
        val next = ArrayList<Entry>(out.size)
        for ((key, v) in out) {
            val combo = ((key ushr 8) and 0xFF).toInt()
            val vf = (key and 0xFF).toInt()
            val t = java.lang.Float.intBitsToFloat(v[2].toInt())
            val vfIdx = vf.coerceAtMost(MAX_VF)
            val dt = fallTimes[vfIdx]
            val (dl, dh) = dilate(v[0], v[1], rotationBins(dt))
            next.add(Entry(t + dt, combo.coerceAtMost(Physics.COMBO_FOR_BLAZE), (vf + 1).coerceAtMost(MAX_VF), dl, dh))
        }
        next.sortBy { it.t }
        return if (next.size > MAX_ENTRIES) next.subList(0, MAX_ENTRIES) else next
    }

    private fun addExit(out: HashMap<Long, LongArray>, t: Float, combo: Int, vf: Int, lo: Long, hi: Long) {
        val tb = (t / TIME_BUCKET).roundToInt().toLong()
        val key = (tb shl 16) or (combo.coerceAtMost(Physics.COMBO_FOR_BLAZE).toLong() shl 8) or vf.coerceAtMost(MAX_VF).toLong()
        val cur = out[key]
        if (cur == null) {
            out[key] = longArrayOf(lo, hi, java.lang.Float.floatToIntBits(t).toLong())
        } else {
            cur[0] = cur[0] or lo
            cur[1] = cur[1] or hi
        }
    }

    /**
     * Returns [gapLo, gapHi, safeLo, safeHi, boostLo, boostHi] masks over tower-angle bins at time [t].
     */
    // Scratch prefix-sum buffers (solver instances are single-threaded).
    private val gapPre = IntArray(721)
    private val safePre = IntArray(721)
    private val boostPre = IntArray(721)

    fun masks(ring: RingSpec, t: Float): LongArray {
        // 1. Classify every degree once at time t, then use prefix sums for window checks.
        gapPre[0] = 0; safePre[0] = 0; boostPre[0] = 0
        for (i in 0 until 720) {
            val c = ring.contactAt((i % 360) + 0.5f, t)
            gapPre[i + 1] = gapPre[i] + if (c == Contact.GAP) 1 else 0
            safePre[i + 1] = safePre[i] + if (c == Contact.SAFE || c == Contact.CRUMBLE) 1 else 0
            boostPre[i + 1] = boostPre[i] + if (c == Contact.BOOST) 1 else 0
        }
        val res = LongArray(6)
        val ms = safeMargin.roundToInt()
        val mg = gapMargin.roundToInt()
        for (b in 0 until BINS) {
            val alpha = (b + 0.5f) * BIN_DEG
            val theta = Physics.wrap360(Physics.BALL_ANGLE - ring.rotation(alpha, t))
            val word = if (b < 64) 0 else 1
            val bit = 1L shl (b and 63)
            if (windowFull(gapPre, theta, mg)) res[word] = res[word] or bit
            if (windowFull(safePre, theta, ms)) res[2 + word] = res[2 + word] or bit
            if (windowFull(boostPre, theta, 3)) res[4 + word] = res[4 + word] or bit
        }
        return res
    }

    private fun windowFull(pre: IntArray, theta: Float, margin: Int): Boolean {
        var start = kotlin.math.floor(theta - margin).toInt()
        start = ((start % 360) + 360) % 360
        val len = 2 * margin + 1
        return pre[start + len] - pre[start] == len
    }

    private fun rotationBins(dt: Float): Int = ((omega * dt * EFFICIENCY) / BIN_DEG).toInt()

    companion object {
        const val BINS = 128
        const val BIN_DEG = 360f / BINS
        const val EFFICIENCY = 0.75f
        const val MAX_BOUNCES = 10
        const val MAX_ENTRIES = 10
        const val MAX_VF = 12
        const val TIME_BUCKET = 0.04f

        fun angleToBin(alpha: Float): Int = (Physics.wrap360(alpha) / BIN_DEG).toInt().coerceIn(0, BINS - 1)

        fun rotl(lo: Long, hi: Long, n0: Int): Pair<Long, Long> {
            var n = ((n0 % 128) + 128) % 128
            var l = lo
            var h = hi
            if (n >= 64) { val tmp = l; l = h; h = tmp; n -= 64 }
            if (n == 0) return Pair(l, h)
            val nl = (l shl n) or (h ushr (64 - n))
            val nh = (h shl n) or (l ushr (64 - n))
            return Pair(nl, nh)
        }

        /** Circular dilation of the bin set by [k] bins in both directions. */
        fun dilate(lo: Long, hi: Long, k: Int): Pair<Long, Long> {
            if ((lo or hi) == 0L || k <= 0) return Pair(lo, hi)
            if (k >= 64) return Pair(-1L, -1L)
            var l = lo
            var h = hi
            var remaining = k
            var step = 1
            while (remaining > 0) {
                val s = minOf(step, remaining)
                val (al, ah) = rotl(l, h, s)
                val (bl, bh) = rotl(l, h, -s)
                l = l or al or bl
                h = h or ah or bh
                remaining -= s
                step *= 2
            }
            return Pair(l, h)
        }

        fun popCount(lo: Long, hi: Long) = java.lang.Long.bitCount(lo) + java.lang.Long.bitCount(hi)
    }
}
