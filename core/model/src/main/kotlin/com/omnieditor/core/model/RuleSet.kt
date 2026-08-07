package com.omnieditor.core.model

import kotlinx.serialization.Serializable

/**
 * Normalisation applied before hashing, so ignored differences never become hunks
 * (OE-ENG-5). Property held by tests: enabling any rule never increases hunk count.
 */
@Serializable
data class RuleSet(
    val ignoreCase: Boolean = false,
    val whitespace: WhitespaceRule = WhitespaceRule.NONE,
    val ignoreBlankLines: Boolean = false,
    val ignoreLineEndings: Boolean = true,
    val linePatterns: List<LinePattern> = emptyList(),
    val betweenMarkers: List<MarkerPair> = emptyList(),
    val headSkip: Int = 0,
    val tailSkip: Int = 0,
    val columnRanges: List<ColumnRange> = emptyList(),
    val granularity: Granularity = Granularity.WORD,
) {
    companion object { val DEFAULT = RuleSet() }
}

enum class WhitespaceRule { NONE, TRAILING, LEADING_AND_TRAILING, ALL }
enum class Granularity { LINE, WORD, CHARACTER }

@Serializable
data class LinePattern(val match: MatchPosition, val text: String, val caseSensitive: Boolean = true)

enum class MatchPosition { BEGINS_WITH, CONTAINS, ENDS_WITH }

@Serializable
data class MarkerPair(val start: String, val end: String)

/** 1-based, inclusive. [compare] false means the range is ignored instead. */
@Serializable
data class ColumnRange(val from: Int, val to: Int, val compare: Boolean = true) {
    init { require(from in 1..to) { "Column range must be 1-based with from <= to" } }
}
