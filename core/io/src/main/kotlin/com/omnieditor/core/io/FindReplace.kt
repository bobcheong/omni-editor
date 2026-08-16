package com.omnieditor.core.io

/**
 * Find and replace engine operating on a [TextDocument] (OE-EDT-4, OE-FND-1).
 *
 * Supports case sensitivity, whole word, regex with capture groups,
 * replace-all with count, and find-in-selection.
 *
 * Replace-all is a single undo step — all replacements are applied as
 * one compound edit so undo restores the pre-replace state in one action.
 */
object FindReplace {

    data class FindOptions(
        val caseSensitive: Boolean = false,
        val wholeWord: Boolean = false,
        val regex: Boolean = false,
    )

    data class Match(
        val line: Long,
        val startColumn: Int,
        val endColumn: Int,
        val text: String,
    )

    data class FindResult(
        val matches: List<Match>,
        val totalCount: Int,
    )

    data class ReplaceAllResult(
        val replacementCount: Int,
    )

    /**
     * Find all matches in the document.
     *
     * @param document     the document to search
     * @param pattern      the search pattern
     * @param options      case, whole word, regex options
     * @param selectionOnly if non-null, restrict search to this line range (inclusive)
     */
    fun findAll(
        document: TextDocument,
        pattern: String,
        options: FindOptions = FindOptions(),
        selectionOnly: LongRange? = null,
    ): FindResult {
        if (pattern.isEmpty()) return FindResult(emptyList(), 0)

        val matches = mutableListOf<Match>()
        val startLine = selectionOnly?.first ?: 0L
        val endLine = selectionOnly?.last ?: (document.lineCount - 1)

        val compiledRegex = if (options.regex) {
            compileRegex(pattern, options)
        } else null

        for (lineIdx in startLine..endLine) {
            val lineText = document.line(lineIdx).toString()
            val lineMatches = findInLine(lineText, pattern, options, compiledRegex)
            for ((start, end, matchText) in lineMatches) {
                matches.add(Match(lineIdx, start, end, matchText))
            }
        }

        return FindResult(matches, matches.size)
    }

    /**
     * Replace all occurrences. This is a single undo step.
     *
     * Replaces from bottom to top so that line/column indices remain valid
     * as earlier content shifts.
     */
    fun replaceAll(
        table: PieceTable,
        pattern: String,
        replacement: String,
        options: FindOptions = FindOptions(),
        lineCount: Long,
        lineReader: (Long) -> CharSequence,
        selectionOnly: LongRange? = null,
    ): ReplaceAllResult {
        if (pattern.isEmpty()) return ReplaceAllResult(0)

        val compiledRegex = if (options.regex) compileRegex(pattern, options) else null

        // Collect all matches first
        data class MatchPos(val line: Long, val start: Int, val end: Int, val matchText: String)
        val allMatches = mutableListOf<MatchPos>()

        val startLine = selectionOnly?.first ?: 0L
        val endLine = selectionOnly?.last ?: (lineCount - 1)

        for (lineIdx in startLine..endLine) {
            val lineText = lineReader(lineIdx).toString()
            val lineMatches = findInLine(lineText, pattern, options, compiledRegex)
            for ((start, end, matchText) in lineMatches) {
                allMatches.add(MatchPos(lineIdx, start, end, matchText))
            }
        }

        if (allMatches.isEmpty()) return ReplaceAllResult(0)

        // Apply replacements from bottom-right to top-left to preserve positions
        allMatches.sortWith(compareByDescending<MatchPos> { it.line }.thenByDescending { it.start })

        for (match in allMatches) {
            val lineText = lineReader(match.line).toString()
            val actualReplacement = if (options.regex && compiledRegex != null) {
                computeRegexReplacement(match.matchText, compiledRegex, replacement)
            } else {
                replacement
            }
            // Calculate the character offset within the piece table
            val offset = lineOffset(table, match.line, lineReader) + match.start
            val count = match.end - match.start
            table.replace(offset, count, actualReplacement)
        }

        return ReplaceAllResult(allMatches.size)
    }

