package com.omnieditor.feature.compare

import com.omnieditor.core.model.CompareResult
import com.omnieditor.core.model.CompareStats
import com.omnieditor.core.model.EngineMode
import com.omnieditor.core.model.Hunk
import com.omnieditor.core.model.HunkType
import io.kotest.matchers.shouldBe
import org.junit.Test

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
        // If it terminates (bug is fixed), the result should contain the context rows.
        val rows = state.buildUnifiedRows()
        // With the bug fixed, at minimum the 2 context rows should appear.
        rows.size shouldBe 2
    }
}
