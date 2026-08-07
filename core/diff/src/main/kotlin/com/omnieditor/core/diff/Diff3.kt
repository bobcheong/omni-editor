package com.omnieditor.core.diff

import com.omnieditor.core.model.Hunk
import com.omnieditor.core.model.HunkType
import com.omnieditor.core.model.RuleSet

/**
 * 3-way diff against a common base for merge conflict detection (OE-ENG-2).
 *
 * Compares left and right independently against the base, then classifies
 * each change region:
 * - LEFT_ONLY: only the left side changed → auto-mergeable
 * - RIGHT_ONLY: only the right side changed → auto-mergeable
 * - BOTH_SAME: both sides made the same change → auto-mergeable
 * - CONFLICT: both sides changed the same region differently → needs resolution
 */
object Diff3 {

    /**
     * A region in the 3-way merge result.
     */
    data class Region(
        val type: RegionType,
        val baseStart: Long,
        val baseEnd: Long,
        val leftStart: Long,
        val leftEnd: Long,
        val rightStart: Long,
        val rightEnd: Long,
    )

    enum class RegionType {
        /** No changes — all three sides agree. */
        UNCHANGED,
        /** Only the left side changed relative to base. */
        LEFT_ONLY,
        /** Only the right side changed relative to base. */
        RIGHT_ONLY,
        /** Both sides made the same change. */
        BOTH_SAME,
        /** Both sides changed the same region differently — conflict. */
        CONFLICT,
    }

    data class Diff3Result(
        val regions: List<Region>,
        val hasConflicts: Boolean,
        val conflictCount: Int,
    )

    /**
     * Perform a 3-way diff.
     *
     * @param baseLines   lines from the common ancestor
     * @param leftLines   lines from the left side
     * @param rightLines  lines from the right side
     * @param rules       normalisation rules
     */
    suspend fun diff3(
        baseLines: List<String>,
        leftLines: List<String>,
        rightLines: List<String>,
        rules: RuleSet = RuleSet.DEFAULT,
    ): Diff3Result {
        // Diff base→left and base→right independently
        val leftHunks = DiffEngine.compare(
            leftLineCount = baseLines.size.toLong(),
            rightLineCount = leftLines.size.toLong(),
            leftLine = { baseLines[it.toInt()] },
            rightLine = { leftLines[it.toInt()] },
            rules = rules,
        ).hunks

        val rightHunks = DiffEngine.compare(
            leftLineCount = baseLines.size.toLong(),
            rightLineCount = rightLines.size.toLong(),
            leftLine = { baseLines[it.toInt()] },
            rightLine = { rightLines[it.toInt()] },
            rules = rules,
        ).hunks

        // Merge the two edit streams to classify regions
        return classifyRegions(
            baseLines, leftLines, rightLines,
            leftHunks, rightHunks,
        )
    }

