package com.omnieditor.core.io

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel

/**
 * Read-only [TextDocument] backed by a [FileChannel] and [LineIndex].
 *
 * Used for files in the INDEXED_READ_ONLY tier (16–256 MiB). Line access is O(1)
 * via the index; the file content stays on disk, not in heap.
 *
 * Editing is not supported — [edit], [replaceAll], [undo], [redo] throw
 * [UnsupportedOperationException]. [dirty] is always false; [editGeneration] is always 0.
 * [beginBatch] and [commitBatch] are no-ops.
 */
class LargeFileDocument private constructor(
    private val indexResult: FileIndexer.IndexResult,
    private val raf: RandomAccessFile,
    private val channel: FileChannel,
) : TextDocument, Closeable {

    private val lineIndex: LineIndex = indexResult.index
    private val encoding: String = indexResult.encoding.charset
    private val bomLength: Int = indexResult.encoding.bomLength
    private val _changes = MutableSharedFlow<DocumentChange>(extraBufferCapacity = 1)

    override val lineCount: Long get() = lineIndex.lineCount

    /**
     * Approximation: returns file size in bytes. For large read-only files the
     * exact character count is not tracked (it would require decoding the whole
     * file). Consumers of [length] must treat this as an upper bound.
     */
    override val length: Int get() = indexResult.fileSize.toInt()

    /**
     * The [LineIndex] backing this document, used by the diff engine.
     *
     * Unlike [PieceTableDocument] (which builds the index lazily from the piece
     * table), this implementation holds the index directly from [FileIndexer].
     */
    override val index: LineIndex get() = lineIndex

    /**
     * Read line [index] from the file channel.
     *
     * [LineIndex.offset] is relative to post-BOM content; we add [bomLength] to
     * get the true byte position in the channel.
     */
    override fun line(index: Long): CharSequence {
        val offset = lineIndex.offset(index) + bomLength
        val len = lineIndex.length(index)
        if (len == 0) return ""
        val buf = ByteBuffer.allocate(len)
        channel.read(buf, offset)
        buf.flip()
        return String(buf.array(), 0, buf.limit(), charset(encoding))
    }

    override fun edit(range: LongRange, replacement: CharSequence): Long {
        throw UnsupportedOperationException("LargeFileDocument is read-only")
    }

    override fun replaceAll(offset: Int, length: Int, replacement: String): Long {
        throw UnsupportedOperationException("LargeFileDocument is read-only")
    }

    override fun undo(): Long? {
        throw UnsupportedOperationException("LargeFileDocument is read-only")
    }

    override fun redo(): Long? {
        throw UnsupportedOperationException("LargeFileDocument is read-only")
    }

    /** No-op: read-only documents have no batch state to manage. */
    override fun beginBatch() = Unit

    /** No-op: read-only documents have no batch state to manage. */
    override fun commitBatch() = Unit

    /**
     * Copy the raw file bytes (including any BOM) to [into].
     *
     * Uses an 8 KiB transfer buffer; does not load the whole file into heap.
     */
    override suspend fun materialise(into: WritableByteChannel) {
        withContext(Dispatchers.IO) {
            channel.position(0)
            val buf = ByteBuffer.allocate(8192)
            while (channel.read(buf) != -1) {
                buf.flip()
                into.write(buf)
                buf.compact()
            }
        }
    }

    override fun text(): String {
        return buildString {
            for (i in 0 until lineCount) {
                if (i > 0) append('\n')
                append(line(i))
            }
        }
    }

    override val changes: Flow<DocumentChange> = _changes.asSharedFlow()
    override val dirty: Boolean get() = false
    override val editGeneration: Long get() = 0L

    override fun close() {
        channel.close()
        raf.close()
    }

    companion object {
        /**
         * Open [file] as a read-only large-file document.
         *
         * Indexes the file via [FileIndexer] (encoding detection + line boundary scan)
         * then opens a [FileChannel] for on-demand line reads.
         *
         * @param file     the file to open; must exist and be readable
         * @param progress optional progress callback for the indexing phase
         */
        suspend fun open(
            file: File,
            progress: ((com.omnieditor.core.model.Progress) -> Unit)? = null,
        ): LargeFileDocument = withContext(Dispatchers.IO) {
            val indexResult = FileIndexer.index(file, progress)
            val raf = RandomAccessFile(file, "r")
            val channel = raf.channel
            LargeFileDocument(indexResult, raf, channel)
        }
    }
}
