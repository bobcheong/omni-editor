package com.omnieditor.feature.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
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

    /**
     * Whether word wrap is currently enabled for this editor session.
     *
     * Toggling this preserves the logical caret position (line, column).
     * The scroll position is adjusted by [EditorContent] to keep the caret
     * visible after the toggle.
     */
    var wordWrap by mutableStateOf(false)

    /**
     * Cache of visual-row start columns for wrapped lines.
     *
     * Populated by [EditorContent] from [LineLayoutCache] results during
     * composition. Key-event handlers query this to navigate between visual
     * rows when [wordWrap] is true. Entries are automatically stale when the
     * document is edited; [EditorContent] clears and repopulates them during
     * the next composition pass.
     *
     * When [wordWrap] is false this cache is not consulted and need not be
     * populated.
     */
    val wrappedRowCache: WrappedRowCache = WrappedRowCache()

    // ── Bookmarks (R-36b) ────────────────────────────────────────────────

    /**
     * Set of bookmarked line indices (0-based). Observable so the gutter
     * recomposes when bookmarks change.
     */
    val bookmarks: MutableSet<Long> = mutableStateSetOf()

    /** Toggle bookmark on the current caret line. */
    fun toggleBookmark() {
        if (caretLine in bookmarks) bookmarks.remove(caretLine) else bookmarks.add(caretLine)
    }

    /** Move caret to the next bookmark after the current line (wraps around). */
    fun nextBookmark() {
        if (bookmarks.isEmpty()) return
        val next = bookmarks.filter { it > caretLine }.minOrNull()
            ?: bookmarks.minOrNull()
        if (next != null) moveCaret(next, 0)
    }

    /** Move caret to the previous bookmark before the current line (wraps around). */
    fun prevBookmark() {
        if (bookmarks.isEmpty()) return
        val prev = bookmarks.filter { it < caretLine }.maxOrNull()
            ?: bookmarks.maxOrNull()
        if (prev != null) moveCaret(prev, 0)
    }

    // ── Column select (R-36b) ────────────────────────────────────────────

    /**
     * Toggle between LINEAR and COLUMN selection mode.
     * In COLUMN mode the selection covers a rectangular block; rendering of
     * the block highlight is deferred but the data model is ready.
     */
    fun toggleColumnSelectMode() {
        selectionMode = if (selectionMode == SelectionMode.COLUMN) SelectionMode.LINEAR else SelectionMode.COLUMN
        clearSelection()
    }

    /**
     * Return the selected text for COLUMN mode: each line's slice from
     * [startCol] to [endCol], joined with newlines.
     * Falls back to [selectedText] when not in COLUMN mode.
     */
    fun selectedTextForMode(): String {
        if (selectionMode != SelectionMode.COLUMN || !hasSelection) return selectedText()
        val bounds = selectionBounds() ?: return ""
        val startCol = minOf(bounds.startColumn, bounds.endColumn)
        val endCol = maxOf(bounds.startColumn, bounds.endColumn)
        val sb = StringBuilder()
        for (line in bounds.startLine..bounds.endLine) {
            val lineText = document.line(line).toString()
            val from = startCol.coerceAtMost(lineText.length)
            val to = endCol.coerceAtMost(lineText.length)
            sb.append(lineText.substring(from, to))
            if (line < bounds.endLine) sb.append('\n')
        }
        return sb.toString()
    }

    // ── IME composing region (R-16) ──────────────────────────────────────

    /** Start of the composing region within the composing line, or -1 when inactive. */
    var composingStart by mutableStateOf(-1)
        internal set

    /** End (exclusive) of the composing region within the composing line, or -1 when inactive. */
    var composingEnd by mutableStateOf(-1)
        internal set

    /** Line on which the composing region lives. Only meaningful when [composingStart] >= 0. */
    var composingLine by mutableLongStateOf(0L)
        internal set

    /** Whether a composing region is currently active. */
    val isComposing: Boolean get() = composingStart >= 0

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

    // ── IME composing text operations (R-16) ───────────────────────────

    /**
     * Set or replace the composing (pre-edit) text. CJK IMEs use this to show
     * uncommitted characters with an underline. If a composing region already
     * exists it is replaced; otherwise one is created at the caret.
     *
     * @param text The new composing text.
     * @param newCursorPosition Relative cursor position after composing text
     *        (1 = immediately after composing text, per Android InputConnection convention).
     */
    fun setComposingText(text: String, @Suppress("UNUSED_PARAMETER") newCursorPosition: Int = 1) {
        if (readOnly) return
        // If there's an existing composing region, remove it first
        if (isComposing && composingLine < lineCount) {
            val lineText = document.line(composingLine).toString()
            val safeStart = composingStart.coerceIn(0, lineText.length)
            val safeEnd = composingEnd.coerceIn(safeStart, lineText.length)
            val withoutComposing = lineText.removeRange(safeStart, safeEnd)
            document.edit(composingLine..composingLine, withoutComposing)
            // Restore caret to composing start
            caretLine = composingLine
            caretColumn = safeStart
        }
        // Insert the new composing text at caret
        if (text.isNotEmpty()) {
            val lineText = document.line(caretLine).toString()
            val col = caretColumn.coerceAtMost(lineText.length)
            val newLine = lineText.substring(0, col) + text + lineText.substring(col)
            document.edit(caretLine..caretLine, newLine)
            composingLine = caretLine
            composingStart = col
            composingEnd = col + text.length
            caretColumn = composingEnd
        } else {
            clearComposingRegion()
        }
    }

    /**
     * Commit (finalise) the composing text. The composing region is cleared
     * and the text becomes permanent. Called when the IME confirms input.
     */
    fun commitComposingText() {
        clearComposingRegion()
    }

    /** Clear the composing region markers without modifying the document. */
    fun clearComposingRegion() {
        composingStart = -1
        composingEnd = -1
    }

    // ── Delete operations (R-16) ─────────────────────────────────────────

    /**
     * Delete one character backward (backspace). If a selection is active,
     * deletes the selection instead. If the caret is at the start of a line,
     * joins with the previous line.
     */
    fun deleteBackward() {
        if (readOnly) return
        if (hasSelection) { deleteSelection(); return }
        if (caretColumn > 0) {
            val lineText = document.line(caretLine).toString()
            val newLine = lineText.removeRange(caretColumn - 1, caretColumn)
            document.edit(caretLine..caretLine, newLine)
            caretColumn--
        } else if (caretLine > 0) {
            // Join with previous line
            val prevText = document.line(caretLine - 1).toString()
            val curText = document.line(caretLine).toString()
            document.edit((caretLine - 1)..caretLine, prevText + curText)
            caretLine--
            caretColumn = prevText.length
        }
    }

    /**
     * Delete one character forward (delete key). If a selection is active,
     * deletes the selection instead. If the caret is at the end of a line,
     * joins with the next line.
     */
    fun deleteForward() {
        if (readOnly) return
        if (hasSelection) { deleteSelection(); return }
        val lineText = document.line(caretLine).toString()
        if (caretColumn < lineText.length) {
            val newLine = lineText.removeRange(caretColumn, caretColumn + 1)
            document.edit(caretLine..caretLine, newLine)
        } else if (caretLine < lineCount - 1) {
            // Join with next line
            val nextText = document.line(caretLine + 1).toString()
            document.edit(caretLine..(caretLine + 1), lineText + nextText)
        }
    }

    /**
     * Delete [beforeLength] characters before the caret and [afterLength]
     * characters after the caret. Used by IME DeleteSurroundingTextCommand.
     */
    fun deleteSurrounding(beforeLength: Int, afterLength: Int) {
        if (readOnly) return
        val lineText = document.line(caretLine).toString()
        val col = caretColumn.coerceAtMost(lineText.length)
        val deleteStart = (col - beforeLength).coerceAtLeast(0)
        val deleteEnd = (col + afterLength).coerceAtMost(lineText.length)
        if (deleteStart < deleteEnd) {
            val newLine = lineText.removeRange(deleteStart, deleteEnd)
            document.edit(caretLine..caretLine, newLine)
            caretColumn = deleteStart
        }
    }

    // ── Line editing operations (#12) ──────────────────────────────────

    /**
     * Delete the caret line (or all selected lines when a selection is active).
     * Each call is one undo step.
     */
    fun deleteLine() {
        if (readOnly) return
        val (startLine, endLine) = selectedLineRange()
        clearSelection()
        if (lineCount == 1L) {
            // Single-line document: clear content rather than deleting
            document.edit(0L..0L, "")
            moveCaret(0L, 0)
            return
        }
        if (endLine >= lineCount - 1 && startLine > 0) {
            // Deleting lines that include the last line: merge with previous
            // to remove the trailing newline left behind.
            val prevText = document.line(startLine - 1).toString()
            document.edit((startLine - 1)..endLine, prevText)
            moveCaret(startLine - 1, 0)
        } else if (endLine < lineCount - 1) {
            // Not the last lines: replace [start..end+1] with just [end+1],
            // effectively removing start..end and their terminators.
            val nextText = document.line(endLine + 1).toString()
            document.edit(startLine..(endLine + 1), nextText)
            moveCaret(startLine.coerceAtMost(lineCount - 1), 0)
        } else {
            // startLine == 0 and endLine == last: replacing entire document
            document.edit(0L..endLine, "")
            moveCaret(0L, 0)
        }
    }

    /**
     * Duplicate the caret line (or all selected lines) below the current position.
     * Each call is one undo step.
     */
    fun duplicateLine() {
        if (readOnly) return
        val (startLine, endLine) = selectedLineRange()
        val block = buildString {
            for (l in startLine..endLine) {
                append(document.line(l))
                if (l < endLine) append('\n')
            }
        }
        // Append the duplicated block after the last line
        val lastLineText = document.line(endLine).toString()
        document.edit(endLine..endLine, lastLineText + "\n" + block)
        // Move caret to the duplicated block
        val offset = endLine - startLine + 1
        moveCaret(caretLine + offset, caretColumn)
    }

    /** Insert an empty line above the caret and move the caret there. */
    fun insertLineAbove() {
        if (readOnly) return
        val lineText = document.line(caretLine).toString()
        document.edit(caretLine..caretLine, "\n" + lineText)
        moveCaret(caretLine, 0)
    }

    /** Insert an empty line below the caret and move the caret there. */
    fun insertLineBelow() {
        if (readOnly) return
        val lineText = document.line(caretLine).toString()
        document.edit(caretLine..caretLine, lineText + "\n")
        moveCaret(caretLine + 1, 0)
    }

    /** Swap the caret line with the line above. */
    fun moveLineUp() {
        if (readOnly || caretLine <= 0) return
        val curText = document.line(caretLine).toString()
        val prevText = document.line(caretLine - 1).toString()
        document.edit((caretLine - 1)..caretLine, curText + "\n" + prevText)
        moveCaret(caretLine - 1, caretColumn)
    }

    /** Swap the caret line with the line below. */
    fun moveLineDown() {
        if (readOnly || caretLine >= lineCount - 1) return
        val curText = document.line(caretLine).toString()
        val nextText = document.line(caretLine + 1).toString()
        document.edit(caretLine..(caretLine + 1), nextText + "\n" + curText)
        moveCaret(caretLine + 1, caretColumn)
    }

    /**
     * Indent: insert [tabWidth] spaces at the start of the caret line,
     * or each line in the selection when a multi-line selection is active.
     */
    fun indent(tabWidth: Int = 4) {
        if (readOnly) return
        val spaces = " ".repeat(tabWidth)
        val (startLine, endLine) = selectedLineRange()
        for (l in startLine..endLine) {
            val text = document.line(l).toString()
            document.edit(l..l, spaces + text)
        }
        if (!hasSelection) {
            caretColumn += tabWidth
        }
    }

    /**
     * Outdent: remove up to [tabWidth] leading spaces from the caret line,
     * or each line in the selection when a multi-line selection is active.
     */
    fun outdent(tabWidth: Int = 4) {
        if (readOnly) return
        val (startLine, endLine) = selectedLineRange()
        for (l in startLine..endLine) {
            val text = document.line(l).toString()
            val leading = text.length - text.trimStart(' ').length
            val remove = minOf(leading, tabWidth)
            if (remove > 0) {
                document.edit(l..l, text.substring(remove))
                if (l == caretLine) {
                    caretColumn = maxOf(0, caretColumn - remove)
                }
            }
        }
    }

    /**
     * Toggle line comment on the caret line or each selected line.
     * [prefix] is the line-comment string (e.g. "//" or "#").
     */
    fun toggleComment(prefix: String = "//") {
        if (readOnly) return
        val (startLine, endLine) = selectedLineRange()
        // Determine whether to add or remove: if ALL lines have the prefix, remove it
        val allCommented = (startLine..endLine).all { l ->
            document.line(l).toString().trimStart().startsWith(prefix)
        }
        for (l in startLine..endLine) {
            val text = document.line(l).toString()
            if (allCommented) {
                // Remove the first occurrence of prefix (with optional trailing space)
                val idx = text.indexOf(prefix)
                if (idx >= 0) {
                    val afterPrefix = idx + prefix.length
                    val end = if (afterPrefix < text.length && text[afterPrefix] == ' ') {
                        afterPrefix + 1
                    } else {
                        afterPrefix
                    }
                    document.edit(l..l, text.removeRange(idx, end))
                }
            } else {
                // Add prefix + space at line start (after leading whitespace)
                val leadingSpaces = text.length - text.trimStart().length
                val newLine = text.substring(0, leadingSpaces) + prefix + " " + text.substring(leadingSpaces)
                document.edit(l..l, newLine)
            }
        }
    }

    /**
     * Return the line range affected by the current operation:
     * the selection range (if active) or just the caret line.
     */
    private fun selectedLineRange(): Pair<Long, Long> {
        val bounds = selectionBounds()
        return if (bounds != null) {
            bounds.startLine to bounds.endLine
        } else {
            caretLine to caretLine
        }
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
