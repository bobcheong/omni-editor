package com.omnieditor.core.diff

/**
 * F-11: Find the matching bracket for a given position.
 *
 * Supports `()`, `{}`, `[]`. Handles nesting correctly.
 */
object BracketMatcher {

    private val OPEN_TO_CLOSE = mapOf('(' to ')', '{' to '}', '[' to ']')
    private val CLOSE_TO_OPEN = mapOf(')' to '(', '}' to '{', ']' to '[')

    /**
     * Find the position of the matching bracket.
     *
     * @param text the full text to search in
     * @param position the 0-based position of the bracket character to match
     * @return the position of the matching bracket, or null if [position] is not a
     *   bracket or the bracket is unmatched
     */
    fun findMatch(text: String, position: Int): Int? {
        if (position < 0 || position >= text.length) return null
        return when (val ch = text[position]) {
            in OPEN_TO_CLOSE -> findForward(text, position, ch, OPEN_TO_CLOSE.getValue(ch))
            in CLOSE_TO_OPEN -> findBackward(text, position, ch, CLOSE_TO_OPEN.getValue(ch))
            else -> null
        }
    }

    private fun findForward(text: String, start: Int, open: Char, close: Char): Int? {
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                open  -> depth++
                close -> { depth--; if (depth == 0) return i }
            }
        }
        return null
    }

    private fun findBackward(text: String, start: Int, close: Char, open: Char): Int? {
        var depth = 0
        for (i in start downTo 0) {
            when (text[i]) {
                close -> depth++
                open  -> { depth--; if (depth == 0) return i }
            }
        }
        return null
    }
}
