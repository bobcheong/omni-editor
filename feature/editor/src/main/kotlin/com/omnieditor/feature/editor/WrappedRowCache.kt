package com.omnieditor.feature.editor

/**
 * Cache of visual-row boundaries for wrapped logical lines.
 *
 * When word wrap is enabled each logical line may render as multiple visual
 * rows. [EditorContent] populates this cache from [LineLayoutCache] results
 * during composition; the key-event handlers in [ImeHandler] and
 * [EditorScreen] query it to navigate between visual rows rather than
 * jumping whole logical lines.
 *
 * Thread-safety: all access must be on the composition / main thread.
 *
 * ## Data model
 * Each entry maps a logical line index to a list of **character offsets** at
 * which each visual row starts. For a line that wraps into three visual rows
 * with break points at columns 40 and 80 the stored value is `[0, 40, 80]`.
 * The list always starts with `0`.
 *
 * ## Fallback
 * If a line has no entry the caller treats the whole line as one visual row —
 * identical to the no-wrap case, which is safe and correct.
 */
class WrappedRowCache {

    /** lineIndex → list of visual-row start columns (always begins with 0). */
    private val rows = HashMap<Long, IntArray>()

    /**
     * Store visual-row start columns for [lineIndex].
     * [starts] must start with `0` and be sorted ascending.
     * Passing a single-element array `[0]` is valid (one visual row = no wrap).
     */
    fun put(lineIndex: Long, starts: IntArray) {
        rows[lineIndex] = starts
    }

    /**
     * Remove the entry for [lineIndex] (e.g. after a document edit invalidates
     * the cached measurement).
     */
    fun invalidate(lineIndex: Long) {
        rows.remove(lineIndex)
    }

    /** Remove all entries. */
    fun clear() {
        rows.clear()
    }

    /**
     * Return the visual-row index (0-based) that contains [column] on
     * [lineIndex], or `0` when the line has no cached wrap info.
     */
    fun visualRowOf(lineIndex: Long, column: Int): Int {
        val starts = rows[lineIndex] ?: return 0
        for (i in starts.indices.reversed()) {
            if (column >= starts[i]) return i
        }
        return 0
    }

    /**
     * Return the number of visual rows for [lineIndex], or `1` when unknown.
     */
    fun visualRowCount(lineIndex: Long): Int = rows[lineIndex]?.size ?: 1

    /**
     * Return the character offset at which visual row [visualRow] starts on
     * [lineIndex], or `0` when unknown / out of range.
     */
    fun rowStart(lineIndex: Long, visualRow: Int): Int {
        val starts = rows[lineIndex] ?: return 0
        return if (visualRow in starts.indices) starts[visualRow] else 0
    }

    /**
     * Return the character offset just past the end of visual row [visualRow]
     * on [lineIndex] (exclusive), or [lineLength] when it is the last row or
     * the line is unknown.
     *
     * [lineLength] must be the length of the logical line text.
     */
    fun rowEnd(lineIndex: Long, visualRow: Int, lineLength: Int): Int {
        val starts = rows[lineIndex] ?: return lineLength
        val nextRow = visualRow + 1
        return if (nextRow in starts.indices) starts[nextRow] else lineLength
    }

    /**
     * Given a current logical position ([lineIndex], [column]), return the
     * new column after moving **up** one visual row.
     *
     * - If there is a previous visual row on the same logical line, the caret
     *   moves to the same relative column within that visual row (clamped to
     *   the row's length).
     * - If already on the first visual row, returns `null` so the caller can
     *   move to the previous logical line instead.
     *
     * [lineLength] is the length of the line text (needed to compute row end).
     */
    fun moveUpVisualRow(lineIndex: Long, column: Int, lineLength: Int): Int? {
        val vRow = visualRowOf(lineIndex, column)
        if (vRow == 0) return null   // already first visual row → caller moves to previous line
        val prevRowStart = rowStart(lineIndex, vRow - 1)
        val prevRowEnd = rowEnd(lineIndex, vRow - 1, lineLength)
        val colWithinRow = column - rowStart(lineIndex, vRow)
        return (prevRowStart + colWithinRow).coerceIn(prevRowStart, prevRowEnd)
    }

    /**
     * Given a current logical position ([lineIndex], [column]), return the
     * new column after moving **down** one visual row.
     *
     * - If there is a next visual row on the same logical line, the caret
     *   moves to the same relative column within that row (clamped to the
     *   row's length).
     * - If already on the last visual row, returns `null` so the caller can
     *   move to the next logical line instead.
     *
     * [lineLength] is the length of the line text (needed to compute row end).
     */
    fun moveDownVisualRow(lineIndex: Long, column: Int, lineLength: Int): Int? {
        val vRow = visualRowOf(lineIndex, column)
        val rowCount = visualRowCount(lineIndex)
        if (vRow >= rowCount - 1) return null  // already last visual row → caller moves to next line
        val curRowStart = rowStart(lineIndex, vRow)
        val nextRowStart = rowStart(lineIndex, vRow + 1)
        val nextRowEnd = rowEnd(lineIndex, vRow + 1, lineLength)
        val colWithinRow = column - curRowStart
        return (nextRowStart + colWithinRow).coerceIn(nextRowStart, nextRowEnd)
    }
}
