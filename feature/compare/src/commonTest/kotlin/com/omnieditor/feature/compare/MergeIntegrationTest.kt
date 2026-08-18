package com.omnieditor.feature.compare

import com.omnieditor.core.io.PieceTableDocument
import com.omnieditor.core.model.CompareResult
import com.omnieditor.core.model.CompareStats
import com.omnieditor.core.model.EngineMode
import com.omnieditor.core.model.Hunk
import com.omnieditor.core.model.HunkType
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * R-27: Tests that merge actually applies changes to documents.
 */
class MergeIntegrationTest {

    private fun makeResult(hunks: List<Hunk>): CompareResult = CompareResult(
        hunks = hunks,
        stats = CompareStats(
            linesAdded = hunks.sumOf { it.rightEnd - it.rightStart },
            linesRemoved = hunks.sumOf { it.leftEnd - it.leftStart },
            linesChanged = 0,
            hunkCount = hunks.size,
        ),
        engineMode = EngineMode.FULL_INDEX,
        generatedAt = 0L,
    )

    @Test
    fun `R-27 mergeHunk left-to-right applies change to right document`() {
        val leftLines = listOf("alpha", "beta", "gamma")
        val rightLines = listOf("alpha", "CHANGED", "gamma")

        val leftDoc = PieceTableDocument.create("alpha\nbeta\ngamma")
        val rightDoc = PieceTableDocument.create("alpha\nCHANGED\ngamma")

        val hunks = listOf(
            Hunk(type = HunkType.CHANGED, leftStart = 1, leftEnd = 2, rightStart = 1, rightEnd = 2),
        )
        val result = makeResult(hunks)

        val state = CompareState(
            result, leftLines, rightLines,
            leftDocument = leftDoc,
            rightDocument = rightDoc,
        )

        state.mergeHunk(0, MergeDirection.LEFT_TO_RIGHT) shouldBe true

        // Right document should now have "beta" instead of "CHANGED"
        rightDoc.text() shouldBe "alpha\nbeta\ngamma"
        rightDoc.dirty shouldBe true
        state.rightDirty shouldBe true

        // Left document should be unchanged
        leftDoc.text() shouldBe "alpha\nbeta\ngamma"
        leftDoc.dirty shouldBe false
    }

    @Test
    fun `R-27 mergeHunk right-to-left applies change to left document`() {
        val leftLines = listOf("alpha", "beta", "gamma")
        val rightLines = listOf("alpha", "CHANGED", "gamma")

        val leftDoc = PieceTableDocument.create("alpha\nbeta\ngamma")
        val rightDoc = PieceTableDocument.create("alpha\nCHANGED\ngamma")

        val hunks = listOf(
            Hunk(type = HunkType.CHANGED, leftStart = 1, leftEnd = 2, rightStart = 1, rightEnd = 2),
        )
        val result = makeResult(hunks)

        val state = CompareState(
            result, leftLines, rightLines,
            leftDocument = leftDoc,
            rightDocument = rightDoc,
        )

        state.mergeHunk(0, MergeDirection.RIGHT_TO_LEFT) shouldBe true

        // Left document should now have "CHANGED" instead of "beta"
        leftDoc.text() shouldBe "alpha\nCHANGED\ngamma"
        leftDoc.dirty shouldBe true
        state.leftDirty shouldBe true

        // Right document should be unchanged
        rightDoc.text() shouldBe "alpha\nCHANGED\ngamma"
        rightDoc.dirty shouldBe false
    }

    @Test
    fun `R-27 mergeHunk prevents double merge`() {
        val leftLines = listOf("alpha", "beta")
        val rightLines = listOf("alpha", "CHANGED")

        val leftDoc = PieceTableDocument.create("alpha\nbeta")
        val rightDoc = PieceTableDocument.create("alpha\nCHANGED")

        val hunks = listOf(
            Hunk(type = HunkType.CHANGED, leftStart = 1, leftEnd = 2, rightStart = 1, rightEnd = 2),
        )
        val result = makeResult(hunks)

        val state = CompareState(
            result, leftLines, rightLines,
            leftDocument = leftDoc,
            rightDocument = rightDoc,
        )

        state.mergeHunk(0, MergeDirection.LEFT_TO_RIGHT) shouldBe true
        state.mergeHunk(0, MergeDirection.LEFT_TO_RIGHT) shouldBe false // already merged
    }

    @Test
    fun `R-27 merge is one undo step`() {
        val leftLines = listOf("alpha", "beta", "gamma")
        val rightLines = listOf("alpha", "CHANGED", "gamma")

        val leftDoc = PieceTableDocument.create("alpha\nbeta\ngamma")
        val rightDoc = PieceTableDocument.create("alpha\nCHANGED\ngamma")

        val hunks = listOf(
            Hunk(type = HunkType.CHANGED, leftStart = 1, leftEnd = 2, rightStart = 1, rightEnd = 2),
        )
        val result = makeResult(hunks)

        val state = CompareState(
            result, leftLines, rightLines,
            leftDocument = leftDoc,
            rightDocument = rightDoc,
        )

        val undoCountBefore = rightDoc.undoCount
        state.mergeHunk(0, MergeDirection.LEFT_TO_RIGHT)

        // Should be exactly one undo step
        rightDoc.undoCount shouldBe undoCountBefore + 1

        // Undo should restore original content
        rightDoc.undo()
        rightDoc.text() shouldBe "alpha\nCHANGED\ngamma"
        rightDoc.dirty shouldBe false
    }

