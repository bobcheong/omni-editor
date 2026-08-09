package com.omnieditor.feature.compare

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.omnieditor.core.model.CompareResult
import com.omnieditor.core.model.Hunk
import com.omnieditor.core.model.HunkType

/**
 * Observable state for the compare view.
 *
 * Drives both unified and split layouts with difference navigation,
 * filter modes, and merge tracking.
 */
@Stable
class CompareState(
    val result: CompareResult,
    val leftLines: List<String>,
    val rightLines: List<String>,
) {
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
