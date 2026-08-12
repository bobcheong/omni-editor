package com.omnieditor.feature.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.omnieditor.core.io.PieceTableDocument

/**
 * Selection mode: LINEAR for normal contiguous selection,
 * COLUMN for block/rectangular selection (data model only — behaviour deferred).
 */
enum class SelectionMode { LINEAR, COLUMN }

/**
 * Observable state for the editor composable.
 *
 * Backed by [PieceTableDocument] for content, with Compose-observable
 * caret position, selection, and scroll offset.
 */
@Stable
class EditorState(
    val document: PieceTableDocument,
) {
    /** 0-based caret line. */
    var caretLine by mutableLongStateOf(0L)
        internal set

    /** 0-based caret column (character offset within the line). */
    var caretColumn by mutableStateOf(0)
        internal set

    /** Selection anchor. Null when no selection is active. */
    var selectionAnchorLine by mutableStateOf<Long?>(null)
        internal set
    var selectionAnchorColumn by mutableStateOf<Int?>(null)
        internal set

    /**
     * Selection mode: LINEAR (normal) or COLUMN (block/rectangular).
     * Column selection behaviour is not yet implemented; the data model
     * accommodates it so the field is ready when the feature ships.
     */
    var selectionMode by mutableStateOf(SelectionMode.LINEAR)

    /** First visible line (scroll position). */
    var firstVisibleLine by mutableLongStateOf(0L)
        internal set

    /** Whether the document is in read-only mode. */
    var readOnly by mutableStateOf(false)

    /** Total line count, derived from document. */
    val lineCount: Long get() = document.lineCount

    /** Whether a selection is active. */
    val hasSelection: Boolean
        get() = selectionAnchorLine != null && selectionAnchorColumn != null &&
            (selectionAnchorLine != caretLine || selectionAnchorColumn != caretColumn)

    /** Move caret to the given position, clearing any selection. */
    fun moveCaret(line: Long, column: Int) {
        caretLine = line.coerceIn(0, maxOf(lineCount - 1, 0))
        val lineText = document.line(caretLine)
        caretColumn = column.coerceIn(0, lineText.length)
        clearSelection()
    }

    /** Start or extend a selection from the current caret position. */
    fun startSelection() {
        if (selectionAnchorLine == null) {
            selectionAnchorLine = caretLine
            selectionAnchorColumn = caretColumn
        }
    }

    /** Clear the current selection. */
    fun clearSelection() {
        selectionAnchorLine = null
        selectionAnchorColumn = null
    }

    /** Get the selected text, or empty string if no selection. */
    fun selectedText(): String {
        if (!hasSelection) return ""
        val anchorLine = selectionAnchorLine ?: return ""
        val anchorCol = selectionAnchorColumn ?: return ""

        // Determine start and end
        val (startLine, startCol, endLine, endCol) = if (
            anchorLine < caretLine || (anchorLine == caretLine && anchorCol < caretColumn)
        ) {
            listOf(anchorLine, anchorCol.toLong(), caretLine, caretColumn.toLong())
        } else {
            listOf(caretLine, caretColumn.toLong(), anchorLine, anchorCol.toLong())
        }

        val sb = StringBuilder()
        for (line in startLine..endLine) {
            val lineText = document.line(line)
            val from = if (line == startLine) startCol.toInt() else 0
            val to = if (line == endLine) endCol.toInt().coerceAtMost(lineText.length) else lineText.length
            if (from < lineText.length) {
                sb.append(lineText, from, to.coerceAtMost(lineText.length))
            }
            if (line < endLine) sb.append('\n')
        }
        return sb.toString()
    }

    /** Insert text at the caret position. */
    fun insertAtCaret(text: String) {
        if (readOnly || text.isEmpty()) return
        // If there's a selection, delete it first
        if (hasSelection) {
            deleteSelection()
        }
        document.edit(caretLine..caretLine, buildReplacementForInsert(text))
        // Advance caret
        val newLines = text.count { it == '\n' }
        if (newLines > 0) {
            caretLine += newLines
            caretColumn = text.length - text.lastIndexOf('\n') - 1
        } else {
            caretColumn += text.length
        }
    }

    /** Delete the current selection. */
    fun deleteSelection() {
        if (!hasSelection || readOnly) return
        val anchorLine = selectionAnchorLine ?: return
        val anchorCol = selectionAnchorColumn ?: return

        val (startLine, startCol, endLine, endCol) = if (
            anchorLine < caretLine || (anchorLine == caretLine && anchorCol < caretColumn)
        ) {
            listOf(anchorLine, anchorCol.toLong(), caretLine, caretColumn.toLong())
        } else {
            listOf(caretLine, caretColumn.toLong(), anchorLine, anchorCol.toLong())
        }

        val prefix = document.line(startLine).substring(0, startCol.toInt())
        val endLineText = document.line(endLine)
        val suffix = endLineText.substring(endCol.toInt().coerceAtMost(endLineText.length))
        document.edit(startLine..endLine, prefix + suffix)
        caretLine = startLine
        caretColumn = startCol.toInt()
        clearSelection()
    }

    /**
     * Move the caret while extending the selection from the current anchor.
     * If no anchor is set, the current caret position becomes the anchor.
     */
    fun moveCaretWithSelection(line: Long, column: Int) {
        startSelection()
        caretLine = line.coerceIn(0, maxOf(lineCount - 1, 0))
        val lineText = document.line(caretLine)
        caretColumn = column.coerceIn(0, lineText.length)
    }

    /** Set selection explicitly (anchor and caret). */
    fun setSelection(
        anchorLine: Long,
        anchorColumn: Int,
        caretLine: Long,
        caretColumn: Int,
    ) {
        selectionAnchorLine = anchorLine
        selectionAnchorColumn = anchorColumn
        this.caretLine = caretLine.coerceIn(0, maxOf(lineCount - 1, 0))
        val lineText = document.line(this.caretLine)
        this.caretColumn = caretColumn.coerceIn(0, lineText.length)
    }

    /**
     * Ordered selection boundaries: (startLine, startCol, endLine, endCol).
     * Returns null when no selection is active.
     */
    fun selectionBounds(): SelectionBounds? {
        if (!hasSelection) return null
        val aLine = selectionAnchorLine ?: return null
        val aCol = selectionAnchorColumn ?: return null
        return if (aLine < caretLine || (aLine == caretLine && aCol < caretColumn)) {
            SelectionBounds(aLine, aCol, caretLine, caretColumn)
        } else {
            SelectionBounds(caretLine, caretColumn, aLine, aCol)
        }
    }

    /**
     * Select the word at the given position. A "word" is a maximal run of
     * alphanumeric/underscore characters. Falls back to selecting the single
     * character at [column] when not inside a word.
     */
    fun selectWordAt(line: Long, column: Int) {
        val clampedLine = line.coerceIn(0, maxOf(lineCount - 1, 0))
        val text = document.line(clampedLine).toString()
        if (text.isEmpty()) {
            moveCaret(clampedLine, 0)
            return
        }
        val col = column.coerceIn(0, text.length - 1)
        val ch = text[col]
        if (ch.isLetterOrDigit() || ch == '_') {
            var start = col
            while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
            var end = col
            while (end < text.length - 1 && (text[end + 1].isLetterOrDigit() || text[end + 1] == '_')) end++
            setSelection(clampedLine, start, clampedLine, end + 1)
        } else {
            // Select single character
            setSelection(clampedLine, col, clampedLine, (col + 1).coerceAtMost(text.length))
        }
    }

    /** Select the entire line at [line]. */
    fun selectLineAt(line: Long) {
        val clampedLine = line.coerceIn(0, maxOf(lineCount - 1, 0))
        val text = document.line(clampedLine).toString()
        setSelection(clampedLine, 0, clampedLine, text.length)
    }

    private fun buildReplacementForInsert(text: String): String {
        val currentLine = document.line(caretLine).toString()
        val before = currentLine.substring(0, caretColumn.coerceAtMost(currentLine.length))
        val after = currentLine.substring(caretColumn.coerceAtMost(currentLine.length))
        return before + text + after
    }
}

/** Ordered selection range. */
data class SelectionBounds(
    val startLine: Long,
    val startColumn: Int,
    val endLine: Long,
    val endColumn: Int,
)