    /**
     * Apply [matches] replacements to [fullText], returning the modified string.
     *
     * Intended for use with [replaceAll] via the public TextDocument API: callers
     * first collect matches with [findAll], then produce the new content here,
     * then write it back as a single undo step via [TextDocument.replaceAll].
     */
    fun replaceAllInText(
        fullText: String,
        matches: List<Match>,
        replacement: String,
        options: FindOptions = FindOptions(),
    ): String {
        if (matches.isEmpty()) return fullText

        // Build offset-based replacements by computing character offsets per match.
        // Matches are already in top-to-bottom order; process right-to-left so offsets stay valid.
        val lines = fullText.split('\n')

        // Compute cumulative line start offsets
        val lineStarts = IntArray(lines.size)
        var acc = 0
        for (i in lines.indices) {
            lineStarts[i] = acc
            acc += lines[i].length + 1 // +1 for the '\n'
        }

        // Sort matches from last to first (bottom-right to top-left)
        val sorted = matches.sortedWith(
            compareByDescending<Match> { it.line }.thenByDescending { it.startColumn }
        )

        val sb = StringBuilder(fullText)
        val compiledRegex = if (options.regex) {
            val regexOptions = if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            Regex(matches.firstOrNull()?.text ?: "", regexOptions)
        } else null

        for (match in sorted) {
            val lineIdx = match.line.toInt()
            if (lineIdx >= lineStarts.size) continue
            val charStart = lineStarts[lineIdx] + match.startColumn
            val charEnd = lineStarts[lineIdx] + match.endColumn
            val actualReplacement = if (options.regex && compiledRegex != null) {
                compiledRegex.replaceFirst(match.text, replacement)
            } else {
                replacement
            }
            sb.replace(charStart, charEnd, actualReplacement)
        }

        return sb.toString()
    }

    /**
     * Find all matches across a plain list of strings (e.g. compare side lines).
     *
     * Returns one [Match] per occurrence; [Match.line] is the 0-based index into [lines].
     * Invalid regex patterns return an empty list.
     */
    fun findAllInLines(
        lines: List<String>,
        query: String,
        options: FindOptions = FindOptions(),
    ): List<Match> {
        if (query.isEmpty()) return emptyList()
        val compiledRegex: Regex? = if (options.regex) {
            try {
                val regexOptions = if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                Regex(query, regexOptions)
            } catch (_: Exception) {
                return emptyList()
            }
        } else null
        val matches = mutableListOf<Match>()
        for ((lineIdx, line) in lines.withIndex()) {
            val lineMatches = findInLine(line, query, options, compiledRegex)
            for ((start, end, matchText) in lineMatches) {
                matches.add(Match(lineIdx.toLong(), start, end, matchText))
            }
        }
        return matches
    }

    // ── Internal helpers ──

    internal data class LineMatch(val start: Int, val end: Int, val text: String)

    internal fun findInLine(
        lineText: String,
        pattern: String,
        options: FindOptions,
        compiledRegex: Regex? = null,
    ): List<LineMatch> {
        if (options.regex && compiledRegex != null) {
            return compiledRegex.findAll(lineText).map {
                LineMatch(it.range.first, it.range.last + 1, it.value)
            }.toList()
        }

        val matches = mutableListOf<LineMatch>()
        val haystack = if (options.caseSensitive) lineText else lineText.lowercase()
        val needle = if (options.caseSensitive) pattern else pattern.lowercase()

        var searchFrom = 0
        while (searchFrom <= haystack.length - needle.length) {
            val idx = haystack.indexOf(needle, searchFrom)
            if (idx < 0) break

            if (options.wholeWord && !isWholeWord(haystack, idx, needle.length)) {
                searchFrom = idx + 1
                continue
            }

            // Use the original text for the match (preserving case)
            matches.add(LineMatch(idx, idx + needle.length, lineText.substring(idx, idx + needle.length)))
            searchFrom = idx + needle.length
        }

        return matches
    }

    private fun isWholeWord(text: String, start: Int, length: Int): Boolean {
        val before = if (start > 0) text[start - 1] else ' '
        val after = if (start + length < text.length) text[start + length] else ' '
        return !before.isLetterOrDigit() && before != '_' &&
            !after.isLetterOrDigit() && after != '_'
    }

    private fun compileRegex(pattern: String, options: FindOptions): Regex {
        val regexOptions = if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return Regex(pattern, regexOptions)
    }

    private fun computeRegexReplacement(matchText: String, regex: Regex, replacement: String): String {
        // Use Kotlin's built-in regex replacement which handles $1, $2 etc. correctly
        return regex.replaceFirst(matchText, replacement)
    }

    private fun lineOffset(table: PieceTable, lineIdx: Long, lineReader: (Long) -> CharSequence): Int {
        var offset = 0
        for (i in 0 until lineIdx) {
            offset += lineReader(i).length + 1 // +1 for newline
        }
        return offset
    }
}
