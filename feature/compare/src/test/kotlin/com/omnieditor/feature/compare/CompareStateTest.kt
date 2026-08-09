package com.omnieditor.feature.compare

import com.omnieditor.core.model.CompareResult
import com.omnieditor.core.model.CompareStats
import com.omnieditor.core.model.EngineMode
import com.omnieditor.core.model.Hunk
import com.omnieditor.core.model.HunkType
import io.kotest.matchers.shouldBe
import org.junit.Test
import kotlin.random.Random

class CompareStateTest {

    /**
     * R-07: buildUnifiedRows() hangs forever when right has trailing lines not covered by hunks.
     *
     * Bug location: CompareState.buildUnifiedRows(), the else (context) branch.
     *
     * When all hunks are exhausted and leftIdx has reached leftLines.size, but rightIdx
     * is still less than rightLines.size, the outer while loop condition remains true
     * (rightIdx < rightLines.size). Inside the else branch (no remaining hunk),
     * contextEnd = hunk?.leftStart ?: leftLines.size.toLong() = leftLines.size.toLong().
     * The inner while condition (leftIdx < contextEnd) = (leftLines.size < leftLines.size)
     * = false, so the inner loop exits immediately without advancing either index or
     * emitting anything. The outer loop spins forever.
     *
     * Trigger: left and right share a common prefix, right has trailing lines, and no
     * hunk covers those trailing right-only lines. This happens when CompareResult is
     * constructed with hunks = emptyList() (e.g. a buggy engine pass) but left and right
     * differ in length, or when the last hunk's rightEnd < rightLines.size.
     *
     * This test constructs the minimum scenario:
     *   left = ["a", "b"]     (2 lines)
     *   right = ["a", "b", "c", "d"]  (4 lines, 2 extra at the end)
     *   hunks = []  (no hunks — simulates a CompareResult that missed the trailing additions)
     *
     * After emitting context for lines 0..1 (where leftIdx reaches leftLines.size=2),
     * rightIdx = 2, which is still < 4. The outer loop does not exit → infinite loop.
     *
     * This test uses a 5-second JUnit timeout to detect the hang.
     */
    @Test(timeout = 5000)
    fun `R-07 buildUnifiedRows terminates when right has trailing lines`() {
        val leftLines = listOf("a", "b")
        val rightLines = listOf("a", "b", "c", "d")

        // No hunks — simulates a result where the trailing additions on the right
        // were not recorded. After leftIdx is exhausted, rightIdx < rightLines.size
        // causes the outer while to spin forever.
        val result = CompareResult(
            hunks = emptyList(),
            stats = CompareStats(
                linesAdded = 0,
                linesRemoved = 0,
                linesChanged = 0,
                hunkCount = 0,
            ),
            engineMode = EngineMode.FULL_INDEX,
            generatedAt = 0L,
        )

        val state = CompareState(result, leftLines, rightLines)

        // If buildUnifiedRows() hangs, the 5-second timeout kills this test with a failure.
        // If it terminates (bug is fixed), the result should contain 4 rows:
        //   2 shared context rows (lines "a" and "b") + 2 trailing right-only context rows ("c", "d").
        val rows = state.buildUnifiedRows()
        rows.size shouldBe 4  // 2 shared context + 2 trailing right-only
    }

    /**
     * R-07 fuzz: buildUnifiedRows() terminates on 10,000 random left/right pairs.
     *
     * Verifies that no combination of random inputs causes an infinite loop or exception.
     * The 30-second timeout catches any hang.
     */
    @Test(timeout = 30000)
    fun `R-07 fuzz - buildUnifiedRows terminates on random inputs`() {
        val rng = Random(42)
        repeat(10_000) {
            val leftSize = rng.nextInt(20)
            val rightSize = rng.nextInt(20)
            val leftLines = (0 until leftSize).map { "L$it" }
            val rightLines = (0 until rightSize).map { "R$it" }

            val hunks = generateRandomHunks(rng, leftSize, rightSize)

            val result = CompareResult(
                hunks = hunks,
                stats = CompareStats(
                    linesAdded = hunks.sumOf { (it.rightEnd - it.rightStart) },
                    linesRemoved = hunks.sumOf { (it.leftEnd - it.leftStart) },
                    linesChanged = 0,
                    hunkCount = hunks.size,
                ),
                engineMode = EngineMode.FULL_INDEX,
                generatedAt = 0L,
            )

            val state = CompareState(result, leftLines, rightLines)
            // Must not hang; result size must be non-negative
            val rows = state.buildUnifiedRows()
            check(rows.size >= 0) { "Negative row count" }
        }
    }

    /**
     * Generate a list of non-overlapping, sorted hunks within the given left/right bounds.
     * Hunks are created by picking random intervals and filtering to avoid overlaps.
     */
    private fun generateRandomHunks(rng: Random, leftSize: Int, rightSize: Int): List<Hunk> {
        if (leftSize == 0 && rightSize == 0) return emptyList()

        val hunkCount = rng.nextInt(5)  // 0..4 hunks
        if (hunkCount == 0) return emptyList()

        val candidates = mutableListOf<Hunk>()
        repeat(hunkCount * 3) {  // generate more than needed, then filter
            val lStart = if (leftSize > 0) rng.nextInt(leftSize) else 0
            val lEnd = if (leftSize > 0) rng.nextInt(leftSize + 1).coerceAtLeast(lStart) else 0
            val rStart = if (rightSize > 0) rng.nextInt(rightSize) else 0
            val rEnd = if (rightSize > 0) rng.nextInt(rightSize + 1).coerceAtLeast(rStart) else 0
            val type = HunkType.entries[rng.nextInt(HunkType.entries.size)]
            candidates.add(Hunk(lStart.toLong(), lEnd.toLong(), rStart.toLong(), rEnd.toLong(), type))
        }

        // Sort by leftStart, then filter to non-overlapping on both left and right
        val sorted = candidates.sortedWith(compareBy({ it.leftStart }, { it.rightStart }))
        val result = mutableListOf<Hunk>()
        var nextLeft = 0L
        var nextRight = 0L
        for (h in sorted) {
            if (h.leftStart >= nextLeft && h.rightStart >= nextRight) {
                result.add(h)
                nextLeft = h.leftEnd
                nextRight = h.rightEnd
            }
        }
        return result
    }
}
