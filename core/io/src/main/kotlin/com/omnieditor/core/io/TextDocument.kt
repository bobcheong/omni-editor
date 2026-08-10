package com.omnieditor.core.io

import kotlinx.coroutines.flow.Flow
import java.nio.channels.WritableByteChannel

/**
 * The shared document model (spec §10). The editor and both compare panes use this.
 *
 * Backed by a piece table (T-12): edits are held as a piece list and materialised
 * only on save. The line index provides O(1) access by line number.
 *
 * CLAUDE.md rule: never load a whole file into a String. Never assume a file fits
 * in memory. This interface enforces that — there is no "get all text" method.
 */
interface TextDocument {

    /** Total number of lines, including the last line even if empty. */
    val lineCount: Long

    /** The line index backing this document. */
    val index: LineIndex

    /** O(1) line access via the line index. */
    fun line(index: Long): CharSequence

    /**
     * Apply an edit: replace [range] (line-based, half-open) with [replacement].
     * Returns an edit ID for undo tracking.
     */
    fun edit(range: LongRange, replacement: CharSequence): Long

    /**
     * Replace [length] characters starting at character [offset] with [replacement].
     * This is a character-offset-based edit (unlike [edit] which is line-based).
     * The entire operation is a single journalled, undoable step.
     * Returns an edit ID for undo tracking.
     *
     * Use for text tools and find/replace-all operations that need character-level
     * control and must be undone as one atomic step.
     */
    fun replaceAll(offset: Int, length: Int, replacement: String): Long

    /** Total document length in characters. */
    val length: Int

    /** Undo the most recent edit. Returns the edit ID undone, or null if nothing to undo. */
    fun undo(): Long?

    /** Redo the most recently undone edit. Returns the edit ID redone, or null. */
    fun redo(): Long?

    /**
     * Materialise the current document state to a byte channel (save).
     * The piece table is flattened; this is the only time the full document
     * is written sequentially.
     */
    suspend fun materialise(into: WritableByteChannel)

    /** Emits on every edit, undo, or redo. Drives re-diff and re-highlight. */
    val changes: Flow<DocumentChange>

    /** Whether the document has unsaved changes. */
    val dirty: Boolean
}

/**
 * Describes a change to a [TextDocument], consumed by the diff engine for
 * incremental re-diffing and by the syntax highlighter for partial re-lex.
 */
data class DocumentChange(
    val editId: Long,
    val startLine: Long,
    val oldEndLine: Long,
    val newEndLine: Long,
)
