package com.omnieditor.core.diff

import com.omnieditor.core.model.CompareResult
import com.omnieditor.core.model.Hunk
import com.omnieditor.core.model.HunkType

/**
 * Merge operations: copy changes between sides (OE-MRG-1..4).
 *
 * All operations return [MergeAction]s describing the edits to apply.
 * The caller applies them to the target document as a single undo step.
 * Undo restores byte-exact state (guaranteed by the PieceTable).
 */
object MergeEngine {

    /**
     * Direction of a merge operation.
     */
    enum class Direction {
        /** Copy from left to right (right becomes like left). */
        LEFT_TO_RIGHT,
        /** Copy from right to left (left becomes like right). */
        RIGHT_TO_LEFT,
    }

    /**
     * A merge action: replace a range of lines in the target with source lines.
     */
    data class MergeAction(
        val targetStartLine: Long,
        val targetEndLine: Long,
        val replacementLines: List<String>,
        val hunkIndex: Int,
    )

    /**
     * Merge a single hunk (block merge, OE-MRG-1).
     */
    fun mergeHunk(
        hunkIndex: Int,
        result: CompareResult,
        leftLines: List<String>,
        rightLines: List<String>,
        direction: Direction,
    ): MergeAction {
        val hunk = result.hunks[hunkIndex]
        return when (direction) {
            Direction.LEFT_TO_RIGHT -> {
                // Replace right side with left side content
                val sourceLines = extractLines(leftLines, hunk.leftStart, hunk.leftEnd)
                MergeAction(hunk.rightStart, hunk.rightEnd, sourceLines, hunkIndex)
            }
            Direction.RIGHT_TO_LEFT -> {
                // Replace left side with right side content
                val sourceLines = extractLines(rightLines, hunk.rightStart, hunk.rightEnd)
                MergeAction(hunk.leftStart, hunk.leftEnd, sourceLines, hunkIndex)
            }
        }
    }

    /**
     * Merge specific lines within a hunk (line-level merge, OE-MRG-1).
     */
    fun mergeLines(
        hunkIndex: Int,
        sourceLines: List<String>,
        targetStartLine: Long,
        targetEndLine: Long,
    ): MergeAction {
        return MergeAction(targetStartLine, targetEndLine, sourceLines, hunkIndex)
    }

    /**
     * Accept all hunks in one direction (OE-MRG-3).
     * Returns the count and the list of actions to apply.
     * Actions are ordered from bottom to top so applying them
     * sequentially doesn't invalidate line numbers.
     */
    fun acceptAll(
        result: CompareResult,
        leftLines: List<String>,
        rightLines: List<String>,
        direction: Direction,
    ): AcceptAllResult {
        val actions = result.hunks.mapIndexed { index, _ ->
            mergeHunk(index, result, leftLines, rightLines, direction)
        }.reversed() // bottom-to-top so line numbers stay valid

        return AcceptAllResult(
            actions = actions,
            hunkCount = result.hunks.size,
            lineCount = actions.sumOf { it.replacementLines.size.toLong() },
        )
    }

    /**
     * Apply a list of merge actions to a mutable line list.
     * Actions must be ordered bottom-to-top (highest line numbers first).
     * Returns the modified lines.
     */
    fun applyActions(
        lines: MutableList<String>,
        actions: List<MergeAction>,
    ): MutableList<String> {
        for (action in actions) {
            val start = action.targetStartLine.toInt().coerceIn(0, lines.size)
            val end = action.targetEndLine.toInt().coerceIn(start, lines.size)

            // Remove the target range
            if (end > start) {
                lines.subList(start, end).clear()
            }

            // Insert the replacement
            lines.addAll(start, action.replacementLines)
        }
        return lines
    }

    /**
     * Verify that applying all left-to-right merges makes the target
     * identical to the source under the active rule set.
     * Used by the property test.
     */
    fun verifyFullMerge(
        result: CompareResult,
        leftLines: List<String>,
        rightLines: List<String>,
        direction: Direction,
    ): Boolean {
        val target = when (direction) {
            Direction.LEFT_TO_RIGHT -> rightLines.toMutableList()
            Direction.RIGHT_TO_LEFT -> leftLines.toMutableList()
        }
        val source = when (direction) {
            Direction.LEFT_TO_RIGHT -> leftLines
            Direction.RIGHT_TO_LEFT -> rightLines
        }

        val acceptResult = acceptAll(result, leftLines, rightLines, direction)
        applyActions(target, acceptResult.actions)

        return target == source
    }

    private fun extractLines(lines: List<String>, start: Long, end: Long): List<String> {
        if (start >= end) return emptyList()
        val s = start.toInt().coerceIn(0, lines.size)
        val e = end.toInt().coerceIn(s, lines.size)
        return lines.subList(s, e).toList()
    }
}

data class AcceptAllResult(
    val actions: List<MergeEngine.MergeAction>,
    val hunkCount: Int,
    val lineCount: Long,
)
