package com.omnieditor.core.diff

import com.omnieditor.core.model.ColumnRange
import com.omnieditor.core.model.LinePattern
import com.omnieditor.core.model.MarkerPair
import com.omnieditor.core.model.MatchPosition
import com.omnieditor.core.model.RuleSet
import com.omnieditor.core.model.WhitespaceRule

/**
 * Normalises line content before hashing (OE-ENG-5).
 *
 * Applied to the hash input only — the original text is preserved for display.
 * The property that must hold: enabling any rule never increases hunk count.
 *
 * Each normalisation is applied in order:
 * 1. Line terminators stripped (always, before anything else)
 * 2. Head/tail skip (lines outside the range → empty)
 * 3. Line pattern exclusion (matching lines → empty)
 * 4. Between-markers exclusion (content between markers → removed)
 * 5. Column ranges (extract or exclude column spans)
 * 6. Whitespace normalisation
 * 7. Case folding
 *
 * A line normalised to empty is treated as a blank line. If ignoreBlankLines
 * is on, blank lines are further collapsed (they hash to the same value as
 * any other blank line, and consecutive blanks are elided by the diff engine).
 */
object Normaliser {

    /**
     * Normalise a single line's content for hashing.
     *
     * @param line        the raw line text (without line terminator)
     * @param lineIndex   0-based line number in the file
     * @param totalLines  total line count in the file
     * @param rules       the active rule set
     * @param inMarker    whether this line is inside a between-markers region
     * @return the normalised text for hashing, and whether we're now in a marker region
     */
    fun normaliseLine(
        line: CharSequence,
        lineIndex: Long,
        totalLines: Long,
        rules: RuleSet,
        inMarker: Boolean = false,
    ): NormaliseResult {
        var text = line.toString()
        var currentlyInMarker = inMarker

        // 1. Strip line terminators (always)
        text = text.trimEnd('\r', '\n')

        // 2. Head/tail skip
        if (rules.headSkip > 0 && lineIndex < rules.headSkip) {
            return NormaliseResult("", currentlyInMarker, skipped = true)
        }
        if (rules.tailSkip > 0 && lineIndex >= totalLines - rules.tailSkip) {
            return NormaliseResult("", currentlyInMarker, skipped = true)
        }

        // 3. Line pattern exclusion
        if (matchesAnyPattern(text, rules.linePatterns)) {
            return NormaliseResult("", currentlyInMarker, skipped = true)
        }

        // 4. Between-markers exclusion
        val markerResult = applyBetweenMarkers(text, rules.betweenMarkers, currentlyInMarker)
        text = markerResult.text
        currentlyInMarker = markerResult.inMarker

        // 5. Column ranges
        if (rules.columnRanges.isNotEmpty()) {
            text = applyColumnRanges(text, rules.columnRanges)
        }

        // 6. Whitespace normalisation
        text = normaliseWhitespace(text, rules.whitespace)

        // 7. Case folding
        if (rules.ignoreCase) {
            text = text.lowercase()
        }

        return NormaliseResult(text, currentlyInMarker, skipped = false)
    }

    /**
     * Hash an entire file's lines with normalisation applied.
     * Returns normalised hashes and a blank-line mask for the ignore-blank-lines rule.
     */
    fun normaliseHashes(
        lineCount: Long,
        lineReader: (Long) -> CharSequence,
        lineHasher: (String) -> Long,
        rules: RuleSet,
    ): NormalisedFile {
        val hashes = LongArray(lineCount.toInt())
        val isBlank = BooleanArray(lineCount.toInt())
        var inMarker = false

        for (i in 0 until lineCount.toInt()) {
            val line = lineReader(i.toLong())
            val result = normaliseLine(line, i.toLong(), lineCount, rules, inMarker)
            inMarker = result.inMarker

            val normText = result.text
            hashes[i] = lineHasher(normText)
            isBlank[i] = result.skipped || normText.isEmpty()
        }

        return NormalisedFile(hashes, isBlank)
    }

