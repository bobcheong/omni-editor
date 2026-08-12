package com.omnieditor.feature.editor

/**
 * Expand tab characters to spaces, aligning to tab stops at multiples of [tabWidth].
 *
 * Each tab is replaced by enough spaces to reach the next tab-stop column.
 */
fun expandTabs(text: CharSequence, tabWidth: Int): String {
    if (tabWidth <= 0) return text.toString().replace("\t", "")
    val sb = StringBuilder(text.length + text.count { it == '\t' } * (tabWidth - 1))
    var column = 0
    for (char in text) {
        if (char == '\t') {
            val spaces = tabWidth - (column % tabWidth)
            repeat(spaces) { sb.append(' ') }
            column += spaces
        } else {
            sb.append(char)
            column++
        }
    }
    return sb.toString()
}

/**
 * Build a mapping from display-offset (in tab-expanded text) to character-offset
 * (in original text).
 *
 * For a tab that expands to N spaces, all N display positions map to the tab's
 * character index.
 *
 * The returned array has length equal to the expanded text length + 1.
 * `result[expandedLength]` maps to `originalLength` (end sentinel).
 */
fun buildDisplayToCharMap(text: CharSequence, tabWidth: Int): IntArray {
    if (tabWidth <= 0) {
        // Degenerate: tabs collapse to nothing, non-tab chars 1:1
        val result = IntArray(text.count { it != '\t' } + 1)
        var di = 0
        var ci = 0
        for (char in text) {
            if (char != '\t') {
                result[di] = ci
                di++
            }
            ci++
        }
        result[di] = ci
        return result
    }

    // First pass: compute expanded length
    var expandedLen = 0
    var column = 0
    for (char in text) {
        if (char == '\t') {
            val spaces = tabWidth - (column % tabWidth)
            expandedLen += spaces
            column += spaces
        } else {
            expandedLen++
            column++
        }
    }

    val result = IntArray(expandedLen + 1)
    var di = 0
    column = 0
    for ((ci, char) in text.withIndex()) {
        if (char == '\t') {
            val spaces = tabWidth - (column % tabWidth)
            repeat(spaces) {
                result[di] = ci
                di++
            }
            column += spaces
        } else {
            result[di] = ci
            di++
            column++
        }
    }
    result[di] = text.length // end sentinel
    return result
}

/**
 * Build a mapping from character-offset (in original text) to display-offset
 * (first column of the expanded representation).
 *
 * The returned array has length equal to `text.length + 1`.
 * `result[text.length]` gives the total display width (end sentinel).
 */
fun buildCharToDisplayMap(text: CharSequence, tabWidth: Int): IntArray {
    val result = IntArray(text.length + 1)
    var displayColumn = 0
    for ((ci, char) in text.withIndex()) {
        result[ci] = displayColumn
        if (char == '\t') {
            val spaces = if (tabWidth > 0) tabWidth - (displayColumn % tabWidth) else 0
            displayColumn += spaces
        } else {
            displayColumn++
        }
    }
    result[text.length] = displayColumn // end sentinel
    return result
}
