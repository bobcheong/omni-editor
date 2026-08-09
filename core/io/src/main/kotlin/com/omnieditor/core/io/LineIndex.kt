package com.omnieditor.core.io

import com.omnieditor.core.model.LineEnding
import com.omnieditor.core.model.Phase
import com.omnieditor.core.model.Progress
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * An index of line positions within a file: [offset, length, hash] per line.
 *
 * Built from raw bytes (after BOM skip), used by the diff engine to compare files
 * without loading them into memory. The editor and compare panes use it for O(1)
 * line access.
 *
 * Lines are 0-indexed. A "line" is everything up to but not including the line
 * terminator. The terminator style is recorded but not included in the hash.
 */
class LineIndex internal constructor(
    private val offsets: LongArray,
    private val lengths: IntArray,
    private val hashes: LongArray,
    val lineEnding: LineEnding,
    val encoding: String,
    val bomLength: Int,
) {
    val lineCount: Long get() = offsets.size.toLong()

    /** Byte offset of line [index] from the start of content (after BOM). */
    fun offset(index: Long): Long {
        require(index in 0 until lineCount) { "Line $index out of range [0, $lineCount)" }
        return offsets[index.toInt()]
    }

    /** Length of line [index] in bytes, excluding the line terminator. */
    fun length(index: Long): Int {
        require(index in 0 until lineCount) { "Line $index out of range [0, $lineCount)" }
        return lengths[index.toInt()]
    }

    /** Content hash for line [index], used for O(1) equality checks in diff. */
    fun hash(index: Long): Long {
        require(index in 0 until lineCount) { "Line $index out of range [0, $lineCount)" }
        return hashes[index.toInt()]
    }

    companion object {
        /**
         * Build a line index from raw bytes.
         *
         * @param bytes      the file content
         * @param size       number of valid bytes in [bytes]
         * @param bomSkip    bytes to skip at the start (BOM length)
         * @param lineEnding detected line ending style
         * @param encoding   detected encoding name
         * @param progress   callback for progress reporting
         */
        suspend fun build(
            bytes: ByteArray,
            size: Int = bytes.size,
            bomSkip: Int = 0,
            lineEnding: LineEnding = LineEnding.LF,
            encoding: String = "UTF-8",
            progress: ((Progress) -> Unit)? = null,
        ): LineIndex {
            return buildFromBytes(bytes, size, bomSkip, lineEnding, encoding, progress)
        }

        private suspend fun buildFromBytes(
            bytes: ByteArray,
            size: Int,
            bomSkip: Int,
            lineEnding: LineEnding,
            encoding: String,
            progress: ((Progress) -> Unit)?,
        ): LineIndex {
            val offsets = mutableListOf<Long>()
            val lengths = mutableListOf<Int>()
            val hashes = mutableListOf<Long>()

            var pos = bomSkip
            var lineStart = pos
            var linesFound = 0L
            val total = (size - bomSkip).toLong()

            while (pos <= size) {
                val atEnd = pos == size
                val isCrlf = !atEnd && pos + 1 < size &&
                    bytes[pos] == '\r'.code.toByte() && bytes[pos + 1] == '\n'.code.toByte()
                val isLf = !atEnd && !isCrlf && bytes[pos] == '\n'.code.toByte()
                val isCr = !atEnd && !isCrlf && !isLf && bytes[pos] == '\r'.code.toByte()
                val isLineEnd = isCrlf || isLf || isCr || atEnd

                if (isLineEnd) {
                    val lineLen = pos - lineStart
                    // D-7 / ADR-007: lineCount = newlines + 1. A file ending in
                    // a terminator has a real, caret-placeable empty final line.
                    offsets.add(lineStart.toLong())
                    lengths.add(lineLen)
                    hashes.add(hashLine(bytes, lineStart, lineLen))
                    linesFound++

                    pos += when {
                        isCrlf -> 2
                        isLf || isCr -> 1
                        else -> 1 // atEnd
                    }
                    lineStart = pos

                    // Cancellation check every 4096 lines (CLAUDE.md rule)
                    if (linesFound % 4096 == 0L) {
                        coroutineContext.ensureActive()
                        progress?.invoke(
                            Progress(
                                done = (pos - bomSkip).toLong(),
                                total = total,
                                phase = Phase.INDEXING,
                            )
                        )
                    }

                    if (atEnd) break
                } else {
                    pos++
                }
            }

            // Handle completely empty input
            if (linesFound == 0L) {
                offsets.add(bomSkip.toLong())
                lengths.add(0)
                hashes.add(hashLine(bytes, bomSkip, 0))
            }

            progress?.invoke(Progress(done = total, total = total, phase = Phase.INDEXING))

            return LineIndex(
                offsets = offsets.toLongArray(),
                lengths = lengths.toIntArray(),
                hashes = hashes.toLongArray(),
                lineEnding = lineEnding,
                encoding = encoding,
                bomLength = bomSkip,
            )
        }

        /**
         * FNV-1a 64-bit hash of a line's bytes. Fast, good distribution, no allocations.
         */
        internal fun hashLine(bytes: ByteArray, start: Int, length: Int): Long {
            var hash = -3750763034362895579L // FNV offset basis
            val end = start + length
            for (i in start until end) {
                hash = hash xor (bytes[i].toLong() and 0xFF)
                hash *= 1099511628211L // FNV prime
            }
            return hash
        }
    }
}
