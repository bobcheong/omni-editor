package com.omnieditor.core.io

/**
 * Text operations applied to a range of lines (OE-EDT-7).
 *
 * Each operation takes lines in and returns transformed lines.
 * The caller wraps the result as a single undo step in the piece table.
 */
object TextTools {

    /** Sort lines alphabetically. */
    fun sortLines(lines: List<String>, descending: Boolean = false, caseSensitive: Boolean = true): List<String> {
        val comparator = if (caseSensitive) {
            compareBy<String> { it }
        } else {
            compareBy { it.lowercase() }
        }
        return if (descending) lines.sortedWith(comparator.reversed()) else lines.sortedWith(comparator)
    }

    /** Remove duplicate lines, preserving first occurrence order. */
    fun deduplicateLines(lines: List<String>, caseSensitive: Boolean = true): List<String> {
        val seen = LinkedHashSet<String>()
        return lines.filter {
            val key = if (caseSensitive) it else it.lowercase()
            seen.add(key)
        }
    }

    /** Trim trailing whitespace from each line. */
    fun trimTrailingWhitespace(lines: List<String>): List<String> {
        return lines.map { it.trimEnd() }
    }

    /** Trim leading whitespace from each line. */
    fun trimLeadingWhitespace(lines: List<String>): List<String> {
        return lines.map { it.trimStart() }
    }

    /** Trim both leading and trailing whitespace. */
    fun trimWhitespace(lines: List<String>): List<String> {
        return lines.map { it.trim() }
    }

    /** Convert to UPPERCASE. */
    fun toUpperCase(text: String): String = text.uppercase()

    /** Convert to lowercase. */
    fun toLowerCase(text: String): String = text.lowercase()

    /** Convert to Title Case. */
    fun toTitleCase(text: String): String {
        return text.split(Regex("(?<=\\s)|(?=\\s)")).joinToString("") { word ->
            if (word.isBlank()) word
            else word[0].uppercase() + word.substring(1).lowercase()
        }
    }

    /** Toggle case of each character. */
    fun toggleCase(text: String): String {
        return text.map { ch ->
            if (ch.isUpperCase()) ch.lowercase().first()
            else if (ch.isLowerCase()) ch.uppercase().first()
            else ch
        }.joinToString("")
    }

    /** Convert tabs to spaces. */
    fun tabsToSpaces(lines: List<String>, tabWidth: Int = 4): List<String> {
        val spaces = " ".repeat(tabWidth)
        return lines.map { it.replace("\t", spaces) }
    }

    /** Convert leading spaces to tabs. */
    fun spacesToTabs(lines: List<String>, tabWidth: Int = 4): List<String> {
        val spaces = " ".repeat(tabWidth)
        return lines.map { line ->
            val leadingSpaces = line.length - line.trimStart(' ').length
            val tabs = leadingSpaces / tabWidth
            val remainder = leadingSpaces % tabWidth
            "\t".repeat(tabs) + " ".repeat(remainder) + line.trimStart(' ')
        }
    }

    /** Convert line endings. */
    fun convertLineEndings(text: String, to: String): String {
        // Normalise all line endings to LF first, then convert
        val normalised = text.replace("\r\n", "\n").replace("\r", "\n")
        return when (to) {
            "\r\n" -> normalised.replace("\n", "\r\n")
            "\r" -> normalised.replace("\n", "\r")
            else -> normalised
        }
    }

    /** Join selected lines into one line with a separator. */
    fun joinLines(lines: List<String>, separator: String = " "): String {
        return lines.joinToString(separator)
    }

    /** Split a line at a delimiter into multiple lines. */
    fun splitLine(line: String, delimiter: String): List<String> {
        if (delimiter.isEmpty()) return listOf(line)
        return line.split(delimiter)
    }

    /** Insert a timestamp at the current position. */
    fun timestamp(): String {
        val now = java.time.LocalDateTime.now()
        return java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(now)
    }

    /** Reverse the order of lines. */
    fun reverseLines(lines: List<String>): List<String> = lines.reversed()

    /** Number lines with a prefix. */
    fun numberLines(lines: List<String>, startAt: Int = 1, separator: String = ": "): List<String> {
        val width = (startAt + lines.size - 1).toString().length
        return lines.mapIndexed { i, line ->
            "${(startAt + i).toString().padStart(width)}$separator$line"
        }
    }

    /** Remove empty/blank lines. */
    fun removeBlankLines(lines: List<String>): List<String> {
        return lines.filter { it.isNotBlank() }
    }

    /**
     * Encode/decode text between charsets.
     * Returns the text re-encoded, or null if the conversion fails.
     */
    fun convertEncoding(text: String, fromCharset: String, toCharset: String): String? {
        return try {
            val bytes = text.toByteArray(charset(fromCharset))
            String(bytes, charset(toCharset))
        } catch (_: Exception) {
            null
        }
    }
}
