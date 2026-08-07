package com.omnieditor.core.model

/**
 * A pair of changed lines for intra-line diff computation (OE-ENG-1).
 * Computed lazily per visible row in T-08.
 */
data class LinePair(
    val leftLine: Long,
    val rightLine: Long,
    val leftText: CharSequence,
    val rightText: CharSequence,
)

/**
 * A character range within a single line, marking a word-level or character-level change.
 * Offsets are 0-based, half-open: [start, end).
 */
data class IntraLineRange(
    val start: Int,
    val end: Int,
    val type: HunkType,
) {
    init {
        require(start in 0..end) { "Range must satisfy 0 <= start <= end, got [$start, $end)" }
    }
}
