package com.ngstudio.spindledrop

import com.ngstudio.spindledrop.game.level.Cell
import com.ngstudio.spindledrop.game.level.Difficulty
import com.ngstudio.spindledrop.game.level.FairnessSolver
import com.ngstudio.spindledrop.game.level.LevelGenerator
import com.ngstudio.spindledrop.game.level.Physics
import com.ngstudio.spindledrop.game.level.RingKind
import com.ngstudio.spindledrop.game.level.TowerBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.IntStream

class LevelGeneratorTest {

    @Test
    fun difficultyIsStrictlyIncreasingFromLevel11() {
        for (l in 2..Difficulty.MAX_LEVEL) {
            assertTrue("D($l) must be > D(${l - 1})", Difficulty.d(l) > Difficulty.d(l - 1))
        }
        assertTrue(Difficulty.d(10) < Difficulty.d(11))
        assertTrue(Difficulty.d(Difficulty.MAX_LEVEL) <= 1.0001f)
    }

    @Test
    fun generationIsDeterministic() {
        for (l in listOf(1, 7, 10, 42, 999, 10_000)) {
            val a = LevelGenerator.generate(l)
            val b = LevelGenerator.report(l) // builds independently
            assertTrue(b.solvable)
            val c = LevelGenerator.generate(l)
            assertEquals(a.rings.size, c.rings.size)
            for (i in a.rings.indices) assertArrayEquals(a.rings[i].cells, c.rings[i].cells)
        }
    }

    @Test
    fun warmupLevelsAreGuaranteedWins() {
        for (l in 1..10) {
            val spec = LevelGenerator.generate(l)
            val main = spec.rings.filter { it.kind == RingKind.NORMAL }
            for (r in main) {
                assertTrue("warm-up ring must be static", !r.isDynamic)
                if (l <= 2) assertTrue("levels 1-2 have no hazards", r.cells.none { it == Cell.HAZARD })
            }
            // No hazard is ever directly under a gap of the ring above (with 20 degree protection).
            for (i in 1 until main.size) {
                val above = main[i - 1]
                val below = main[i]
                for (deg in 0 until 360) {
                    if (above.cells[deg] != Cell.GAP) continue
                    for (o in -20..20) {
                        val idx = ((deg + o) % 360 + 360) % 360
                        assertTrue("L$l ring $i has a hazard under a gap at $idx", below.cells[idx] != Cell.HAZARD)
                    }
                }
            }
            // Start position is safe.
            val start = main.first().cells[Physics.BALL_ANGLE.toInt()]
            assertEquals(Cell.NORMAL, start)
            if (l == 10) assertTrue(spec.isBoss && spec.rings.any { it.kind == RingKind.GATE })
        }
    }

    @Test
    fun allTenThousandLevelsAreSolvable() {
        val failures = ConcurrentLinkedQueue<String>()
        val fallbacks = AtomicInteger()
        val regenerated = AtomicInteger()
        val started = System.currentTimeMillis()
        IntStream.rangeClosed(1, Difficulty.MAX_LEVEL).parallel().forEach { l ->
            val r = LevelGenerator.report(l)
            if (!r.solvable) failures += "L$l"
            fallbacks.addAndGet(r.fallbacks)
            regenerated.addAndGet(r.regenerated)
            val p = Difficulty.params(l)
            if (r.rings !in 11..48) failures += "L$l ring count ${r.rings}"
            if (p.ringCount != r.rings) failures += "L$l ring mismatch"
        }
        val secs = (System.currentTimeMillis() - started) / 1000.0
        println("Generated + solved 10,000 levels in ${secs}s; regenerated rings=$regenerated, safe-fallback rings=$fallbacks")
        assertTrue("Unsolvable levels: ${failures.take(20)}", failures.isEmpty())
    }

    @Test
    fun printDifficultyReport() {
        val sb = StringBuilder("Level | D | rings | gap | hazard% | spin% | omega\n")
        for (l in listOf(1, 5, 10, 11, 20, 30, 50, 100, 200, 300, 500, 1000, 2000, 5000, 10_000)) {
            val p = Difficulty.params(l)
            sb.append("$l | ${"%.3f".format(p.d)} | ${p.ringCount} | ${p.gapWidth.toInt()} | ${(p.hazardShare * 100).toInt()} | ${(p.spinChance * 100).toInt()} | ${p.solverOmega.toInt()}\n")
        }
        println(sb)
    }

    @Test
    fun endlessStreamsRings() {
        val e = com.ngstudio.spindledrop.game.level.EndlessTower(1234L)
        e.ensure(300)
        assertEquals(300, e.rings.size)
    }

    @Test
    fun dilationCoversExpectedBins() {
        val (lo, hi) = FairnessSolver.dilate(1L, 0L, 3)
        assertEquals(7, FairnessSolver.popCount(lo, hi))
        val (l2, h2) = FairnessSolver.dilate(1L, 0L, 200)
        assertEquals(128, FairnessSolver.popCount(l2, h2))
        assertTrue(TowerBuilder.runsOf(ByteArray(360) { Cell.NORMAL }, Cell.NORMAL).single().second == 360)
    }
}