    @Test
    fun `R-27 acceptAll applies all changes as single undo step`() {
        val leftLines = listOf("A", "B", "C")
        val rightLines = listOf("X", "B", "Z")

        val leftDoc = PieceTableDocument.create("A\nB\nC")
        val rightDoc = PieceTableDocument.create("X\nB\nZ")

        val hunks = listOf(
            Hunk(type = HunkType.CHANGED, leftStart = 0, leftEnd = 1, rightStart = 0, rightEnd = 1),
            Hunk(type = HunkType.CHANGED, leftStart = 2, leftEnd = 3, rightStart = 2, rightEnd = 3),
        )
        val result = makeResult(hunks)

        val state = CompareState(
            result, leftLines, rightLines,
            leftDocument = leftDoc,
            rightDocument = rightDoc,
        )

        val undoCountBefore = rightDoc.undoCount
        val count = state.acceptAll(MergeDirection.LEFT_TO_RIGHT)

        count shouldBe 2
        rightDoc.text() shouldBe "A\nB\nC"
        rightDoc.dirty shouldBe true

        // All in one undo step
        rightDoc.undoCount shouldBe undoCountBefore + 1

        // Undo restores original
        rightDoc.undo()
        rightDoc.text() shouldBe "X\nB\nZ"
    }

    @Test
    fun `R-27 mergeHunk returns false when no document available`() {
        val leftLines = listOf("a", "b")
        val rightLines = listOf("a", "c")

        val hunks = listOf(
            Hunk(type = HunkType.CHANGED, leftStart = 1, leftEnd = 2, rightStart = 1, rightEnd = 2),
        )
        val result = makeResult(hunks)

        // No documents — merge not possible
        val state = CompareState(result, leftLines, rightLines)

        state.canMerge shouldBe false
        state.mergeHunk(0, MergeDirection.LEFT_TO_RIGHT) shouldBe false
    }

    @Test
    fun `R-27 mergeHunk handles insertion (added lines)`() {
        val leftLines = listOf("a", "new1", "new2", "b")
        val rightLines = listOf("a", "b")

        val leftDoc = PieceTableDocument.create("a\nnew1\nnew2\nb")
        val rightDoc = PieceTableDocument.create("a\nb")

        // Left has extra lines inserted between "a" and "b"
        val hunks = listOf(
            Hunk(type = HunkType.ADDED, leftStart = 1, leftEnd = 3, rightStart = 1, rightEnd = 1),
        )
        val result = makeResult(hunks)

        val state = CompareState(
            result, leftLines, rightLines,
            leftDocument = leftDoc,
            rightDocument = rightDoc,
        )

        state.mergeHunk(0, MergeDirection.LEFT_TO_RIGHT) shouldBe true
        rightDoc.text() shouldBe "a\nnew1\nnew2\nb"
    }

    @Test
    fun `R-27 mergeHunk handles deletion (removed lines)`() {
        val leftLines = listOf("a", "b")
        val rightLines = listOf("a", "extra1", "extra2", "b")

        val leftDoc = PieceTableDocument.create("a\nb")
        val rightDoc = PieceTableDocument.create("a\nextra1\nextra2\nb")

        // Right has extra lines that left doesn't have
        val hunks = listOf(
            Hunk(type = HunkType.REMOVED, leftStart = 1, leftEnd = 1, rightStart = 1, rightEnd = 3),
        )
        val result = makeResult(hunks)

        val state = CompareState(
            result, leftLines, rightLines,
            leftDocument = leftDoc,
            rightDocument = rightDoc,
        )

        state.mergeHunk(0, MergeDirection.LEFT_TO_RIGHT) shouldBe true
        rightDoc.text() shouldBe "a\nb"
    }

    @Test
    fun `R-27 in-memory lines update after merge`() {
        val leftLines = listOf("alpha", "beta")
        val rightLines = listOf("alpha", "CHANGED")

        val leftDoc = PieceTableDocument.create("alpha\nbeta")
        val rightDoc = PieceTableDocument.create("alpha\nCHANGED")

        val hunks = listOf(
            Hunk(type = HunkType.CHANGED, leftStart = 1, leftEnd = 2, rightStart = 1, rightEnd = 2),
        )
        val result = makeResult(hunks)

        val state = CompareState(
            result, leftLines, rightLines,
            leftDocument = leftDoc,
            rightDocument = rightDoc,
        )

        state.mergeHunk(0, MergeDirection.LEFT_TO_RIGHT)

        // In-memory lines should reflect the merge
        state.rightLines shouldBe listOf("alpha", "beta")
        // Left lines unchanged
        state.leftLines shouldBe listOf("alpha", "beta")
    }

    @Test
    fun `R-27 acceptAll right-to-left makes left match right`() {
        val leftLines = listOf("A", "B", "C")
        val rightLines = listOf("X", "B", "Z")

        val leftDoc = PieceTableDocument.create("A\nB\nC")
        val rightDoc = PieceTableDocument.create("X\nB\nZ")

        val hunks = listOf(
            Hunk(type = HunkType.CHANGED, leftStart = 0, leftEnd = 1, rightStart = 0, rightEnd = 1),
            Hunk(type = HunkType.CHANGED, leftStart = 2, leftEnd = 3, rightStart = 2, rightEnd = 3),
        )
        val result = makeResult(hunks)

        val state = CompareState(
            result, leftLines, rightLines,
            leftDocument = leftDoc,
            rightDocument = rightDoc,
        )

        state.acceptAll(MergeDirection.RIGHT_TO_LEFT) shouldBe 2
        leftDoc.text() shouldBe "X\nB\nZ"
    }
}
