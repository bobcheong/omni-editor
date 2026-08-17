package com.omnieditor.core.io

import com.omnieditor.core.model.OmniError
import com.omnieditor.core.model.OmniException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel

/**
 * Editable [TextDocument] for the INDEXED_EDITABLE tier (16–256 MiB, UTF-8/ASCII).
 *
 * Wraps a [ChannelPieceTable] that uses a [FileChannel] as the original buffer.
 * Edits go to an in-memory additions buffer; unchanged content stays on disk.
 *
 * Key differences from [PieceTableDocument]:
 * - `text()` is supported but O(file) — callers should prefer `line()` or `materialise()`
 * - `materialise()` checks [FileFingerprint] before writing (ADR-015)
 * - `materialise()` streams pieces without loading the entire document
 * - `index` throws (use `line()` directly)
 *
 * Key differences from [LargeFileDocument]:
 * - Editing is supported (edit/undo/redo/batch)
 * - Document tracks dirty state
 */
class LargeFileEditableDocument private constructor(
    private val table: ChannelPieceTable,
    private val raf: RandomAccessFile,
    private val channel: FileChannel,
    private val encoding: String,
    private val bomLength: Int,
    private val bomBytes: ByteArray?,
    private val fingerprint: FileFingerprint,
    val filePath: String,
) : TextDocument, Closeable {

    private var editIdCounter = 0L
    private val undoStack = mutableListOf<JournalEntry>()
    private val redoStack = mutableListOf<JournalEntry>()
    private var batchStartDepth = -1
    private var _editGeneration = 0L
    private var savedUndoDepth = 0
    private val _changes = MutableSharedFlow<DocumentChange>(extraBufferCapacity = 64)

    override val editGeneration: Long get() = _editGeneration
    override val lineCount: Long get() = table.lineCount.toLong()
    override val length: Int get() = table.length

    override val index: LineIndex
        get() = throw UnsupportedOperationException(
            "LargeFileEditableDocument provides line() access directly"
        )

    override fun line(index: Long): CharSequence = table.line(index.toInt())

    /**
     * Replace the content of lines [range] with [replacement].
     *
     * The range is terminator-excluded: edit(2..2, "new") replaces the
     * content of line 2 but preserves its line terminator. A replacement
     * containing '\n' creates new lines; the original terminator follows
     * the last line of the replacement.
     */
    override fun edit(range: LongRange, replacement: CharSequence): Long {
        val editId = ++editIdCounter
        val offset = table.lineToOffset(range.first.toInt())
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
        val count = endOffset - offset
        val text = replacement.toString()
        val record = table.replace(offset, count, text)
        val entry = JournalEntry(editId, record)
        undoStack.add(entry)
        redoStack.clear()
        _editGeneration++
        _changes.tryEmit(
            DocumentChange(editId, range.first, range.last + 1, range.first + text.count { it == '\n' } + 1)
        )
        return editId
    }

    /**
     * Replace [length] characters starting at character [offset] with [replacement].
     * The entire operation is a single journalled, undoable step.
     */
    override fun replaceAll(offset: Int, length: Int, replacement: String): Long {
        val editId = ++editIdCounter
        val record = table.replace(offset, length, replacement)
        val entry = JournalEntry(editId, record)
        undoStack.add(entry)
        redoStack.clear()
        _editGeneration++
        _changes.tryEmit(DocumentChange(editId, 0, lineCount, lineCount))
        return editId
    }

    override fun undo(): Long? {
        if (undoStack.isEmpty()) return null
        val entry = undoStack.removeAt(undoStack.lastIndex)
        applyReverse(entry.record)
        redoStack.add(entry)
        _editGeneration++
        _changes.tryEmit(DocumentChange(-entry.editId, 0, lineCount, lineCount))
        return entry.editId
    }

    override fun redo(): Long? {
        if (redoStack.isEmpty()) return null
        val entry = redoStack.removeAt(redoStack.lastIndex)
        applyForward(entry.record)
        undoStack.add(entry)
        _editGeneration++
        _changes.tryEmit(DocumentChange(entry.editId, 0, lineCount, lineCount))
        return entry.editId
    }

    /**
     * Begin a batch of edits that will be committed as a single undo step by [commitBatch].
     *
     * Callers must call [commitBatch] in a `finally` block.
     * Nested calls are ignored (flat nesting).
     */
    override fun beginBatch() {
        if (batchStartDepth >= 0) return
        batchStartDepth = undoStack.size
    }

    /**
     * Commit the current batch. All edits since [beginBatch] are merged into a single
     * undo step via a compound replace spanning the full pre/post-batch document.
     *
     * Note: this operation is O(document) because it captures the pre- and post-batch
     * text to build a single compound EditRecord. Batch commits are not per-keystroke
     * operations so this is accepted per ADR-012.
     */
    override fun commitBatch() {
        val startDepth = batchStartDepth
        if (startDepth < 0) return
        batchStartDepth = -1
        val batchSize = undoStack.size - startDepth
        if (batchSize <= 1) return

        val batchEntries = undoStack.subList(startDepth, undoStack.size).toList()
        repeat(batchSize) { undoStack.removeAt(undoStack.lastIndex) }

        // Reverse all batch edits to restore pre-batch state
        for (entry in batchEntries.reversed()) applyReverse(entry.record)

        // Capture pre-batch text, replay all edits, capture post-batch text
        val preText = table.substring(0, table.length)
        for (entry in batchEntries) applyForward(entry.record)
        val postText = table.substring(0, table.length)

        // Reverse back to pre-batch state and apply as single compound edit
        for (entry in batchEntries.reversed()) applyReverse(entry.record)

        val editId = ++editIdCounter
        val record = table.replace(0, preText.length, postText)
        val compoundEntry = JournalEntry(editId, record)
        undoStack.add(compoundEntry)
        redoStack.clear()
        _editGeneration++
        _changes.tryEmit(DocumentChange(editId, 0, lineCount, lineCount))
    }

    /**
     * Returns the full document text as a String.
     * For large files this is O(file) — prefer [line] for rendering
     * and [materialise] for saving.
     */
    override fun text(): String = table.substring(0, table.length)

    /**
     * Stream all pieces to [into] (save operation).
     *
     * Checks the [FileFingerprint] first; throws [OmniException] with
     * [OmniError.ExternallyModified] if the backing file has changed since open.
     */
    override suspend fun materialise(into: WritableByteChannel) {
        val file = File(filePath)
        if (!FileFingerprint.check(file, fingerprint)) {
            throw OmniException(OmniError.ExternallyModified(filePath))
        }
        withContext(Dispatchers.IO) {
            table.streamPieces(into, bomBytes)
        }
    }

    override val changes: Flow<DocumentChange> = _changes.asSharedFlow()
    override val dirty: Boolean get() = undoStack.size != savedUndoDepth

    /** Record the current undo-stack depth as the "saved" baseline. */
    fun markSaved() {
        savedUndoDepth = undoStack.size
    }

    val undoCount: Int get() = undoStack.size
    val redoCount: Int get() = redoStack.size

    override fun close() {
        channel.close()
        raf.close()
    }

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

    companion object {
        /**
         * Open a file and build a [LargeFileEditableDocument] over it.
         *
         * Indexes the file, opens a [FileChannel] in read-only mode, and wraps it
         * in a [ChannelPieceTable]. The file channel remains open until [close] is called.
         */
        suspend fun open(
            file: File,
            progress: ((com.omnieditor.core.model.Progress) -> Unit)? = null,
        ): LargeFileEditableDocument = withContext(Dispatchers.IO) {
            val indexResult = FileIndexer.index(file, progress)
            val raf = RandomAccessFile(file, "r")
            val channel = raf.channel
            val charset = charset(indexResult.encoding.charset)
            val bomLength = indexResult.encoding.bomLength
            val bomBytes: ByteArray? = when {
                indexResult.encoding.charset.equals("UTF-8", ignoreCase = true) && bomLength == 3 ->
                    byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
                indexResult.encoding.charset.equals("UTF-16LE", ignoreCase = true) && bomLength == 2 ->
                    byteArrayOf(0xFF.toByte(), 0xFE.toByte())
                indexResult.encoding.charset.equals("UTF-16BE", ignoreCase = true) && bomLength == 2 ->
                    byteArrayOf(0xFE.toByte(), 0xFF.toByte())
                else -> null
            }
            val fingerprint = FileFingerprint.of(file)
            val table = ChannelPieceTable(channel, indexResult.index, charset, bomLength)
            LargeFileEditableDocument(
                table, raf, channel, indexResult.encoding.charset,
                bomLength, bomBytes, fingerprint, file.absolutePath,
            )
        }
    }
}
