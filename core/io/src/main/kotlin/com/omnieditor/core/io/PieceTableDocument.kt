package com.omnieditor.core.io

import com.omnieditor.core.model.LineEnding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel

/**
 * [TextDocument] implementation backed by a [PieceTable] (OE-EDT-3, OE-EDT-9).
 *
 * - Edits are held as a piece list and materialised only on save
 * - Unlimited undo/redo via an edit history stack
 * - Continuous journalling to app storage for crash recovery
 * - Editing a large file stays within heap budget (piece table only stores deltas)
 */
class PieceTableDocument private constructor(
    private val table: PieceTable,
    private val journal: Journal?,
    private val encoding: String,
    private val lineEnding: LineEnding,
    private val bomLength: Int = 0,
) : TextDocument, java.io.Closeable {

    private var editIdCounter = 0L
    private val undoStack = mutableListOf<JournalEntry>()
    private val redoStack = mutableListOf<JournalEntry>()

    // ── Typing coalescing state (R-17b) ──
    private var lastTypingTime = 0L
    private var lastTypingLine = -1L
    private val coalesceWindowMs = 2000L

    // ── Batch undo state (R-54 prerequisite) ──
    /** Undo stack depth when beginBatch() was called, or -1 if no batch is active. */
    private var batchStartDepth = -1

    private var _editGeneration = 0L
    override val editGeneration: Long get() = _editGeneration

    /**
     * Undo-stack depth at the time of the last save. dirty is false when the
     * stack depth returns to this value (i.e., the user has undone back to the
     * saved state). This is the standard approach: O(1), no content hashing
     * required.
     *
     * Known limitation: undo-past-save then re-edit could produce a stack whose
     * depth equals savedUndoDepth with different content. For R-21 this is
     * accepted — a content hash would be O(n) and is not required by the spec.
     */
    private var savedUndoDepth: Int = 0
    private val _changes = MutableSharedFlow<DocumentChange>(extraBufferCapacity = 64)

    override val lineCount: Long get() = table.lineCount.toLong()

    override val length: Int get() = table.length

    override val index: LineIndex
        get() = throw UnsupportedOperationException(
            "PieceTableDocument provides line() access directly; use line(index) instead of index"
        )

    override fun line(index: Long): CharSequence {
        return table.line(index.toInt())
    }

    /**
     * Replace the content of lines [range] with [replacement].
     *
     * The range is **terminator-excluded**: `edit(2..2, "new")` replaces the
     * content of line 2 but preserves its line terminator. A replacement
     * containing `\n` creates new lines; the original terminator follows
     * the last line of the replacement.
     */
    override fun edit(range: LongRange, replacement: CharSequence): Long {
        val editId = ++editIdCounter
        val offset = table.lineToOffset(range.first.toInt())
        val endOffset = if (range.last >= lineCount - 1) {
            // Last line has no terminator — replace to end of document
            table.length
        } else {
            // Exclude the terminator: end at lineStart + lineContentLength
            val nextLineStart = table.lineToOffset((range.last + 1).toInt())
            // Peek at the character before nextLineStart to determine terminator length
            val charBefore = table.charAt(nextLineStart - 1)
            if (charBefore == '\n' && nextLineStart >= 2 && table.charAt(nextLineStart - 2) == '\r') {
                nextLineStart - 2 // CRLF
            } else {
                nextLineStart - 1 // LF or CR
            }
        }
        val count = endOffset - offset
        val text = replacement.toString()

        val record = table.replace(offset, count, text)
        val entry = JournalEntry(editId, record)
        undoStack.add(entry)
        redoStack.clear()

        // Only write to the journal when NOT in a batch. During a batch, individual
        // edit entries are suppressed; commitBatch() appends a single compound entry
        // that is the only entry crash recovery needs to replay.
        if (batchStartDepth < 0) journal?.append(entry)
        _editGeneration++

        _changes.tryEmit(
            DocumentChange(editId, range.first, range.last + 1, range.first + countLines(text))
        )

        return editId
    }

    /**
     * Variant of [edit] that coalesces consecutive typing keystrokes on the same
     * line within a 2-second window into a single undo step (R-17b).
     *
     * Call this from EditorState.insertAtCaret for every character-at-a-time typing
     * operation. Text tools and replace-all should call [replaceAll] directly.
     *
     * Coalescing breaks when:
     *  - [isTyping] is false
     *  - The line changes (caret jumped to a different line)
     *  - More than [coalesceWindowMs] ms have elapsed since the last typing edit
     *  - [breakCoalescing] is called explicitly (e.g., on save or non-typing op)
     */
    fun editCoalesced(range: LongRange, replacement: CharSequence, isTyping: Boolean = false): Long {
        val now = System.currentTimeMillis()
        val canCoalesce = isTyping &&
            undoStack.isNotEmpty() &&
            lastTypingLine == range.first &&
            (now - lastTypingTime) < coalesceWindowMs

        return if (canCoalesce) {
            // Pop the last entry and reverse it so the piece table is back to the
            // state just before that entry was applied.
            val last = undoStack.removeAt(undoStack.lastIndex)
            applyReverse(last.record)

            // Now apply the new replacement over the same starting offset.
            // Use the same offset as the original entry so the compound record
            // spans from the pre-coalesce state to the new state.
            val compoundOffset = last.record.offset
            val originalDeleted = last.record.deleted

            // Compute the real byte-level span for the new replacement the same
            // way edit() does, but we already know the offset from the original.
            val endOffset = if (range.last >= lineCount - 1) {
                table.length
            } else {
                val nextLineStart = table.lineToOffset((range.last + 1).toInt())
                val charBefore = table.charAt(nextLineStart - 1)
                if (charBefore == '\n' && nextLineStart >= 2 && table.charAt(nextLineStart - 2) == '\r') {
                    nextLineStart - 2
                } else {
                    nextLineStart - 1
                }
            }
            val count = endOffset - compoundOffset
            val text = replacement.toString()
            val newRecord = table.replace(compoundOffset, count, text)

            // Build a compound record: original deleted text → new inserted text
            val compoundRecord = EditRecord(
                type = EditRecord.Type.REPLACE,
                offset = compoundOffset,
                deleted = originalDeleted,
                inserted = newRecord.inserted,
            )
            val editId = ++editIdCounter
            val compoundEntry = JournalEntry(editId, compoundRecord)
            undoStack.add(compoundEntry)
            redoStack.clear()

            journal?.append(compoundEntry)
            _editGeneration++

            lastTypingTime = now
            lastTypingLine = range.first

            _changes.tryEmit(
                DocumentChange(editId, range.first, range.last + 1, range.first + countLines(text))
            )
            editId
        } else {
            // Not coalescing: delegate to the normal edit path.
            val editId = edit(range, replacement)
            if (isTyping) {
                lastTypingTime = now
                lastTypingLine = range.first
            } else {
                // Non-typing op breaks any future coalescing.
                lastTypingLine = -1L
            }
            editId
        }
    }

    /**
     * Explicitly break the coalescing window. Call this on save or after any
     * non-typing operation that should prevent the next keystroke from merging
     * with previous ones.
     */
    fun breakCoalescing() {
        lastTypingLine = -1L
        lastTypingTime = 0L
    }

    /**
     * Replace [length] characters at character [offset] with [replacement] as a single
     * journalled, undoable step. Use for text tools and find/replace-all.
     *
     * Always breaks the coalescing window so that subsequent typing starts a
     * fresh undo step.
     */
    override fun replaceAll(offset: Int, length: Int, replacement: String): Long {
        breakCoalescing()
        val editId = ++editIdCounter
        val record = table.replace(offset, length, replacement)
        val entry = JournalEntry(editId, record)
        undoStack.add(entry)
        redoStack.clear()
        journal?.append(entry)
        _editGeneration++
        // Emit a conservative change covering the whole document
        _changes.tryEmit(DocumentChange(editId, 0, lineCount, lineCount))
        return editId
    }

    override fun undo(): Long? {
        if (undoStack.isEmpty()) return null
        breakCoalescing()
        val entry = undoStack.removeAt(undoStack.lastIndex)
        applyReverse(entry.record)
        redoStack.add(entry)
        journal?.append(JournalEntry(-entry.editId, entry.record))
        _editGeneration++
        _changes.tryEmit(DocumentChange(-entry.editId, 0, lineCount, lineCount))
        return entry.editId
    }

    override fun redo(): Long? {
        if (redoStack.isEmpty()) return null
        breakCoalescing()
        val entry = redoStack.removeAt(redoStack.lastIndex)
        applyForward(entry.record)
        undoStack.add(entry)
        journal?.append(entry)
        _editGeneration++
        _changes.tryEmit(DocumentChange(entry.editId, 0, lineCount, lineCount))
        return entry.editId
    }

    /**
     * Begin a batch of edits that will be committed as a single undo step by [commitBatch].
     *
     * **Callers must call [commitBatch] in a `finally` block.** If an exception propagates
     * between [beginBatch] and [commitBatch], the batch flag stays set permanently, blocking
     * all future journal writes. Example:
     * ```
     * doc.beginBatch()
     * try {
     *     doc.edit(...)
     *     doc.edit(...)
     * } finally {
     *     doc.commitBatch()
     * }
     * ```
     *
     * Nested calls to [beginBatch] are ignored (flat nesting). The outermost
     * [commitBatch] closes the batch.
     */
    override fun beginBatch() {
        if (batchStartDepth >= 0) return // already in a batch — flat nesting
        breakCoalescing()
        batchStartDepth = undoStack.size
    }

    override fun commitBatch() {
        val startDepth = batchStartDepth
        if (startDepth < 0) return // no active batch
        batchStartDepth = -1
        val batchSize = undoStack.size - startDepth
        if (batchSize <= 1) return // 0 or 1 edit — nothing to merge

        // Collect the batch entries (in order they were applied)
        val batchEntries = undoStack.subList(startDepth, undoStack.size).toList()

        // Remove them from the undo stack
        repeat(batchSize) { undoStack.removeAt(undoStack.lastIndex) }

        // Reverse all batch edits to restore the pre-batch state
        for (entry in batchEntries.reversed()) {
            applyReverse(entry.record)
        }

        // Capture pre-batch text, replay all edits, capture post-batch text
        val preText = table.text()
        for (entry in batchEntries) {
            applyForward(entry.record)
        }
        val postText = table.text()

        // Reverse back to pre-batch state so we can issue one atomic replace
        for (entry in batchEntries.reversed()) {
            applyReverse(entry.record)
        }

        // Apply as a single compound replaceAll entry
        val editId = ++editIdCounter
        val record = table.replace(0, preText.length, postText)
        val compoundEntry = JournalEntry(editId, record)
        undoStack.add(compoundEntry)
        redoStack.clear()
        journal?.append(compoundEntry)
        _editGeneration++
        _changes.tryEmit(DocumentChange(editId, 0, lineCount, lineCount))
    }

    override suspend fun materialise(into: WritableByteChannel) {
        withContext(Dispatchers.IO) {
            val stream = Channels.newOutputStream(into)
            // Re-emit BOM if the source had one
            if (bomLength > 0) {
                val bomBytes = when {
                    encoding.equals("UTF-8", ignoreCase = true) && bomLength == 3 ->
                        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
                    encoding.equals("UTF-16LE", ignoreCase = true) && bomLength == 2 ->
                        byteArrayOf(0xFF.toByte(), 0xFE.toByte())
                    encoding.equals("UTF-16BE", ignoreCase = true) && bomLength == 2 ->
                        byteArrayOf(0xFE.toByte(), 0xFF.toByte())
                    encoding.equals("UTF-32LE", ignoreCase = true) && bomLength == 4 ->
                        byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00)
                    encoding.equals("UTF-32BE", ignoreCase = true) && bomLength == 4 ->
                        byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte())
                    else -> null
                }
                if (bomBytes != null) stream.write(bomBytes)
            }
            val text = table.text()
            val bytes = text.toByteArray(charset(encoding))
            stream.write(bytes)
            stream.flush()
        }
    }

    override val changes: Flow<DocumentChange> = _changes.asSharedFlow()

    /**
     * True when the document has unsaved changes.
     *
     * False when the undo-stack depth equals the depth recorded at the last
     * [markSaved] call (or document creation). This means full-undo back to the
     * saved state correctly clears dirty.
     */
    override val dirty: Boolean get() = undoStack.size != savedUndoDepth

    /**
     * Record the current undo-stack depth as the "saved" baseline so that
     * [dirty] returns false until another edit is made.
     * Call this after every successful write to the backing store.
     */
    fun markSaved() {
        savedUndoDepth = undoStack.size
        breakCoalescing()
    }

    /** Get the full text. Use for testing; prefer materialise for save. */
    fun text(): String = table.text()

    /** Release the journal file handle. */
    override fun close() {
        journal?.close()
    }

    /** Number of undoable edits. */
    val undoCount: Int get() = undoStack.size

    /** Number of redoable edits. */
    val redoCount: Int get() = redoStack.size

    private fun applyReverse(record: EditRecord) {
        when (record.type) {
            EditRecord.Type.INSERT -> table.delete(record.offset, record.inserted.length)
            EditRecord.Type.DELETE -> table.insert(record.offset, record.deleted)
            EditRecord.Type.REPLACE -> {
                table.delete(record.offset, record.inserted.length)
                table.insert(record.offset, record.deleted)
            }
        }
    }

    private fun applyForward(record: EditRecord) {
        when (record.type) {
            EditRecord.Type.INSERT -> table.insert(record.offset, record.inserted)
            EditRecord.Type.DELETE -> table.delete(record.offset, record.deleted.length)
            EditRecord.Type.REPLACE -> table.replace(record.offset, record.deleted.length, record.inserted)
        }
    }

    private fun countLines(text: String): Long {
        return text.count { it == '\n' }.toLong() + 1
    }

    companion object {
        /** Create a document from initial content. */
        fun create(
            content: String = "",
            encoding: String = "UTF-8",
            lineEnding: LineEnding = LineEnding.LF,
            journalDir: File? = null,
            documentId: String? = null,
            bomLength: Int = 0,
        ): PieceTableDocument {
            val journal = if (journalDir != null && documentId != null) {
                Journal(File(journalDir, "$documentId.journal"))
            } else null
            return PieceTableDocument(
                PieceTable.create(content),
                journal,
                encoding,
                lineEnding,
                bomLength,
            )
        }

        /**
         * Recover a document from its journal after a crash.
         * Replays all journal entries to reconstruct the edit state.
         */
        fun recover(
            originalContent: String,
            journalDir: File,
            documentId: String,
            encoding: String = "UTF-8",
            lineEnding: LineEnding = LineEnding.LF,
        ): PieceTableDocument? {
            val journalFile = File(journalDir, "$documentId.journal")
            if (!journalFile.exists()) return null

            val doc = create(originalContent, encoding, lineEnding, journalDir, documentId)
            val entries = Journal.readAll(journalFile)

            for (entry in entries) {
                if (entry.editId < 0) {
                    // Undo marker
                    doc.undo()
                } else {
                    doc.applyForward(entry.record)
                    doc.undoStack.add(entry)
                    doc.editIdCounter = maxOf(doc.editIdCounter, entry.editId)
                }
            }

            return doc
        }
    }
}

