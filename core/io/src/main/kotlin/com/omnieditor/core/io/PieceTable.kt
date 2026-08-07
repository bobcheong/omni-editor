package com.omnieditor.core.io

/**
 * A piece table for efficient text editing (OE-EDT-3).
 *
 * Two buffers:
 * - **original**: the initial file content (read-only, never modified)
 * - **additions**: appended text from edits (append-only)
 *
 * A list of **pieces** describes how to reconstruct the document by referencing
 * spans in either buffer. Edits split and insert pieces — never copy text.
 * The document is materialised only on save.
 *
 * This allows editing 300MB+ files without loading the whole file: only the
 * original buffer is memory-mapped, and only changed regions are in the additions buffer.
 */
class PieceTable private constructor(
    private val original: String,
    private val additions: StringBuilder,
    private val pieces: MutableList<Piece>,
) {
    /** Which buffer a piece refers to. */
    enum class Buffer { ORIGINAL, ADDITIONS }

    data class Piece(val buffer: Buffer, val start: Int, val length: Int)

    /** Total document length in characters. */
    val length: Int get() = pieces.sumOf { it.length }

    /** Total number of lines (at least 1). */
    val lineCount: Int get() {
        var count = 1
        for (piece in pieces) {
            val buf = bufferOf(piece)
            for (i in piece.start until piece.start + piece.length) {
                if (buf[i] == '\n') count++
            }
        }
        return count
    }

    /**
     * Get the full document text. Use sparingly — for save/materialise only.
     * For line access, use [line] which is O(pieces) not O(content).
     */
    fun text(): String {
        val sb = StringBuilder(length)
        for (piece in pieces) {
            sb.append(bufferOf(piece), piece.start, piece.start + piece.length)
        }
        return sb.toString()
    }

    /**
     * Get line content by 0-based line index.
     */
    fun line(lineIndex: Int): String {
        var currentLine = 0
        val sb = StringBuilder()

        for (piece in pieces) {
            val buf = bufferOf(piece)
            for (i in piece.start until piece.start + piece.length) {
                if (currentLine == lineIndex) {
                    if (buf[i] == '\n') return sb.toString()
                    sb.append(buf[i])
                } else if (buf[i] == '\n') {
                    currentLine++
                }
            }
        }

        return if (currentLine == lineIndex) sb.toString() else ""
    }

    /**
     * Insert text at the given character offset.
     * Returns the edit for undo tracking.
     */
    fun insert(offset: Int, text: String): EditRecord {
        require(offset in 0..length) { "Offset $offset out of range [0, $length]" }
        if (text.isEmpty()) return EditRecord(EditRecord.Type.INSERT, offset, "", text)

        val addStart = additions.length
        additions.append(text)
        val newPiece = Piece(Buffer.ADDITIONS, addStart, text.length)

        insertPieceAt(offset, newPiece)

        return EditRecord(EditRecord.Type.INSERT, offset, "", text)
    }

    /**
     * Delete [count] characters starting at [offset].
     */
    fun delete(offset: Int, count: Int): EditRecord {
        require(offset >= 0 && offset + count <= length) {
            "Delete range [$offset, ${offset + count}) out of bounds [0, $length)"
        }
        if (count == 0) return EditRecord(EditRecord.Type.DELETE, offset, "", "")

        val deleted = substring(offset, offset + count)
        deletePieceRange(offset, count)

        return EditRecord(EditRecord.Type.DELETE, offset, deleted, "")
    }

    /**
     * Replace [count] characters at [offset] with [text].
     */
    fun replace(offset: Int, count: Int, text: String): EditRecord {
        val deleted = if (count > 0) substring(offset, offset + count) else ""
        if (count > 0) deletePieceRange(offset, count)
        if (text.isNotEmpty()) {
            val addStart = additions.length
            additions.append(text)
            insertPieceAt(offset, Piece(Buffer.ADDITIONS, addStart, text.length))
        }
        return EditRecord(EditRecord.Type.REPLACE, offset, deleted, text)
    }

    /**
     * Extract a substring from the piece table.
     */
    fun substring(start: Int, end: Int): String {
        require(start in 0..end && end <= length) {
            "Substring range [$start, $end) out of bounds [0, $length)"
        }
        val sb = StringBuilder(end - start)
        var pos = 0
        for (piece in pieces) {
            val pieceEnd = pos + piece.length
            if (pieceEnd <= start) { pos = pieceEnd; continue }
            if (pos >= end) break
            val from = maxOf(start - pos, 0)
            val to = minOf(end - pos, piece.length)
            sb.append(bufferOf(piece), piece.start + from, piece.start + to)
            pos = pieceEnd
        }
        return sb.toString()
    }

    private fun bufferOf(piece: Piece): CharSequence {
        return when (piece.buffer) {
            Buffer.ORIGINAL -> original
            Buffer.ADDITIONS -> additions
        }
    }

    private fun insertPieceAt(offset: Int, newPiece: Piece) {
        var pos = 0
        for (i in pieces.indices) {
            val piece = pieces[i]
            if (pos + piece.length >= offset) {
                val splitAt = offset - pos
                if (splitAt == 0) {
                    pieces.add(i, newPiece)
                } else if (splitAt == piece.length) {
                    pieces.add(i + 1, newPiece)
                } else {
                    // Split the piece
                    val left = Piece(piece.buffer, piece.start, splitAt)
                    val right = Piece(piece.buffer, piece.start + splitAt, piece.length - splitAt)
                    pieces[i] = left
                    pieces.add(i + 1, newPiece)
                    pieces.add(i + 2, right)
                }
                return
            }
            pos += piece.length
        }
        // Append at end
        pieces.add(newPiece)
    }

    private fun deletePieceRange(offset: Int, count: Int) {
        var remaining = count
        var pos = 0
        var i = 0

        while (i < pieces.size && remaining > 0) {
            val piece = pieces[i]
            val pieceEnd = pos + piece.length

            if (pieceEnd <= offset) {
                pos = pieceEnd
                i++
                continue
            }

            val deleteStart = maxOf(offset - pos, 0)
            val deleteEnd = minOf(offset + count - pos, piece.length)
            val deleteLen = deleteEnd - deleteStart

            if (deleteStart == 0 && deleteLen == piece.length) {
                // Remove entire piece
                pieces.removeAt(i)
                remaining -= deleteLen
                // don't increment i
            } else if (deleteStart == 0) {
                // Trim from the left
                pieces[i] = Piece(piece.buffer, piece.start + deleteLen, piece.length - deleteLen)
                remaining -= deleteLen
                i++
            } else if (deleteEnd == piece.length) {
                // Trim from the right
                pieces[i] = Piece(piece.buffer, piece.start, deleteStart)
                remaining -= deleteLen
                i++
            } else {
                // Split: keep left, delete middle, keep right
                val left = Piece(piece.buffer, piece.start, deleteStart)
                val right = Piece(piece.buffer, piece.start + deleteEnd, piece.length - deleteEnd)
                pieces[i] = left
                pieces.add(i + 1, right)
                remaining -= deleteLen
                i += 2
            }

            pos = pieceEnd
        }
    }

    companion object {
        /** Create a piece table from initial content. */
        fun create(content: String = ""): PieceTable {
            val pieces = mutableListOf<Piece>()
            if (content.isNotEmpty()) {
                pieces.add(Piece(Buffer.ORIGINAL, 0, content.length))
            }
            return PieceTable(content, StringBuilder(), pieces)
        }
    }
}

/**
 * A record of a single edit operation, sufficient to undo/redo it.
 */
data class EditRecord(
    val type: Type,
    val offset: Int,
    val deleted: String,
    val inserted: String,
) {
    enum class Type { INSERT, DELETE, REPLACE }
}