    internal fun matchesAnyPattern(text: String, patterns: List<LinePattern>): Boolean {
        for (p in patterns) {
            val haystack = if (p.caseSensitive) text else text.lowercase()
            val needle = if (p.caseSensitive) p.text else p.text.lowercase()
            val matches = when (p.match) {
                MatchPosition.BEGINS_WITH -> haystack.startsWith(needle)
                MatchPosition.CONTAINS -> haystack.contains(needle)
                MatchPosition.ENDS_WITH -> haystack.endsWith(needle)
            }
            if (matches) return true
        }
        return false
    }

    internal fun applyBetweenMarkers(
        text: String,
        markers: List<MarkerPair>,
        inMarker: Boolean,
    ): MarkerState {
        if (markers.isEmpty()) return MarkerState(text, inMarker)

        var result = text
        var currently = inMarker

        for (marker in markers) {
            if (currently) {
                // We're inside a marker region — look for the end marker
                val endIdx = result.indexOf(marker.end)
                if (endIdx >= 0) {
                    // Remove everything up to and including the end marker
                    result = result.substring(endIdx + marker.end.length)
                    currently = false
                } else {
                    // Entire line is inside the marker region
                    return MarkerState("", true)
                }
            }

            // Look for start markers in the (remaining) text
            var searchFrom = 0
            while (searchFrom < result.length) {
                val startIdx = result.indexOf(marker.start, searchFrom)
                if (startIdx < 0) break

                val afterStart = startIdx + marker.start.length
                val endIdx = result.indexOf(marker.end, afterStart)
                if (endIdx >= 0) {
                    // Both markers on this line — remove the region
                    result = result.substring(0, startIdx) + result.substring(endIdx + marker.end.length)
                    searchFrom = startIdx
                } else {
                    // Start marker found but no end — rest of line (and subsequent lines) is excluded
                    result = result.substring(0, startIdx)
                    currently = true
                    break
                }
            }
        }

        return MarkerState(result, currently)
    }

    internal fun applyColumnRanges(text: String, ranges: List<ColumnRange>): String {
        if (text.isEmpty()) return text

        // Determine which columns to keep
        val compareRanges = ranges.filter { it.compare }
        val ignoreRanges = ranges.filter { !it.compare }

        if (compareRanges.isNotEmpty()) {
            // Only compare these ranges — extract them
            val sb = StringBuilder()
            for (range in compareRanges) {
                val from = (range.from - 1).coerceAtLeast(0) // 1-based to 0-based
                val to = range.to.coerceAtMost(text.length)
                if (from < text.length) {
                    sb.append(text, from, to.coerceAtMost(text.length))
                }
            }
            return sb.toString()
        }

        if (ignoreRanges.isNotEmpty()) {
            // Remove these ranges
            val keep = BooleanArray(text.length) { true }
            for (range in ignoreRanges) {
                val from = (range.from - 1).coerceAtLeast(0)
                val to = range.to.coerceAtMost(text.length)
                for (i in from until to) {
                    keep[i] = false
                }
            }
            return text.filterIndexed { i, _ -> keep[i] }
        }

        return text
    }

    internal fun normaliseWhitespace(text: String, rule: WhitespaceRule): String {
        return when (rule) {
            WhitespaceRule.NONE -> text
            WhitespaceRule.TRAILING -> text.trimEnd()
            WhitespaceRule.LEADING_AND_TRAILING -> text.trim()
            WhitespaceRule.ALL -> text.replace(Regex("\\s+"), "")
        }
    }
}

data class NormaliseResult(
    val text: String,
    val inMarker: Boolean,
    val skipped: Boolean,
)

data class MarkerState(
    val text: String,
    val inMarker: Boolean,
)

data class NormalisedFile(
    val hashes: LongArray,
    val isBlank: BooleanArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NormalisedFile) return false
        return hashes.contentEquals(other.hashes) && isBlank.contentEquals(other.isBlank)
    }

    override fun hashCode(): Int = hashes.contentHashCode() * 31 + isBlank.contentHashCode()
}