/**
 * Continuous journal for crash recovery (OE-EDT-9).
 * Appends edit records as JSON lines to a file.
 * Holds the file handle open for efficient batched writes.
 */
class Journal(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true }
    private var writer: BufferedWriter? = null

    fun append(entry: JournalEntry) {
        if (writer == null) {
            file.parentFile?.mkdirs()
            writer = BufferedWriter(FileWriter(file, true))
        }
        writer!!.write(json.encodeToString(JournalEntry.serializer(), entry))
        writer!!.newLine()
        writer!!.flush()
    }

    fun flush() {
        writer?.flush()
    }

    fun close() {
        writer?.close()
        writer = null
    }

    fun clear() {
        close()
        file.delete()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun readAll(file: File): List<JournalEntry> {
            if (!file.exists()) return emptyList()
            return file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull {
                    try {
                        json.decodeFromString(JournalEntry.serializer(), it)
                    } catch (_: Exception) {
                        null // skip corrupted entries
                    }
                }
        }
    }
}

@Serializable
data class JournalEntry(
    val editId: Long,
    @Serializable(with = EditRecordSerializer::class)
    val record: EditRecord,
)

@Serializable
data class SerializableEditRecord(
    val type: String,
    val offset: Int,
    val deleted: String,
    val inserted: String,
)

object EditRecordSerializer : kotlinx.serialization.KSerializer<EditRecord> {
    override val descriptor = SerializableEditRecord.serializer().descriptor

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: EditRecord) {
        val surrogate = SerializableEditRecord(value.type.name, value.offset, value.deleted, value.inserted)
        encoder.encodeSerializableValue(SerializableEditRecord.serializer(), surrogate)
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): EditRecord {
        val surrogate = decoder.decodeSerializableValue(SerializableEditRecord.serializer())
        return EditRecord(
            EditRecord.Type.valueOf(surrogate.type), surrogate.offset, surrogate.deleted, surrogate.inserted,
        )
    }
}
