package com.omnieditor.core.io

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel
import java.nio.charset.Charset

/**
 * A piece table backed by a [FileChannel] original buffer (ADR-015, F-03).
 *
 * Analogous to [PieceTable] but the original buffer is a file on disk instead of
 * a [String] in memory. This enables editing files in the INDEXED_EDITABLE tier
 * (16–256 MiB, UTF-8/ASCII only) without loading the entire file into heap.
 *
 * Three buffer types:
 * - **CHANNEL**: references a byte range in the file channel, decoded on demand
 * - **ADDITIONS**: append-only StringBuilder for new content (same as PieceTable)
 * - **ORIGINAL**: not used by this class (exists for PieceTable compatibility)
 *
 * Uses an augmented AVL tree identical in structure to [PieceTable]. The critical
 * difference is that CHANNEL pieces store a byte range and must be decoded to get
 * character content. An LRU cache (capacity 2048) avoids repeated channel reads.
 *
 * [LineIndex.offset] values are file-absolute byte positions (starting at 0 + BOM).
 * [bomLength] is stored to allow [streamPieces] to emit the BOM correctly, but the
 * channel regions reference file-absolute byte positions and are read directly.
 */
class ChannelPieceTable(
    private val channel: FileChannel,
    private val lineIndex: LineIndex,
    private val charset: Charset,
    private val bomLength: Int,
) {
    private val additions = StringBuilder()

    /** Side table mapping CHANNEL piece indices to byte regions (file-absolute offsets). */
    data class ChannelRegion(val byteOffset: Long, val byteLength: Int)

    private val channelRegions = ArrayList<ChannelRegion>()

    // ── AVL tree node ──

    /** Buffer type for a piece. */
    enum class Buffer { CHANNEL, ADDITIONS }

    data class Piece(val buffer: Buffer, val start: Int, val length: Int)

    private class Node(
        var piece: Piece,
        /** Offsets of '\n' chars relative to piece start, sorted ascending. */
        var nlOffsets: IntArray,
        var charCount: Int,   // total chars in subtree
        var newlineCount: Int, // total newlines in subtree
        var height: Int = 1,
        var left: Node? = null,
        var right: Node? = null,
    ) {
        val pieceNewlines: Int get() = nlOffsets.size
    }

    private var root: Node? = null
    private var lastInsertAdditionsEnd: Int = -1

    /** Total document length in characters — O(1). */
    val length: Int get() = root?.charCount ?: 0

    /** Total number of lines (at least 1) — O(1). */
    val lineCount: Int get() = (root?.newlineCount ?: 0) + 1

    // ── LRU decode cache — keyed by region index, capacity 2048 ──

    private val cache = object : LinkedHashMap<Int, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean =
            size > 2048
    }

    init {
        // Build initial piece tree from LineIndex.
        // Strategy: for each line i, create one CHANNEL piece spanning line content
        // plus its terminator bytes (for all lines except possibly the last).
        // This covers the entire file with CHANNEL pieces, with newlines embedded.

        val totalLines = lineIndex.lineCount.toInt()

        for (i in 0 until totalLines) {
            val contentOffset = lineIndex.offset(i.toLong())
            val contentLen = lineIndex.length(i.toLong())

            if (i < totalLines - 1) {
                // Include terminator bytes: span from this line's content start to next line's start
                val nextOffset = lineIndex.offset((i + 1).toLong())
                val segmentLen = (nextOffset - contentOffset).toInt()
                if (segmentLen > 0) {
                    val regionIdx = channelRegions.size
                    channelRegions.add(ChannelRegion(contentOffset, segmentLen))
                    val decoded = decodeRegion(regionIdx)
                    val nlOffsets = buildNlOffsets(decoded)
                    val node = Node(
                        Piece(Buffer.CHANNEL, regionIdx, decoded.length),
                        nlOffsets, decoded.length, nlOffsets.size,
                    )
                    root = insertAsRightmost(root, node)
                }
            } else {
                // Last line — no terminator after it; only add if non-empty
                if (contentLen > 0) {
                    val regionIdx = channelRegions.size
                    channelRegions.add(ChannelRegion(contentOffset, contentLen))
                    val decoded = decodeRegion(regionIdx)
                    val nlOffsets = buildNlOffsets(decoded)
                    val node = Node(
                        Piece(Buffer.CHANNEL, regionIdx, decoded.length),
                        nlOffsets, decoded.length, nlOffsets.size,
                    )
                    root = insertAsRightmost(root, node)
                }
                // Empty final line (file ends with newline): the newline is already
                // embedded in the previous segment's piece; nothing to add.
            }
        }
    }

    // ── Decode ──

    /**
     * Decode a channel region to a String, using the LRU cache.
     * Reads directly from the file-absolute byte offset stored in the region.
     */
    private fun decodeRegion(regionIdx: Int): String {
        cache[regionIdx]?.let { return it }
        val region = channelRegions[regionIdx]
        if (region.byteLength == 0) {
            cache[regionIdx] = ""
            return ""
        }
        val buf = ByteBuffer.allocate(region.byteLength)
        channel.read(buf, region.byteOffset)
        buf.flip()
        val decoded = charset.decode(buf).toString()
        cache[regionIdx] = decoded
        return decoded
    }

    /** Get a substring from a piece. */
    private fun pieceSubstring(piece: Piece, from: Int, to: Int): String {
        return when (piece.buffer) {
            Buffer.CHANNEL -> decodeRegion(piece.start).substring(from, to)
            Buffer.ADDITIONS -> additions.substring(piece.start + from, piece.start + to)
        }
    }

    /** Get char at position within a piece. */
    private fun pieceCharAt(piece: Piece, offset: Int): Char {
        return when (piece.buffer) {
            Buffer.CHANNEL -> decodeRegion(piece.start)[offset]
            Buffer.ADDITIONS -> additions[piece.start + offset]
        }
    }

    // ── AVL helpers (identical structure to PieceTable) ──

    private fun height(node: Node?): Int = node?.height ?: 0
    private fun charCount(node: Node?): Int = node?.charCount ?: 0
    private fun newlineCount(node: Node?): Int = node?.newlineCount ?: 0
    private fun balanceFactor(node: Node): Int = height(node.left) - height(node.right)

    private fun update(node: Node) {
        node.height = 1 + maxOf(height(node.left), height(node.right))
        node.charCount = node.piece.length + charCount(node.left) + charCount(node.right)
        node.newlineCount = node.pieceNewlines + newlineCount(node.left) + newlineCount(node.right)
    }

    private fun rotateRight(y: Node): Node {
        val x = y.left!!; y.left = x.right; x.right = y; update(y); update(x); return x
    }

    private fun rotateLeft(x: Node): Node {
        val y = x.right!!; x.right = y.left; y.left = x; update(x); update(y); return y
    }

    private fun balance(node: Node): Node {
        update(node)
        val bf = balanceFactor(node)
        if (bf > 1) {
            if (balanceFactor(node.left!!) < 0) node.left = rotateLeft(node.left!!)
            return rotateRight(node)
        }
        if (bf < -1) {
            if (balanceFactor(node.right!!) > 0) node.right = rotateRight(node.right!!)
            return rotateLeft(node)
        }
        return node
    }

    // ── Node construction ──

    private fun buildNlOffsets(text: CharSequence): IntArray {
        val offsets = mutableListOf<Int>()
        for (i in text.indices) { if (text[i] == '\n') offsets.add(i) }
        return offsets.toIntArray()
    }

    private fun buildNlOffsetsForAdditions(piece: Piece): IntArray {
        val offsets = mutableListOf<Int>()
        for (i in 0 until piece.length) {
            if (additions[piece.start + i] == '\n') offsets.add(i)
        }
        return offsets.toIntArray()
    }

    private fun makeNode(piece: Piece): Node {
        val nlo = when (piece.buffer) {
            Buffer.CHANNEL -> buildNlOffsets(decodeRegion(piece.start))
            Buffer.ADDITIONS -> buildNlOffsetsForAdditions(piece)
        }
        return Node(piece, nlo, piece.length, nlo.size)
    }

    private fun makeNodeFast(piece: Piece, nlOffsets: IntArray): Node =
        Node(piece, nlOffsets, piece.length, nlOffsets.size)

    private fun splitNlOffsets(nlOffsets: IntArray, splitAt: Int): Pair<IntArray, IntArray> {
        var lo = 0; var hi = nlOffsets.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (nlOffsets[mid] < splitAt) lo = mid + 1 else hi = mid }
        val leftOffsets = nlOffsets.copyOfRange(0, lo)
        val rightOffsets = IntArray(nlOffsets.size - lo) { nlOffsets[lo + it] - splitAt }
        return Pair(leftOffsets, rightOffsets)
    }

    // ── Public API ──

    /**
     * Get line content by 0-based line index — O(log p + log k + line length).
     */
    fun line(lineIndex: Int): String {
        val startOffset = lineToOffset(lineIndex)
        if (startOffset >= length) return ""
        val endOffset = findNextNewline(startOffset)
        return substring(startOffset, endOffset)
    }

    /**
     * Get the character offset of the start of line [lineIndex] — O(log p + log k).
     */
    fun lineToOffset(lineIndex: Int): Int {
        if (lineIndex <= 0) return 0
        var remaining = lineIndex; var offset = 0; var node = root
        while (node != null) {
            val leftNl = newlineCount(node.left); val leftChars = charCount(node.left)
            if (remaining <= leftNl) {
                node = node.left
            } else {
                offset += leftChars; remaining -= leftNl
                if (remaining <= node.pieceNewlines) {
                    return offset + node.nlOffsets[remaining - 1] + 1
                }
                offset += node.piece.length; remaining -= node.pieceNewlines; node = node.right
            }
        }
        return length
    }

    /**
     * Get the line index containing character offset [charOffset] — O(log p + log k).
     */
    fun offsetToLine(charOffset: Int): Int {
        if (charOffset <= 0) return 0
        val clamped = minOf(charOffset, length)
        var remaining = clamped; var linesBefore = 0; var node = root
        while (node != null) {
            val leftChars = charCount(node.left); val leftNl = newlineCount(node.left)
            if (remaining < leftChars) {
                node = node.left
            } else if (remaining < leftChars + node.piece.length) {
                linesBefore += leftNl
                linesBefore += countNewlinesBefore(node.nlOffsets, remaining - leftChars)
                return linesBefore
            } else {
                linesBefore += leftNl + node.pieceNewlines
                remaining -= leftChars + node.piece.length; node = node.right
            }
        }
        return linesBefore
    }

    private fun countNewlinesBefore(nlOffsets: IntArray, inPieceOffset: Int): Int {
        var lo = 0; var hi = nlOffsets.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (nlOffsets[mid] < inPieceOffset) lo = mid + 1 else hi = mid }
        return lo
    }

    /**
     * Get the character at the given offset — O(log p).
     */
    fun charAt(offset: Int): Char {
        require(offset in 0 until length) { "Offset $offset out of range [0, $length)" }
        var remaining = offset; var node = root
        while (node != null) {
            val leftChars = charCount(node.left)
            when {
                remaining < leftChars -> node = node.left
                remaining < leftChars + node.piece.length ->
                    return pieceCharAt(node.piece, remaining - leftChars)
                else -> { remaining -= leftChars + node.piece.length; node = node.right }
            }
        }
        throw IndexOutOfBoundsException("Offset $offset not found")
    }

    /**
     * Insert text at the given character offset.
     */
    fun insert(offset: Int, text: String): EditRecord {
        require(offset in 0..length) { "Offset $offset out of range [0, $length]" }
        if (text.isEmpty()) return EditRecord(EditRecord.Type.INSERT, offset, "", text)
        val addStart = additions.length
        additions.append(text)
        val newPiece = Piece(Buffer.ADDITIONS, addStart, text.length)
        val newNode = makeNode(newPiece)
        root = insertPieceAtOffset(root, offset, newNode)
        lastInsertAdditionsEnd = addStart + text.length
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
        root = deleteRange(root, offset, count)
        lastInsertAdditionsEnd = -1
        return EditRecord(EditRecord.Type.DELETE, offset, deleted, "")
    }

    /**
     * Replace [count] characters at [offset] with [text].
     */
    fun replace(offset: Int, count: Int, text: String): EditRecord {
        val deleted = if (count > 0) substring(offset, offset + count) else ""
        if (count > 0) { root = deleteRange(root, offset, count); lastInsertAdditionsEnd = -1 }
        if (text.isNotEmpty()) {
            val addStart = additions.length
            additions.append(text)
            val newNode = makeNode(Piece(Buffer.ADDITIONS, addStart, text.length))
            root = insertPieceAtOffset(root, offset, newNode)
            lastInsertAdditionsEnd = addStart + text.length
        }
        return EditRecord(EditRecord.Type.REPLACE, offset, deleted, text)
    }

    /**
     * Extract a substring — O(log p + k) where k = result length.
     */
    fun substring(start: Int, end: Int): String {
        require(start in 0..end && end <= length) {
            "Substring range [$start, $end) out of bounds [0, $length)"
        }
        val sb = StringBuilder(end - start)
        substringInOrder(root, start, end, sb)
        return sb.toString()
    }

    private fun substringInOrder(node: Node?, start: Int, end: Int, sb: StringBuilder) {
        if (node == null || start >= end) return
        val leftChars = charCount(node.left)
        val pieceLen = node.piece.length
        val nodeEnd = leftChars + pieceLen
        if (start < leftChars) substringInOrder(node.left, start, minOf(end, leftChars), sb)
        if (start < nodeEnd && end > leftChars) {
            val from = maxOf(start - leftChars, 0)
            val to = minOf(end - leftChars, pieceLen)
            sb.append(pieceSubstring(node.piece, from, to))
        }
        if (end > nodeEnd) substringInOrder(node.right, start - nodeEnd, end - nodeEnd, sb)
    }

    /**
     * Get the full document text.
     * Throws [UnsupportedOperationException] for large files (use [streamPieces] instead).
     */
    fun text(): String {
        if (length > 64 * 1024 * 1024) {
            throw UnsupportedOperationException(
                "text() is not supported for large files; use streamPieces() instead"
            )
        }
        return substring(0, length)
    }

    // ── Newline search ──

    private fun findNextNewline(from: Int): Int = findNewlineInTree(root, from) ?: length

    private fun findNewlineInTree(node: Node?, offset: Int): Int? {
        if (node == null) return null
        val leftChars = charCount(node.left); val pieceLen = node.piece.length
        if (offset < leftChars) {
            findNewlineInTree(node.left, offset)?.let { return it }
            firstNewlineInPiece(node, 0)?.let { return leftChars + it }
            return findNewlineInTree(node.right, 0)?.let { it + leftChars + pieceLen }
        }
        if (offset < leftChars + pieceLen) {
            firstNewlineInPiece(node, offset - leftChars)?.let { return leftChars + it }
            return findNewlineInTree(node.right, 0)?.let { it + leftChars + pieceLen }
        }
        return findNewlineInTree(node.right, offset - leftChars - pieceLen)?.let { it + leftChars + pieceLen }
    }

    private fun firstNewlineInPiece(node: Node, fromInPiece: Int): Int? {
        val nlo = node.nlOffsets; if (nlo.isEmpty()) return null
        var lo = 0; var hi = nlo.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (nlo[mid] < fromInPiece) lo = mid + 1 else hi = mid }
        return if (lo < nlo.size) nlo[lo] else null
    }

    // ── Tree insertion/deletion ──

    private fun insertPieceAtOffset(node: Node?, offset: Int, newNode: Node): Node {
        if (node == null) return newNode
        val leftChars = charCount(node.left)
        if (offset <= leftChars) {
            if (offset == leftChars) node.left = insertAsRightmost(node.left, newNode)
            else node.left = insertPieceAtOffset(node.left, offset, newNode)
            return balance(node)
        }
        val nodeEnd = leftChars + node.piece.length
        if (offset >= nodeEnd) {
            node.right = insertPieceAtOffset(node.right, offset - nodeEnd, newNode)
            return balance(node)
        }
        // offset falls within this node's piece — split it
        val splitAt = offset - leftChars
        val piece = node.piece
        val (leftPiece, rightPiece) = splitPiece(piece, splitAt)
        val (leftNlo, rightNlo) = splitNlOffsets(node.nlOffsets, splitAt)
        node.piece = leftPiece; node.nlOffsets = leftNlo
        val rightNode = makeNodeFast(rightPiece, rightNlo)
        node.right = insertAsLeftmost(node.right, rightNode)
        node.right = insertAsLeftmost(node.right, newNode)
        return balance(node)
    }

    /**
     * Split a piece at character position [splitAt].
     * For CHANNEL pieces: decode, find the char boundary, re-encode left portion to
     * compute the byte split point, register two new ChannelRegions, cache both halves.
     */
    private fun splitPiece(piece: Piece, splitAt: Int): Pair<Piece, Piece> {
        return when (piece.buffer) {
            Buffer.ADDITIONS -> {
                Pair(
                    Piece(Buffer.ADDITIONS, piece.start, splitAt),
                    Piece(Buffer.ADDITIONS, piece.start + splitAt, piece.length - splitAt),
                )
            }
            Buffer.CHANNEL -> {
                val region = channelRegions[piece.start]
                val decoded = decodeRegion(piece.start)
                val leftChars = decoded.substring(0, splitAt)
                val leftByteLen = leftChars.toByteArray(charset).size
                val rightByteLen = region.byteLength - leftByteLen

                // Invalidate the combined-region cache entry (its index is now stale)
                cache.remove(piece.start)

                val leftRegionIdx = channelRegions.size
                channelRegions.add(ChannelRegion(region.byteOffset, leftByteLen))
                cache[leftRegionIdx] = leftChars

                val rightRegionIdx = channelRegions.size
                val rightChars = decoded.substring(splitAt)
                channelRegions.add(ChannelRegion(region.byteOffset + leftByteLen, rightByteLen))
                cache[rightRegionIdx] = rightChars

                Pair(
                    Piece(Buffer.CHANNEL, leftRegionIdx, splitAt),
                    Piece(Buffer.CHANNEL, rightRegionIdx, piece.length - splitAt),
                )
            }
        }
    }

    private fun insertAsLeftmost(node: Node?, newNode: Node): Node {
        if (node == null) return newNode
        node.left = insertAsLeftmost(node.left, newNode)
        return balance(node)
    }

    private fun insertAsRightmost(node: Node?, newNode: Node): Node {
        if (node == null) return newNode
        node.right = insertAsRightmost(node.right, newNode)
        return balance(node)
    }

    private fun deleteRange(node: Node?, offset: Int, count: Int): Node? {
        if (node == null || count == 0) return node
        val leftChars = charCount(node.left); val pieceLen = node.piece.length; val nodeEnd = leftChars + pieceLen
        if (offset + count <= leftChars) {
            node.left = deleteRange(node.left, offset, count); return balance(node)
        }
        if (offset >= nodeEnd) {
            node.right = deleteRange(node.right, offset - nodeEnd, count); return balance(node)
        }
        var result: Node? = node; var remaining = count; var currentOffset = offset
        if (currentOffset < leftChars) {
            val leftDelete = leftChars - currentOffset
            node.left = deleteRange(node.left, currentOffset, leftDelete)
            remaining -= leftDelete; currentOffset = leftChars
        }
        if (remaining > 0 && currentOffset < nodeEnd) {
            val inPiece = currentOffset - leftChars
            val deleteInPiece = minOf(remaining, pieceLen - inPiece)
            if (inPiece == 0 && deleteInPiece == pieceLen) {
                result = merge(node.left, node.right); remaining -= deleteInPiece
                if (remaining > 0) result = deleteRange(result, charCount(node.left), remaining)
                return result
            } else if (inPiece == 0) {
                val (_, rightPiece) = splitPiece(node.piece, deleteInPiece)
                node.nlOffsets = sliceNlOffsetsFrom(node.nlOffsets, deleteInPiece)
                node.piece = rightPiece; remaining -= deleteInPiece
            } else if (inPiece + deleteInPiece == pieceLen) {
                val (leftPiece, _) = splitPiece(node.piece, inPiece)
                node.nlOffsets = sliceNlOffsetsTo(node.nlOffsets, inPiece)
                node.piece = leftPiece; remaining -= deleteInPiece
            } else {
                val (leftPiece, tmpRight) = splitPiece(node.piece, inPiece)
                val (_, rightPiece) = splitPiece(tmpRight, deleteInPiece)
                val leftNlo = sliceNlOffsetsTo(node.nlOffsets, inPiece)
                val rightNlo = sliceNlOffsetsFrom(node.nlOffsets, inPiece + deleteInPiece)
                node.piece = leftPiece; node.nlOffsets = leftNlo
                val rightNode = makeNodeFast(rightPiece, rightNlo)
                node.right = insertAsLeftmost(node.right, rightNode)
                remaining -= deleteInPiece
            }
        }
        if (remaining > 0) node.right = deleteRange(node.right, 0, remaining)
        return if (result === node) balance(node) else result
    }

    private fun sliceNlOffsetsTo(nlOffsets: IntArray, cutoff: Int): IntArray {
        var lo = 0; var hi = nlOffsets.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (nlOffsets[mid] < cutoff) lo = mid + 1 else hi = mid }
        return nlOffsets.copyOfRange(0, lo)
    }

    private fun sliceNlOffsetsFrom(nlOffsets: IntArray, cutoff: Int): IntArray {
        var lo = 0; var hi = nlOffsets.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (nlOffsets[mid] < cutoff) lo = mid + 1 else hi = mid }
        return IntArray(nlOffsets.size - lo) { nlOffsets[lo + it] - cutoff }
    }

    private fun merge(left: Node?, right: Node?): Node? {
        if (left == null) return right; if (right == null) return left
        val (newLeft, maxNode) = removeMax(left)
        maxNode.left = newLeft; maxNode.right = right; return balance(maxNode)
    }

    private fun removeMax(node: Node): Pair<Node?, Node> {
        if (node.right == null) {
            val d = node; val r = node.left; d.left = null; return Pair(r, d)
        }
        val (newRight, maxNode) = removeMax(node.right!!)
        node.right = newRight; return Pair(balance(node), maxNode)
    }

    /**
     * Stream all pieces to [output] for materialise / save operations.
     * - BOM bytes are emitted first if [bomBytes] is non-null.
     * - CHANNEL pieces are copied as raw bytes (no decode/re-encode round-trip).
     * - ADDITIONS pieces are encoded and written.
     */
    fun streamPieces(output: WritableByteChannel, bomBytes: ByteArray?) {
        if (bomBytes != null) output.write(ByteBuffer.wrap(bomBytes))
        streamInOrder(root, output)
    }

    private fun streamInOrder(node: Node?, output: WritableByteChannel) {
        if (node == null) return
        streamInOrder(node.left, output)
        when (node.piece.buffer) {
            Buffer.CHANNEL -> {
                val region = channelRegions[node.piece.start]
                if (region.byteLength > 0) {
                    val buf = ByteBuffer.allocate(region.byteLength)
                    channel.read(buf, region.byteOffset)
                    buf.flip()
                    output.write(buf)
                }
            }
            Buffer.ADDITIONS -> {
                val text = additions.substring(node.piece.start, node.piece.start + node.piece.length)
                output.write(ByteBuffer.wrap(text.toByteArray(charset)))
            }
        }
        streamInOrder(node.right, output)
    }
}
