package com.omnieditor.feature.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.omnieditor.core.io.PieceTableDocument

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

    private fun buildReplacementForInsert(text: String): String {
        val currentLine = document.line(caretLine).toString()
        val before = currentLine.substring(0, caretColumn.coerceAtMost(currentLine.length))
        val after = currentLine.substring(caretColumn.coerceAtMost(currentLine.length))
        return before + text + after
    }
}
