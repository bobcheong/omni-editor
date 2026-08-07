package com.omnieditor.core.diff

import com.omnieditor.core.model.CompareResult
import com.omnieditor.core.model.CompareStats
import com.omnieditor.core.model.EngineMode
import com.omnieditor.core.model.Hunk
import com.omnieditor.core.model.HunkType
import com.omnieditor.core.model.RuleSet
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MergeEngineTest {

    private suspend fun diffAndMerge(
        left: List<String>,
        right: List<String>,
    ): CompareResult {
        return DiffEngine.compare(
            leftLineCount = left.size.toLong(),
            rightLineCount = right.size.toLong(),
            leftLine = { left[it.toInt()] },
            rightLine = { right[it.toInt()] },
        )
    }

    // ── Block merge ──

    @Test
    fun `merge single hunk left to right`() = runTest {
        val left = listOf("a", "X", "c")
        val right = listOf("a", "b", "c")
        val result = diffAndMerge(left, right)
        result.hunks.size shouldBe 1

        val action = MergeEngine.mergeHunk(0, result, left, right, MergeEngine.Direction.LEFT_TO_RIGHT)
        val target = right.toMutableList()
        MergeEngine.applyActions(target, listOf(action))
        target shouldBe left
    }

    @Test
    fun `merge single hunk right to left`() = runTest {
        val left = listOf("a", "b", "c")
        val right = listOf("a", "X", "c")
        val result = diffAndMerge(left, right)

        val action = MergeEngine.mergeHunk(0, result, left, right, MergeEngine.Direction.RIGHT_TO_LEFT)
        val target = left.toMutableList()
        MergeEngine.applyActions(target, listOf(action))
        target shouldBe right
    }

    @Test
    fun `merge insertion hunk`() = runTest {
        val left = listOf("a", "b", "c")
        val right = listOf("a", "c")
        val result = diffAndMerge(left, right)

        val action = MergeEngine.mergeHunk(0, result, left, right, MergeEngine.Direction.LEFT_TO_RIGHT)
        val target = right.toMutableList()
        MergeEngine.applyActions(target, listOf(action))
        target shouldBe left
    }

    @Test
    fun `merge deletion hunk`() = runTest {
        val left = listOf("a", "c")
        val right = listOf("a", "b", "c")
        val result = diffAndMerge(left, right)

        val action = MergeEngine.mergeHunk(0, result, left, right, MergeEngine.Direction.LEFT_TO_RIGHT)
        val target = right.toMutableList()
        MergeEngine.applyActions(target, listOf(action))
        target shouldBe left
    }

    // ── Accept all ──

    @Test
    fun `accept all left to right`() = runTest {
        val left = listOf("a", "X", "c", "Y", "e")
        val right = listOf("a", "b", "c", "d", "e")
        val result = diffAndMerge(left, right)

        val acceptResult = MergeEngine.acceptAll(result, left, right, MergeEngine.Direction.LEFT_TO_RIGHT)
        acceptResult.hunkCount shouldBe result.hunks.size

        val target = right.toMutableList()
        MergeEngine.applyActions(target, acceptResult.actions)
        target shouldBe left
    }

    @Test
    fun `accept all right to left`() = runTest {
        val left = listOf("a", "b", "c", "d", "e")
        val right = listOf("a", "X", "c", "Y", "e")
        val result = diffAndMerge(left, right)

        val acceptResult = MergeEngine.acceptAll(result, left, right, MergeEngine.Direction.RIGHT_TO_LEFT)
        val target = left.toMutableList()
        MergeEngine.applyActions(target, acceptResult.actions)
        target shouldBe right
    }

    @Test
    fun `accept all with no differences does nothing`() = runTest {
        val lines = listOf("a", "b", "c")
        val result = diffAndMerge(lines, lines)

        val acceptResult = MergeEngine.acceptAll(result, lines, lines, MergeEngine.Direction.LEFT_TO_RIGHT)
        acceptResult.hunkCount shouldBe 0
        acceptResult.actions.size shouldBe 0
    }

    // ── Property tests ──

    @Test
    fun `property - full L to R merge makes files identical`() = runTest {
        val testPairs = listOf(
            listOf("a", "b", "c") to listOf("a", "X", "c"),
            listOf("a", "b", "c", "d") to listOf("a", "c", "d"),
            listOf("a", "c") to listOf("a", "b", "c"),
            listOf("x", "y", "z") to listOf("a", "b", "c"),
            listOf("same") to listOf("same"),
            emptyList<String>() to listOf("added"),
            listOf("removed") to emptyList(),
        )

        for ((left, right) in testPairs) {
            val result = diffAndMerge(left, right)
            val verified = MergeEngine.verifyFullMerge(result, left, right, MergeEngine.Direction.LEFT_TO_RIGHT)
            verified shouldBe true
        }
    }

    @Test
    fun `property - full R to L merge makes files identical`() = runTest {
        val testPairs = listOf(
            listOf("a", "X", "c") to listOf("a", "b", "c"),
            listOf("a", "c", "d") to listOf("a", "b", "c", "d"),
            listOf("a", "b", "c") to listOf("a", "c"),
        )

        for ((left, right) in testPairs) {
            val result = diffAndMerge(left, right)
            val verified = MergeEngine.verifyFullMerge(result, left, right, MergeEngine.Direction.RIGHT_TO_LEFT)
            verified shouldBe true
        }
    }

    @Test
    fun `property - full merge on golden corpus cases`() = runTest {
        val goldenPairs = listOf(
            listOf("alpha", "beta", "gamma") to listOf("alpha", "BETA", "gamma"),
            listOf("first", "second", "third") to listOf("zeroth", "first", "second", "third"),
            listOf("first", "second", "third") to listOf("first", "second", "third", "fourth"),
            (0 until 100).map { "line $it" } to
                (0 until 100).map { if (it == 50) "CHANGED" else "line $it" },
        )

        for ((left, right) in goldenPairs) {
            val result = diffAndMerge(left, right)
            MergeEngine.verifyFullMerge(result, left, right, MergeEngine.Direction.LEFT_TO_RIGHT) shouldBe true
            MergeEngine.verifyFullMerge(result, left, right, MergeEngine.Direction.RIGHT_TO_LEFT) shouldBe true
        }
    }

    // ── Undo verification ──

    @Test
    fun `merge actions preserve original for undo`() = runTest {
        val left = listOf("a", "X", "c")
        val right = listOf("a", "b", "c")
        val result = diffAndMerge(left, right)
        val originalRight = right.toList()

        val action = MergeEngine.mergeHunk(0, result, left, right, MergeEngine.Direction.LEFT_TO_RIGHT)

        // The action records what to replace, so undo can reverse it
        action.targetStartLine shouldBe result.hunks[0].rightStart
        action.targetEndLine shouldBe result.hunks[0].rightEnd
        action.replacementLines shouldBe listOf("X")

        // Verify the original is captured for undo
        val deletedLines = right.subList(
            action.targetStartLine.toInt(),
            action.targetEndLine.toInt(),
        )
        deletedLines shouldBe listOf("b")

        // Apply
        val target = right.toMutableList()
        MergeEngine.applyActions(target, listOf(action))
        target shouldBe left

        // Undo: replace back
        val undoAction = MergeEngine.MergeAction(
            action.targetStartLine,
            action.targetStartLine + action.replacementLines.size,
            deletedLines,
            action.hunkIndex,
        )
        MergeEngine.applyActions(target, listOf(undoAction))
        target shouldBe originalRight
    }

    // ── Multiple scattered hunks ──

    @Test
    fun `accept all with multiple scattered changes`() = runTest {
        val left = (0 until 20).map { "line $it" }.toMutableList().also {
            it[3] = "LEFT_3"
            it[10] = "LEFT_10"
            it[17] = "LEFT_17"
        }
        val right = (0 until 20).map { "line $it" }
        val result = diffAndMerge(left, right)
        result.hunks.size shouldBe 3

        MergeEngine.verifyFullMerge(result, left, right, MergeEngine.Direction.LEFT_TO_RIGHT) shouldBe true
    }
}