    private fun classifyRegions(
        baseLines: List<String>,
        leftLines: List<String>,
        rightLines: List<String>,
        leftHunks: List<Hunk>,
        rightHunks: List<Hunk>,
    ): Diff3Result {
        val regions = mutableListOf<Region>()
        var basePos = 0L
        var leftPos = 0L
        var rightPos = 0L
        var li = 0 // left hunk index
        var ri = 0 // right hunk index
        val baseLen = baseLines.size.toLong()

        while (basePos < baseLen || li < leftHunks.size || ri < rightHunks.size) {
            val leftHunk = if (li < leftHunks.size) leftHunks[li] else null
            val rightHunk = if (ri < rightHunks.size) rightHunks[ri] else null

            // Both hunks reference base positions via leftStart/leftEnd (since base was the "left" in each diff)
            val leftBase = leftHunk?.leftStart ?: baseLen
            val rightBase = rightHunk?.leftStart ?: baseLen

            // Add unchanged region before the next hunk
            val nextChange = minOf(leftBase, rightBase)
            if (nextChange > basePos) {
                val span = nextChange - basePos
                regions.add(
                    Region(
                        RegionType.UNCHANGED,
                        baseStart = basePos, baseEnd = nextChange,
                        leftStart = leftPos, leftEnd = leftPos + span,
                        rightStart = rightPos, rightEnd = rightPos + span,
                    )
                )
                leftPos += span
                rightPos += span
                basePos = nextChange
            }

            if (leftHunk == null && rightHunk == null) break

            // Determine if the hunks overlap in the base
            if (leftHunk != null && rightHunk != null && overlaps(leftHunk, rightHunk)) {
                // Both sides changed an overlapping region
                val mergedBaseStart = minOf(leftHunk.leftStart, rightHunk.leftStart)
                val mergedBaseEnd = maxOf(leftHunk.leftEnd, rightHunk.leftEnd)

                // Compute the left and right spans
                val leftSpanStart = leftPos + (mergedBaseStart - basePos)
                val leftChangeLines = leftHunk.rightEnd - leftHunk.rightStart
                val rightSpanStart = rightPos + (mergedBaseStart - basePos)
                val rightChangeLines = rightHunk.rightEnd - rightHunk.rightStart

                // Adjust for the overlap: we need to figure out positions in left/right files
                val leftContentStart = leftHunk.rightStart
                val leftContentEnd = leftHunk.rightEnd
                val rightContentStart = rightHunk.rightStart
                val rightContentEnd = rightHunk.rightEnd

                // Check if both sides made the same change
                val sameChange = isSameChange(
                    leftLines, leftContentStart, leftContentEnd,
                    rightLines, rightContentStart, rightContentEnd,
                )

                val regionType = if (sameChange) RegionType.BOTH_SAME else RegionType.CONFLICT

                regions.add(
                    Region(
                        regionType,
                        baseStart = mergedBaseStart, baseEnd = mergedBaseEnd,
                        leftStart = leftContentStart, leftEnd = leftContentEnd,
                        rightStart = rightContentStart, rightEnd = rightContentEnd,
                    )
                )

                val baseAdvance = mergedBaseEnd - basePos
                leftPos = leftContentEnd
                rightPos = rightContentEnd
                basePos = mergedBaseEnd

                // Advance hunk pointers past all consumed hunks
                while (li < leftHunks.size && leftHunks[li].leftEnd <= mergedBaseEnd) li++
                while (ri < rightHunks.size && rightHunks[ri].leftEnd <= mergedBaseEnd) ri++

            } else if (leftHunk != null && (rightHunk == null || leftBase <= rightBase)) {
                // Left-only change
                regions.add(
                    Region(
                        RegionType.LEFT_ONLY,
                        baseStart = leftHunk.leftStart, baseEnd = leftHunk.leftEnd,
                        leftStart = leftHunk.rightStart, leftEnd = leftHunk.rightEnd,
                        rightStart = rightPos, rightEnd = rightPos,
                    )
                )
                val baseAdvance = leftHunk.leftEnd - basePos
                rightPos += baseAdvance
                leftPos = leftHunk.rightEnd
                basePos = leftHunk.leftEnd
                li++

            } else if (rightHunk != null) {
                // Right-only change
                regions.add(
                    Region(
                        RegionType.RIGHT_ONLY,
                        baseStart = rightHunk.leftStart, baseEnd = rightHunk.leftEnd,
                        leftStart = leftPos, leftEnd = leftPos,
                        rightStart = rightHunk.rightStart, rightEnd = rightHunk.rightEnd,
                    )
                )
                val baseAdvance = rightHunk.leftEnd - basePos
                leftPos += baseAdvance
                rightPos = rightHunk.rightEnd
                basePos = rightHunk.leftEnd
                ri++
            }
        }

        // Trailing unchanged
        if (basePos < baseLen) {
            val span = baseLen - basePos
            regions.add(
                Region(
                    RegionType.UNCHANGED,
                    baseStart = basePos, baseEnd = baseLen,
                    leftStart = leftPos, leftEnd = leftPos + span,
                    rightStart = rightPos, rightEnd = rightPos + span,
                )
            )
        }

        val conflicts = regions.count { it.type == RegionType.CONFLICT }
        return Diff3Result(
            regions = regions,
            hasConflicts = conflicts > 0,
            conflictCount = conflicts,
        )
    }

    /**
     * Check if two hunks overlap in base coordinates.
     * Hunks overlap if they modify any of the same base lines,
     * or if both are insertions at the same base position (both leftStart == leftEnd).
     */
    private fun overlaps(left: Hunk, right: Hunk): Boolean {
        // Both are insertions at the same point
        if (left.leftStart == left.leftEnd && right.leftStart == right.leftEnd &&
            left.leftStart == right.leftStart
        ) return true
        // Standard overlap check
        return left.leftStart < right.leftEnd && right.leftStart < left.leftEnd
    }

    /**
     * Check if two sides made identical changes.
     */
    private fun isSameChange(
        leftLines: List<String>, leftStart: Long, leftEnd: Long,
        rightLines: List<String>, rightStart: Long, rightEnd: Long,
    ): Boolean {
        val leftCount = leftEnd - leftStart
        val rightCount = rightEnd - rightStart
        if (leftCount != rightCount) return false
        for (i in 0 until leftCount.toInt()) {
            if (leftLines[(leftStart + i).toInt()] != rightLines[(rightStart + i).toInt()]) {
                return false
            }
        }
        return true
    }
}
