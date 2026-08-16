package com.omnieditor.core.diff

import com.omnieditor.core.model.Granularity
import com.omnieditor.core.model.LinePair

/**
 * F-10: Word-level merge within a changed line pair (OE-MRG-2).
 *
 * Uses [IntraLineDiff] to find changed ranges, then constructs a merged
 * line by selecting from left or right per changed range.
 */
object WordMerge {

    enum class Side { LEFT, RIGHT }

    /**
     * Merge a changed line pair by selecting left or right for each changed range.
     *
     * @param leftLine the left version of the line
     * @param rightLine the right version of the line
     * @param granularity WORD or CHARACTER level
     * @param selections one [Side] per changed range — which version to take.
     *   If fewer selections than changed ranges, remaining default to LEFT.
     * @return the merged line
     */
    fun merge(
        leftLine: String,
        rightLine: String,
        granularity: Granularity = Granularity.WORD,
        selections: List<Side> = emptyList(),
    ): String {
        val pair = LinePair(0L, 0L, leftLine, rightLine)
        val diff = IntraLineDiff.compute(pair, granularity)

        if (diff.leftRanges.isEmpty()) return leftLine // no changes

        // Build merged line: start with left, replace changed ranges per selection.
        // Ranges are processed left-to-right; track cumulative offset after each replacement.
        val result = StringBuilder(leftLine)
        var offset = 0
        val leftRanges = diff.leftRanges
        val rightRanges = diff.rightRanges

        val rangeCount = minOf(leftRanges.size, rightRanges.size)
        for (i in 0 until rangeCount) {
            val side = selections.getOrElse(i) { Side.LEFT }
            if (side == Side.RIGHT) {
                val leftRange = leftRanges[i]
                val rightRange = rightRanges[i]
                val replacement = rightLine.substring(rightRange.start, rightRange.end)
                val adjStart = leftRange.start + offset
                val adjEnd = leftRange.end + offset
                result.replace(adjStart, adjEnd, replacement)
                offset += replacement.length - (leftRange.end - leftRange.start)
            }
        }
        return result.toString()
    }
}
