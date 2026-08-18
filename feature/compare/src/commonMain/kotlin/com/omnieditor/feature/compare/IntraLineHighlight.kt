package com.omnieditor.feature.compare

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.omnieditor.core.diff.IntraLineDiff
import com.omnieditor.core.model.Granularity
import com.omnieditor.core.model.IntraLineRange
import com.omnieditor.core.model.LinePair

/**
 * Rendering utilities for intra-line character/word-level highlighting (R-26).
 *
 * Converts [IntraLineRange] lists produced by [IntraLineDiff] into [AnnotatedString]
 * values with background-colour spans. Granularity comes from the active [RuleSet]:
 *
 * - CHARACTER: character-level spans
 * - WORD:      word-level spans (default)
 * - LINE:      no intra-line spans (whole line is the unit of change)
 */

/**
 * Apply background-colour spans to [text] at every position in [ranges].
 *
 * Ranges are half-open [start, end) as produced by [IntraLineDiff]. Spans that
 * extend beyond the string are clamped silently so a stale cached result never
 * throws [StringIndexOutOfBoundsException].
 */
fun highlightIntraLine(
    text: String,
    ranges: List<IntraLineRange>,
    highlightColor: Color,
): AnnotatedString {
    if (ranges.isEmpty() || text.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (range in ranges) {
            val start = range.start.coerceIn(0, text.length)
            val end = range.end.coerceIn(start, text.length)
            if (start < end) {
                addStyle(
                    style = SpanStyle(background = highlightColor),
                    start = start,
                    end = end,
                )
            }
        }
    }
}

/**
 * Compute and cache intra-line diff results for CHANGED row pairs.
 *
 * The cache is keyed by a [Long] pair encoded as `(leftLineIdx shl 32) or rightLineIdx`,
 * which is unique per pair for files under 2^31 lines (far beyond realistic use).
 * The cache is cleared externally whenever the compare result changes (see [CompareState]).
 */
class IntraLineCache {

    private val cache = HashMap<Long, IntraLineDiff.IntraLineResult>()

    /** Compute (or return cached) intra-line ranges for the given line pair at [granularity]. */
    fun get(
        leftText: String,
        rightText: String,
        leftLineIdx: Long,
        rightLineIdx: Long,
        granularity: Granularity,
    ): IntraLineDiff.IntraLineResult {
        if (granularity == Granularity.LINE) {
            // Whole line is the unit — no per-character spans needed; return empty.
            return IntraLineDiff.IntraLineResult(emptyList(), emptyList())
        }
        val key = (leftLineIdx shl 32) or (rightLineIdx and 0xFFFFFFFFL)
        return cache.getOrPut(key) {
            IntraLineDiff.compute(
                LinePair(
                    leftLine = leftLineIdx,
                    rightLine = rightLineIdx,
                    leftText = leftText,
                    rightText = rightText,
                ),
                granularity,
            )
        }
    }

    /** Discard all cached results (call when the compare result is replaced). */
    fun clear() {
        cache.clear()
    }
}
