package com.omnieditor.core.io

import com.omnieditor.core.model.Phase
import com.omnieditor.core.model.Progress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.coroutines.coroutineContext

/**
 * Indexes a file by memory-mapping it and building a [LineIndex].
 *
 * For files under [MMAP_THRESHOLD], the file is read into a byte array.
 * For larger files, it is memory-mapped to stay within heap budget.
 * OE-ENG-4: the 300 MB test file should use under 60 MB heap.
 */
object FileIndexer {

    /** Below this size, read the whole file into a byte array. */
    const val MMAP_THRESHOLD = 8L * 1024 * 1024 // 8 MB

    /** Maximum sample size for encoding detection. */
    private const val ENCODING_SAMPLE_SIZE = 64 * 1024 // 64 KB

    data class IndexResult(
        val index: LineIndex,
        val encoding: EncodingDetector.Result,
        val fileSize: Long,
    )

    /**
     * Index a file: detect encoding, detect line endings, build the line index.
     * Cancellable via the coroutine context.
     */
    suspend fun index(
        file: File,
        progress: ((Progress) -> Unit)? = null,
    ): IndexResult = withContext(Dispatchers.IO) {
        val fileSize = file.length()

        if (fileSize == 0L) {
            val encoding = EncodingDetector.Result("UTF-8", 0, EncodingDetector.Confidence.HEURISTIC)
            val index = LineIndex.build(
                bytes = ByteArray(0),
                size = 0,
                encoding = encoding.charset,
                progress = progress,
            )
            return@withContext IndexResult(index, encoding, 0)
        }

        if (fileSize <= MMAP_THRESHOLD) {
            return@withContext indexSmallFile(file, fileSize.toInt(), progress)
        }

        return@withContext indexLargeFile(file, fileSize, progress)
    }

    private suspend fun indexSmallFile(
        file: File,
        size: Int,
        progress: ((Progress) -> Unit)?,
    ): IndexResult {
        val bytes = file.readBytes()
        val sampleSize = minOf(bytes.size, ENCODING_SAMPLE_SIZE)
        val encoding = EncodingDetector.detect(bytes, sampleSize)
        val lineEnding = LineEndingDetector.detect(bytes, sampleSize)
        val index = LineIndex.build(
            bytes = bytes,
            size = bytes.size,
            bomSkip = encoding.bomLength,
            lineEnding = lineEnding,
            encoding = encoding.charset,
            progress = progress,
        )
        return IndexResult(index, encoding, size.toLong())
    }

    /**
     * Index a large file using memory-mapped I/O.
     *
     * Strategy: map the file in chunks, scan for line boundaries, and build the index
     * without holding the whole file in heap. Each chunk is mapped, scanned, then
     * unmapped (the buffer goes out of scope and the OS reclaims the mapping).
     */
    private suspend fun indexLargeFile(
        file: File,
        fileSize: Long,
        progress: ((Progress) -> Unit)?,
    ): IndexResult {
        // Read a sample for encoding detection
        val sampleBytes = ByteArray(minOf(fileSize, ENCODING_SAMPLE_SIZE.toLong()).toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.readFully(sampleBytes, 0, sampleBytes.size)
        }
        val encoding = EncodingDetector.detect(sampleBytes)
        val lineEnding = LineEndingDetector.detect(sampleBytes)

        val offsets = mutableListOf<Long>()
        val lengths = mutableListOf<Int>()
        val hashes = mutableListOf<Long>()

        val bomSkip = encoding.bomLength
        var globalPos = bomSkip.toLong()
        var lineStart = globalPos
        var linesFound = 0L

        val chunkSize = 64L * 1024 * 1024 // 64 MB chunks

        RandomAccessFile(file, "r").use { raf ->
            val channel = raf.channel

            var chunkStart = 0L
            while (chunkStart < fileSize) {
                coroutineContext.ensureActive()
                val chunkEnd = minOf(chunkStart + chunkSize, fileSize)
                val mappedSize = chunkEnd - chunkStart

                val buffer: MappedByteBuffer = channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    chunkStart,
                    mappedSize,
                )

                val chunkBytes = ByteArray(mappedSize.toInt())
                buffer.get(chunkBytes)

                // Scan for lines in this chunk
                var i = if (chunkStart == 0L) bomSkip else 0
                while (i < chunkBytes.size) {
                    val absPos = chunkStart + i
                    val isCrlf = i + 1 < chunkBytes.size &&
                        chunkBytes[i] == '\r'.code.toByte() && chunkBytes[i + 1] == '\n'.code.toByte()
                    val isLf = !isCrlf && chunkBytes[i] == '\n'.code.toByte()
                    val isCr = !isCrlf && !isLf && chunkBytes[i] == '\r'.code.toByte()

                    if (isCrlf || isLf || isCr) {
                        val lineLen = (absPos - lineStart).toInt()
                        offsets.add(lineStart)
                        lengths.add(lineLen)
                        hashes.add(hashLineFromFile(channel, lineStart, lineLen))
                        linesFound++

                        i += if (isCrlf) 2 else 1
                        lineStart = chunkStart + i

                        if (linesFound % 4096 == 0L) {
                            coroutineContext.ensureActive()
                            progress?.invoke(
                                Progress(absPos, fileSize, Phase.INDEXING)
                            )
                        }
                    } else {
                        i++
                    }
                }

                chunkStart = chunkEnd
            }

            // Handle last line (no trailing newline)
            if (lineStart < fileSize) {
                val lineLen = (fileSize - lineStart).toInt()
                offsets.add(lineStart)
                lengths.add(lineLen)
                hashes.add(hashLineFromFile(channel, lineStart, lineLen))
                linesFound++
            }
        }

        // Handle completely empty file after BOM
        if (linesFound == 0L) {
            offsets.add(bomSkip.toLong())
            lengths.add(0)
            hashes.add(LineIndex.hashLine(ByteArray(0), 0, 0))
        }

        progress?.invoke(Progress(fileSize, fileSize, Phase.INDEXING))

        val index = LineIndex(
            offsets.toLongArray(), lengths.toIntArray(), hashes.toLongArray(),
            lineEnding, encoding.charset, bomSkip,
        )
        return IndexResult(index, encoding, fileSize)
    }

    /**
     * Hash a line by reading its bytes from the file channel.
     * For large files, this avoids keeping all line content in memory.
     */
    private fun hashLineFromFile(channel: FileChannel, offset: Long, length: Int): Long {
        if (length == 0) return LineIndex.hashLine(ByteArray(0), 0, 0)
        val buf = ByteArray(length)
        val bb = java.nio.ByteBuffer.wrap(buf)
        channel.read(bb, offset)
        return LineIndex.hashLine(buf, 0, length)
    }
}
