package com.ngstudio.spindledrop.game.level

/**
 * Small, fast, deterministic PRNG (SplitMix64). The same seed always produces the same
 * sequence on every device and JVM, which is what keeps all 10,000 levels identical everywhere.
 */
class Rng(seed: Long) {
    private var state: Long = seed

    fun nextLong(): Long {
        state += GOLDEN
        var z = state
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    /** Uniform in [0, 1). */
    fun nextFloat(): Float = (nextLong() ushr 40).toFloat() / (1L shl 24).toFloat()

    /** Uniform in [0, n). */
    fun nextInt(n: Int): Int {
        require(n > 0)
        return ((nextLong() ushr 1) % n).toInt()
    }

    fun range(a: Float, b: Float): Float = a + (b - a) * nextFloat()
    fun rangeInt(a: Int, bInclusive: Int): Int = a + nextInt(bInclusive - a + 1)
    fun chance(p: Float): Boolean = nextFloat() < p
    fun sign(): Int = if (nextFloat() < 0.5f) -1 else 1

    fun <T> pick(items: List<T>): T = items[nextInt(items.size)]

    /** Weighted pick: weights must be >= 0 and not all zero. */
    fun <T> pickWeighted(items: List<Pair<T, Float>>): T {
        var total = 0f
        for ((_, w) in items) total += w
        var r = nextFloat() * total
        for ((v, w) in items) {
            r -= w
            if (r <= 0f) return v
        }
        return items.last().first
    }

    companion object {
        private const val GOLDEN = -7046029254386353131L // 0x9E3779B97F4A7C15
        private const val MIX1 = -4658895280553007687L   // 0xBF58476D1CE4E5B9
        private const val MIX2 = -7723592293110705685L   // 0x94D049BB133111EB

        /** Combines several numbers into one well-mixed seed. */
        fun mix(vararg parts: Long): Long {
            var h = 0x5D1E_D20F_5EED_0001L
            for (p in parts) {
                h = h xor p
                h = Rng(h).nextLong()
            }
            return h
        }
    }
}
