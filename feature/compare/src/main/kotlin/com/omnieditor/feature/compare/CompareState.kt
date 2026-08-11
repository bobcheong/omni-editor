package com.omnieditor.feature.compare

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.omnieditor.core.diff.MergeEngine
import com.omnieditor.core.io.PieceTableDocument
import com.omnieditor.core.model.CompareResult
import com.omnieditor.core.model.Granularity
import com.omnieditor.core.model.Hunk
import com.omnieditor.core.model.HunkType
import com.omnieditor.core.model.RuleSet

/**
 * Direction for merge operations.
 */
enum class MergeDirection {
    /** Copy from left to right (right becomes like left). */
    LEFT_TO_RIGHT,
    /** Copy from right to left (left becomes like right). */
    RIGHT_TO_LEFT,
}

/**
 * Observable state for the compare view.
 *
 * Drives both unified and split layouts with difference navigation,
 * filter modes, and merge tracking.
 */
@Stable
class CompareState(
    result: CompareResult,
    leftLines: List<String>,
    rightLines: List<String>,
    val ruleSet: RuleSet = RuleSet.DEFAULT,
    /** Optional document backing the left side — enables merge write-back. */
    val leftDocument: PieceTableDocument? = null,
    /** Optional document backing the right side — enables merge write-back. */
    val rightDocument: PieceTableDocument? = null,
) {
    /** The current compare result — updates after merges trigger re-compare. */
    var result by mutableStateOf(result)
        internal set

    /** Current left-side lines — updates after merges. */
    var leftLines by mutableStateOf(leftLines)
        internal set

    /** Current right-side lines — updates after merges. */
    var rightLines by mutableStateOf(rightLines)
        internal set

    /**
     * Intra-line diff cache — keyed by (leftLineIdx, rightLineIdx), cleared when [result]
     * changes. Exposed so composables can pass it into rendering utilities without
     * recomputing per frame.
     */
    val intraLineCache: IntraLineCache = IntraLineCache()

    /** Granularity from the active rule set, forwarded to intra-line computation. */
    val granularity: Granularity get() = ruleSet.granularity
    /** Currently focused difference index (0-based). */
    var currentDiffIndex by mutableIntStateOf(0)
        internal set

    /** Total number of differences. */
    val diffCount: Int get() = result.hunks.size

    /** Current filter mode. */
    var filterMode by mutableStateOf(FilterMode.ALL)

    /** First visible line in the unified view. */
    var firstVisibleLine by mutableLongStateOf(0L)
        internal set

    /** Set of hunk indices already merged (prevents double-merge). */
    var mergedHunks by mutableStateOf(emptySet<Int>())
        internal set

    /** Whether the left document is dirty (has unsaved merge changes). */
    val leftDirty: Boolean get() = leftDocument?.dirty ?: false

    /** Whether the right document is dirty (has unsaved merge changes). */
    val rightDirty: Boolean get() = rightDocument?.dirty ?: false

    /** True when documents are available for merge write-back. */
    val canMerge: Boolean get() = leftDocument != null || rightDocument != null

    /** Message shown after a merge operation. */
    var mergeMessage by mutableStateOf<String?>(null)
        internal set

    /** Navigate to the next difference. */
    fun nextDiff() {
        if (currentDiffIndex < diffCount - 1) {
            currentDiffIndex++
        }
    }

    /** Navigate to the previous difference. */
    fun prevDiff() {
        if (currentDiffIndex > 0) {
            currentDiffIndex--
        }
    }

    /** Jump to a specific difference by index. */
    fun goToDiff(index: Int) {
        currentDiffIndex = index.coerceIn(0, maxOf(diffCount - 1, 0))
    }

    /** Get the currently focused hunk. */
    val currentHunk: Hunk? get() = result.hunks.getOrNull(currentDiffIndex)

    /**
     * Merge a single hunk in the given direction.
     *
     * Applies the change to the target document via [PieceTableDocument.replaceAll]
     * (one undo step). Updates the in-memory line lists and marks the hunk as merged.
     *
     * Returns true if the merge was applied, false if skipped (already merged or no document).
     */
    fun mergeHunk(hunkIndex: Int, direction: MergeDirection): Boolean {
        if (hunkIndex in mergedHunks) return false

        val targetDoc = when (direction) {
            MergeDirection.LEFT_TO_RIGHT -> rightDocument
            MergeDirection.RIGHT_TO_LEFT -> leftDocument
        } ?: return false

        val engineDirection = when (direction) {
            MergeDirection.LEFT_TO_RIGHT -> MergeEngine.Direction.LEFT_TO_RIGHT
            MergeDirection.RIGHT_TO_LEFT -> MergeEngine.Direction.RIGHT_TO_LEFT
        }

        val action = MergeEngine.mergeHunk(
            hunkIndex, result, leftLines, rightLines, engineDirection,
        )

        // Apply to the target document as one undo step
        applyActionToDocument(targetDoc, action)

        // Update in-memory lines from the document
        refreshLinesFromDocument(direction, targetDoc)

        mergedHunks = mergedHunks + hunkIndex
        mergeMessage = "Merged hunk ${hunkIndex + 1}: ${action.replacementLines.size} lines"
        return true
    }

    /**
     * Accept all unmerged hunks in one direction as a single batched edit.
     *
     * Builds the complete target content once and issues a single [replaceAll].
     * Returns the number of hunks applied, or 0 if nothing to do.
     */
    fun acceptAll(direction: MergeDirection): Int {
        val targetDoc = when (direction) {
            MergeDirection.LEFT_TO_RIGHT -> rightDocument
            MergeDirection.RIGHT_TO_LEFT -> leftDocument
        } ?: return 0

        val engineDirection = when (direction) {
            MergeDirection.LEFT_TO_RIGHT -> MergeEngine.Direction.LEFT_TO_RIGHT
            MergeDirection.RIGHT_TO_LEFT -> MergeEngine.Direction.RIGHT_TO_LEFT
        }

        // Get the target lines and apply all actions to build complete new content
        val targetLines = when (direction) {
            MergeDirection.LEFT_TO_RIGHT -> rightLines.toMutableList()
            MergeDirection.RIGHT_TO_LEFT -> leftLines.toMutableList()
        }

        val acceptResult = MergeEngine.acceptAll(
            result, leftLines, rightLines, engineDirection,
        )

        if (acceptResult.hunkCount == 0) return 0

        // Apply actions to the in-memory list to build the new content
        MergeEngine.applyActions(targetLines, acceptResult.actions)

        // Build the complete new content and issue one replaceAll
        val newContent = targetLines.joinToString("\n")
        targetDoc.replaceAll(0, targetDoc.length, newContent)

        // Refresh lines from document
        refreshLinesFromDocument(direction, targetDoc)

        // Mark all hunks as merged
        mergedHunks = mergedHunks + (0 until result.hunks.size).toSet()
        mergeMessage = "Applied ${acceptResult.hunkCount} changes (${acceptResult.lineCount} lines)"
        return acceptResult.hunkCount
    }

    /**
     * Apply a single [MergeAction] to a [PieceTableDocument] as one undo step.
     */
    private fun applyActionToDocument(
        doc: PieceTableDocument,
        action: MergeEngine.MergeAction,
    ) {
        // Calculate character offsets from line numbers
        val startOffset = lineToCharOffset(doc, action.targetStartLine)
        val endOffset = lineToCharOffset(doc, action.targetEndLine)

        val replacementText = if (action.replacementLines.isEmpty()) {
            ""
        } else {
            // Join with newline; if we're replacing a range that ends before the last line,
            // add a trailing newline to maintain line structure
            val joined = action.replacementLines.joinToString("\n")
            if (action.targetEndLine < doc.lineCount) "$joined\n" else joined
        }

        val deleteText = if (startOffset < endOffset) {
            // If deleting lines that aren't the last, include trailing newline
            val rawLength = endOffset - startOffset
            rawLength
        } else {
            0
        }

        doc.replaceAll(startOffset, deleteText, replacementText)
    }

    /**
     * Calculate the character offset of a given line number in the document.
     */
    private fun lineToCharOffset(doc: PieceTableDocument, lineNumber: Long): Int {
        if (lineNumber <= 0) return 0
        // Walk through lines to find the character offset
        var offset = 0
        for (i in 0 until lineNumber.coerceAtMost(doc.lineCount)) {
            val line = doc.line(i)
            offset += line.length
            // Add 1 for the newline character (except after the last line)
            if (i < doc.lineCount - 1) offset += 1
        }
        return offset
    }

    /**
     * Refresh the in-memory line list for the target side from its document.
     */
    private fun refreshLinesFromDocument(direction: MergeDirection, doc: PieceTableDocument) {
        val newLines = doc.text().lines()
        when (direction) {
            MergeDirection.LEFT_TO_RIGHT -> rightLines = newLines
            MergeDirection.RIGHT_TO_LEFT -> leftLines = newLines
        }
    }

    /**
     * Build the shared alignment model for both unified and split views (R-25).
     *
     * One List<AlignedRow> drives both views. Sync holds by construction because both
     * panes iterate the same list: left pane renders left-side rows, right pane renders
     * right-side rows, and spacer rows (null on one side) keep matched lines visually aligned.
     */
    fun buildAlignedRows(): List<AlignedRow> =
        buildAlignedRows(result.hunks, leftLines.size, rightLines.size)

    /**
     * Build the unified view rows from the compare result.
     * Each row represents a line in the interleaved unified view.
     */
    fun buildUnifiedRows(): List<UnifiedRow> {
        val rows = mutableListOf<UnifiedRow>()
        var leftIdx = 0L
        var rightIdx = 0L
        var hunkIdx = 0

        while (leftIdx < leftLines.size || rightIdx < rightLines.size) {
            val prevLeft = leftIdx
            val prevRight = rightIdx
            val prevHunk = hunkIdx

            val hunk = if (hunkIdx < result.hunks.size) result.hunks[hunkIdx] else null

            if (hunk != null && leftIdx == hunk.leftStart) {
                // Emit the hunk's lines
                // Removed lines (from left)
                for (i in hunk.leftStart until hunk.leftEnd) {
                    if (filterMode != FilterMode.MATCHES_ONLY) {
                        rows.add(
                            UnifiedRow(
                                side = Side.LEFT,
                                lineNumber = i,
                                text = leftLines[i.toInt()],
                                type = if (hunk.type == HunkType.REMOVED) RowType.REMOVED else RowType.CHANGED_OLD,
                                hunkIndex = hunkIdx,
                            )
                        )
                    }
                }
                // Added lines (from right)
                for (i in hunk.rightStart until hunk.rightEnd) {
                    if (filterMode != FilterMode.MATCHES_ONLY) {
                        rows.add(
                            UnifiedRow(
                                side = Side.RIGHT,
                                lineNumber = i,
                                text = rightLines[i.toInt()],
                                type = if (hunk.type == HunkType.ADDED) RowType.ADDED else RowType.CHANGED_NEW,
                                hunkIndex = hunkIdx,
                            )
                        )
                    }
                }

                leftIdx = hunk.leftEnd
                rightIdx = hunk.rightEnd
                hunkIdx++
            } else {
                // Context line (same on both sides)
                val contextEnd = hunk?.leftStart ?: leftLines.size.toLong()
                while (leftIdx < contextEnd && leftIdx < leftLines.size) {
                    if (filterMode != FilterMode.DIFFS_ONLY) {
                        rows.add(
                            UnifiedRow(
                                side = Side.BOTH,
                                lineNumber = leftIdx,
                                text = leftLines[leftIdx.toInt()],
                                type = RowType.CONTEXT,
                                hunkIndex = -1,
                            )
                        )
                    }
                    leftIdx++
                    rightIdx++
                }

                // Drain trailing right-only lines when hunks are exhausted
                if (hunk == null) {
                    while (rightIdx < rightLines.size) {
                        if (filterMode != FilterMode.DIFFS_ONLY) {
                            rows.add(
                                UnifiedRow(
                                    side = Side.RIGHT,
                                    lineNumber = rightIdx,
                                    text = rightLines[rightIdx.toInt()],
                                    type = RowType.ADDED,
                                    hunkIndex = -1,
                                )
                            )
                        }
                        rightIdx++
                    }
                    // Drain trailing left-only lines when hunks are exhausted
                    while (leftIdx < leftLines.size) {
                        if (filterMode != FilterMode.DIFFS_ONLY) {
                            rows.add(
                                UnifiedRow(
                                    side = Side.LEFT,
                                    lineNumber = leftIdx,
                                    text = leftLines[leftIdx.toInt()],
                                    type = RowType.REMOVED,
                                    hunkIndex = -1,
                                )
                            )
                        }
                        leftIdx++
                    }
                }
            }

            // Defensive guard: if nothing advanced, break to prevent infinite loop from future bugs
            if (leftIdx == prevLeft && rightIdx == prevRight && hunkIdx == prevHunk) {
                break
            }
        }

        return rows
    }
}

enum class FilterMode {
    /** Show all lines. */
    ALL,
    /** Show only differences. */
    DIFFS_ONLY,
    /** Show only matching lines. */
    MATCHES_ONLY,
}

enum class Side { LEFT, RIGHT, BOTH }

enum class RowType {
    CONTEXT,
    ADDED,
    REMOVED,
    CHANGED_OLD,
    CHANGED_NEW,
}

/**
 * A single row in the unified diff view.
 */
data class UnifiedRow(
    val side: Side,
    val lineNumber: Long,
    val text: String,
    val type: RowType,
    val hunkIndex: Int,
)
