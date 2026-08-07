package com.omnieditor.core.io

import com.omnieditor.core.model.LineEnding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
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
) : TextDocument {

    private var editIdCounter = 0L
    private val undoStack = mutableListOf<JournalEntry>()
    private val redoStack = mutableListOf<JournalEntry>()
    private val _changes = MutableSharedFlow<DocumentChange>(extraBufferCapacity = 64)

    override val lineCount: Long get() = table.lineCount.toLong()

    override val index: LineIndex
        get() = throw UnsupportedOperationException(
            "PieceTableDocument provides line() access directly; use line(index) instead of index"
        )

    override fun line(index: Long): CharSequence {
        return table.line(index.toInt())
    }

    override fun edit(range: LongRange, replacement: CharSequence): Long {
        val editId = ++editIdCounter
        val offset = lineToOffset(range.first)
        val endOffset = if (range.last >= lineCount) table.length else lineToOffset(range.last + 1)
        val count = endOffset - offset
        val text = replacement.toString()

        val record = table.replace(offset, count, text)
        val entry = JournalEntry(editId, record)
        undoStack.add(entry)
        redoStack.clear()

        journal?.append(entry)

        _changes.tryEmit(
            DocumentChange(editId, range.first, range.last + 1, range.first + countLines(text))
        )

        return editId
    }

    override fun undo(): Long? {
        if (undoStack.isEmpty()) return null
        val entry = undoStack.removeAt(undoStack.lastIndex)
        applyReverse(entry.record)
        redoStack.add(entry)
        journal?.append(JournalEntry(-entry.editId, entry.record))
        _changes.tryEmit(DocumentChange(-entry.editId, 0, lineCount, lineCount))
        return entry.editId
    }

    override fun redo(): Long? {
        if (redoStack.isEmpty()) return null
        val entry = redoStack.removeAt(redoStack.lastIndex)
        applyForward(entry.record)
        undoStack.add(entry)
        journal?.append(entry)
        _changes.tryEmit(DocumentChange(entry.editId, 0, lineCount, lineCount))
        return entry.editId
    }

    override suspend fun materialise(into: WritableByteChannel) {
        withContext(Dispatchers.IO) {
            val text = table.text()
            val bytes = text.toByteArray(charset(encoding))
            val stream = Channels.newOutputStream(into)
            stream.write(bytes)
            stream.flush()
        }
    }

    override val changes: Flow<DocumentChange> = _changes.asSharedFlow()

    override val dirty: Boolean get() = undoStack.isNotEmpty()

    /** Get the full text. Use for testing; prefer materialise for save. */
    fun text(): String = table.text()

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

    private fun lineToOffset(lineIndex: Long): Int {
        var line = 0L
        var offset = 0
        val text = table.text()
        while (offset < text.length && line < lineIndex) {
            if (text[offset] == '\n') line++
            offset++
        }
        return offset
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
        ): PieceTableDocument {
            val journal = if (journalDir != null && documentId != null) {
                Journal(File(journalDir, "$documentId.journal"))
            } else null
            return PieceTableDocument(
                PieceTable.create(content),
                journal,
                encoding,
                lineEnding,
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
 */
class Journal(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true }

    fun append(entry: JournalEntry) {
        file.parentFile?.mkdirs()
        file.appendText(json.encodeToString(JournalEntry.serializer(), entry) + "\n")
    }

    fun clear() {
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
        return EditRecord(EditRecord.Type.valueOf(surrogate.type), surrogate.offset, surrogate.deleted, surrogate.inserted)
    }
}
