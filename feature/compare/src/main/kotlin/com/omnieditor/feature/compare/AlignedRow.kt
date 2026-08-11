package com.omnieditor.feature.compare

import com.omnieditor.core.model.Hunk
import com.omnieditor.core.model.HunkType

/**
 * A single visual row in the alignment model shared by unified and split views (R-25).
 *
 * Both panes in the split view iterate the same List<AlignedRow>, so they are aligned by
 * construction. Spacer rows (left == null or right == null) ensure that matched context
 * lines always appear on the same visual row after an insertion or deletion.
 */
data class AlignedRow(
    /** Left line index (0-based), or null when this row is a right-only spacer/added line. */
    val left: Long?,
    /** Right line index (0-based), or null when this row is a left-only spacer/removed line. */
    val right: Long?,
    /** Which hunk this row belongs to, or null for context rows. */
    val hunkIndex: Int?,
    /** Visual classification used for colour-coding and glyph selection. */
    val type: RowType,
)

/**
 * Build a List<AlignedRow> from [hunks] and the total line counts of each side.
 *
 * Algorithm:
 * - Walk context regions between hunks, emitting CONTEXT rows where both sides advance in
 *   lockstep (left != null, right != null).
 * - For each hunk, emit REMOVED / CHANGED_OLD rows (left non-null, right null) then
 *   ADDED / CHANGED_NEW rows (left null, right non-null).
 * - Spacer rows keep the two panes aligned after every insertion or deletion.
 */
fun buildAlignedRows(
    hunks: List<Hunk>,
    leftLineCount: Int,
    rightLineCount: Int,
): List<AlignedRow> {
    val rows = mutableListOf<AlignedRow>()
    var leftIdx = 0L
    var rightIdx = 0L

    for ((hunkIdx, hunk) in hunks.withIndex()) {
        // Emit context rows up to the start of this hunk.
        // Context regions must be symmetric: leftIdx and rightIdx advance together.
        val contextEnd = hunk.leftStart
        while (leftIdx < contextEnd) {
            rows.add(
                AlignedRow(
                    left = leftIdx,
                    right = rightIdx,
                    hunkIndex = null,
                    type = RowType.CONTEXT,
                )
            )
            leftIdx++
            rightIdx++
        }

        // Emit removed / changed-old rows (left side of hunk, right is spacer).
        val oldType = if (hunk.type == HunkType.REMOVED) RowType.REMOVED else RowType.CHANGED_OLD
        for (i in hunk.leftStart until hunk.leftEnd) {
            rows.add(
                AlignedRow(
                    left = i,
                    right = null,
                    hunkIndex = hunkIdx,
                    type = oldType,
                )
            )
        }

        // Emit added / changed-new rows (right side of hunk, left is spacer).
        val newType = if (hunk.type == HunkType.ADDED) RowType.ADDED else RowType.CHANGED_NEW
        for (i in hunk.rightStart until hunk.rightEnd) {
            rows.add(
                AlignedRow(
                    left = null,
                    right = i,
                    hunkIndex = hunkIdx,
                    type = newType,
                )
            )
        }

        leftIdx = hunk.leftEnd
        rightIdx = hunk.rightEnd
    }

    // Emit trailing context rows after the last hunk.
    while (leftIdx < leftLineCount || rightIdx < rightLineCount) {
        val hasLeft = leftIdx < leftLineCount
        val hasRight = rightIdx < rightLineCount
        rows.add(
            AlignedRow(
                left = if (hasLeft) leftIdx else null,
                right = if (hasRight) rightIdx else null,
                hunkIndex = null,
                type = RowType.CONTEXT,
            )
        )
        if (hasLeft) leftIdx++
        if (hasRight) rightIdx++
    }

    return rows
}
