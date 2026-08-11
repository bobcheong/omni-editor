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
            // Must not hang; every input line must appear exactly once in the output.
            val rows = state.buildUnifiedRows()
            val leftRowCount = rows.count { it.side == Side.LEFT || it.side == Side.BOTH }
            val rightRowCount = rows.count { it.side == Side.RIGHT || it.side == Side.BOTH }
            leftRowCount shouldBe leftSize
            rightRowCount shouldBe rightSize
        }
    }

    /**
     * R-25: buildAlignedRows() keeps matched lines on the same visual row after an insertion.
     *
     * Scenario: Left has 5 lines. Right has 40 inserted lines followed by the same 5 lines.
     * One ADDED hunk covers the 40 inserted lines at the top of the right side. After the
     * 40 spacer rows (left=null, right=non-null), the 5 matching context lines must appear
     * at the same visual row index in both panes.
     */
    @Test
    fun `R-25 aligned rows keep matched lines on same visual row after insertion`() {
        val leftLines = (0 until 5).map { "line$it" }
        val rightLines = (0 until 40).map { "inserted$it" } + (0 until 5).map { "line$it" }

        // One ADDED hunk at the top covering the 40 inserted lines
        val hunks = listOf(
            Hunk(
                type = HunkType.ADDED,
                leftStart = 0L, leftEnd = 0L,
                rightStart = 0L, rightEnd = 40L,
            )
        )

        val result = CompareResult(
            hunks = hunks,
            stats = CompareStats(
                linesAdded = 40,
                linesRemoved = 0,
                linesChanged = 0,
                hunkCount = 1,
            ),
            engineMode = EngineMode.FULL_INDEX,
            generatedAt = 0L,
        )

        val state = CompareState(result, leftLines, rightLines)
        val rows = state.buildAlignedRows()

        // After the 40 spacer/added rows, the matched lines should be on the same row
        val matchedRows = rows.filter { it.left != null && it.right != null }
        matchedRows.size shouldBe 5
        matchedRows.forEach { row ->
            leftLines[row.left!!.toInt()] shouldBe rightLines[row.right!!.toInt()]
        }
    }

    /**
     * R-25: buildAlignedRows() produces a row for every left and every right line (no gaps).
     */
    @Test
    fun `R-25 aligned rows cover all lines on both sides`() {
        val leftLines = listOf("a", "b", "c")
        val rightLines = listOf("x", "b", "y", "z")

        val hunks = listOf(
            Hunk(type = HunkType.CHANGED, leftStart = 0L, leftEnd = 1L, rightStart = 0L, rightEnd = 1L),
            Hunk(type = HunkType.ADDED,   leftStart = 2L, leftEnd = 2L, rightStart = 2L, rightEnd = 4L),
        )

        val result = CompareResult(
            hunks = hunks,
            stats = CompareStats(linesAdded = 3, linesRemoved = 1, linesChanged = 1, hunkCount = 2),
            engineMode = EngineMode.FULL_INDEX,
            generatedAt = 0L,
        )

        val state = CompareState(result, leftLines, rightLines)
        val rows = state.buildAlignedRows()

        val leftCovered = rows.mapNotNull { it.left }.sorted()
        val rightCovered = rows.mapNotNull { it.right }.sorted()

        leftCovered shouldBe (0 until leftLines.size).map { it.toLong() }
        rightCovered shouldBe (0 until rightLines.size).map { it.toLong() }
    }

    /**
     * Generate a list of non-overlapping, well-formed hunks within the given left/right bounds.
     *
     * "Well-formed" means:
     * - Context gaps between hunks are equal length on both left and right, matching the
     *   invariant that buildUnifiedRows() advances leftIdx and rightIdx in lockstep through
     *   context regions.
     * - Any lines not covered by a hunk are trailing context and are equal on both sides,
     *   so the final reconciliation (every input line appears exactly once) holds.
     *
     * To enforce this, the generator picks a shared context budget and allocates it as
     * leading context + inter-hunk gaps, leaving equal trailing context on both sides.
     * Any asymmetry between left and right is expressed only within hunk spans.
     */
    private fun generateRandomHunks(rng: Random, leftSize: Int, rightSize: Int): List<Hunk> {
        // Determine how many lines will be shared context (same on both sides)
        val maxShared = minOf(leftSize, rightSize)

        // Choose shared context count: some portion of maxShared (the rest also goes to hunks)
        val sharedContext = if (maxShared > 0) rng.nextInt(maxShared + 1) else 0
        // Lines that must appear in hunks on each side (beyond purely left/right-only)
        val leftInHunks = leftSize - sharedContext   // = leftOnlyTotal + (maxShared - sharedContext)
        val rightInHunks = rightSize - sharedContext

        if (leftInHunks == 0 && rightInHunks == 0) return emptyList()

        val hunkCount = rng.nextInt(5).coerceAtLeast(1)
        val result = mutableListOf<Hunk>()
        var nextLeft = 0L
        var nextRight = 0L
        var leftBudget = leftInHunks
        var rightBudget = rightInHunks
        var contextBudget = sharedContext  // shared context lines not yet used as inter-hunk gaps

        repeat(hunkCount) { idx ->
            val isLast = (idx == hunkCount - 1)
            if (leftBudget <= 0 && rightBudget <= 0) return@repeat

            // Inter-hunk context gap (equal on both sides)
            val maxCtx = if (isLast) 0 else contextBudget
            val ctxGap = if (maxCtx > 0) rng.nextInt(maxCtx + 1) else 0
            val lStart = nextLeft + ctxGap
            val rStart = nextRight + ctxGap
            contextBudget -= ctxGap

            // Hunk span — must consume at least 1 line total; last hunk must consume all remaining budget
            val lSpan: Int
            val rSpan: Int
            if (isLast) {
                lSpan = leftBudget
                rSpan = rightBudget
            } else {
                lSpan = if (leftBudget > 0) rng.nextInt(leftBudget + 1) else 0
                rSpan = if (rightBudget > 0) rng.nextInt(rightBudget + 1) else 0
                if (lSpan == 0 && rSpan == 0 && (leftBudget > 0 || rightBudget > 0)) return@repeat
            }
            if (lSpan == 0 && rSpan == 0) return@repeat

            val lEnd = lStart + lSpan
            val rEnd = rStart + rSpan
            val type = HunkType.entries[rng.nextInt(HunkType.entries.size)]
            result.add(Hunk(lStart, lEnd, rStart, rEnd, type))
            nextLeft = lEnd
            nextRight = rEnd
            leftBudget -= lSpan
            rightBudget -= rSpan
        }
        return result
    }
}
