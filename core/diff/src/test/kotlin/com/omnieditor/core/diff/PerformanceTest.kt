package com.omnieditor.core.diff

import com.omnieditor.core.model.RuleSet
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * JVM-level performance tests for the diff engine (T-28).
 *
 * These verify that the engine meets reasonable performance expectations
 * on the JVM. The actual NFR-P1..P5 budgets from spec §11 require a
 * physical Android device (Tier 4) and are carried as release blockers.
 *
 * Budgets from spec:
 *   NFR-P1: Cold start to interactive Home < 1.2s (Tier 4)
 *   NFR-P2: Compare two 5MB text files, first diff visible < 1.5s
 *   NFR-P3: Compare two 250MB text files, complete < 45s
 *   NFR-P4: Scroll 500k-line diff < 16ms per frame (Tier 4)
 *   NFR-P5: Peak heap during any compare < 40% of device limit (Tier 4)
 */
class PerformanceTest {

    @Test
    fun `5MB file compare completes in reasonable time`() = runTest {
        // ~5MB ≈ 50,000 lines of ~100 chars
        val size = 50_000
        val left = (0 until size).map { "line %06d: common content with some padding to reach typical line length".format(it) }
        val right = left.toMutableList().also {
            // 50 scattered changes
            for (i in 0 until 50) {
                val pos = (i * size / 50).coerceIn(0, size - 1)
                it[pos] = "line %06d: MODIFIED content with different text here".format(pos)
            }
        }

        val start = System.currentTimeMillis()
        val result = DiffEngine.compare(
            leftLineCount = size.toLong(),
            rightLineCount = size.toLong(),
            leftLine = { left[it.toInt()] },
            rightLine = { right[it.toInt()] },
        )
        val elapsed = System.currentTimeMillis() - start

        result.hunks.size shouldBe 50
        println("5MB compare: ${elapsed}ms, ${result.hunks.size} hunks")
        // Should complete well under 30s on JVM (spec says <1.5s on device)
        (elapsed < 30_000) shouldBe true
    }

    @Test
    fun `large file with normalisation completes`() = runTest {
        val size = 20_000
        val rules = RuleSet(ignoreCase = true)
        val left = (0 until size).map { "Line %05d: Content Here".format(it) }
        val right = (0 until size).map { "line %05d: content here".format(it) }

        val start = System.currentTimeMillis()
        val result = DiffEngine.compare(
            leftLineCount = size.toLong(),
            rightLineCount = size.toLong(),
            leftLine = { left[it.toInt()] },
            rightLine = { right[it.toInt()] },
            rules = rules,
        )
        val elapsed = System.currentTimeMillis() - start

        // With ignoreCase, all lines should match
        result.hunks.size shouldBe 0
        println("20k lines with ignoreCase: ${elapsed}ms")
        (elapsed < 30_000) shouldBe true
    }

    @Test
    fun `block diff on simulated large file`() = runTest {
        // Simulate a file large enough for block mode (10k lines, small block size)
        val size = 10_000
        val left = (0 until size).map { "line %06d: common content".format(it) }
        val right = left.toMutableList().also {
            it[2500] = "CHANGED at 2500"
            it[5000] = "CHANGED at 5000"
            it[7500] = "CHANGED at 7500"
        }

        val start = System.currentTimeMillis()
        val result = BlockDiff.compare(
            leftLineCount = size.toLong(),
            rightLineCount = size.toLong(),
            leftLine = { left[it.toInt()] },
            rightLine = { right[it.toInt()] },
            blockSize = 100,
        )
        val elapsed = System.currentTimeMillis() - start

        result.hunks.size shouldBe 3
        println("Block diff 10k lines: ${elapsed}ms, ${result.hunks.size} hunks")
        (elapsed < 30_000) shouldBe true
    }

    @Test
    fun `intra-line diff is fast for typical lines`() {
        val left = "int calculateTotalPrice(List<Item> items, double taxRate, boolean includeShipping)"
        val right = "int calculateTotalCost(List<Item> items, double taxRate, boolean includeDiscount)"

        val start = System.nanoTime()
        repeat(1000) {
            IntraLineDiff.compute(
                com.omnieditor.core.model.LinePair(0, 0, left, right),
                com.omnieditor.core.model.Granularity.WORD,
            )
        }
        val elapsedUs = (System.nanoTime() - start) / 1000
        val perCallUs = elapsedUs / 1000

        println("Intra-line diff: ${perCallUs}µs per call (1000 iterations)")
        // Should be well under 1ms per call (spec says <1ms for 4KB lines)
        (perCallUs < 1_000) shouldBe true
    }
}
