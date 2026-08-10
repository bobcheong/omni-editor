package com.omnieditor.core.io

/**
 * A piece table for efficient text editing (OE-EDT-3).
 *
 * Two buffers:
 * - **original**: the initial file content (read-only, never modified)
 * - **additions**: appended text from edits (append-only)
 *
 * An augmented AVL tree of **pieces** describes how to reconstruct the document
 * by referencing spans in either buffer. Each node stores:
 * - A piece (buffer, start, length)
 * - An IntArray of newline offsets within the piece (for O(log k) within-piece lookup)
 * - Subtree charCount and newlineCount (for O(log p) tree navigation)
 *
 * This gives O(log p + log k) for lineToOffset, offsetToLine, and line access,
 * where p is piece count and k is newlines per piece.
 *
 * Coalescing: sequential appends to the additions buffer extend the last piece
 * rather than creating a new one, keeping piece count bounded during typing.
 */
class PieceTable private constructor(
    private val original: String,
    private val additions: StringBuilder,
) {
    /** Which buffer a piece refers to. */
    enum class Buffer { ORIGINAL, ADDITIONS }

    data class Piece(val buffer: Buffer, val start: Int, val length: Int)

    // ── AVL tree node ──

    private class Node(
        var piece: Piece,
        /** Offsets of '\n' chars relative to piece.start, sorted ascending. */
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

    // Track last insert position for coalescing
    private var lastInsertAdditionsEnd: Int = -1

    /** Total document length in characters — O(1). */
    val length: Int get() = root?.charCount ?: 0

    /** Total number of lines (at least 1) — O(1). */
    val lineCount: Int get() = (root?.newlineCount ?: 0) + 1

    /** Number of pieces in the tree (for testing). */
    val pieceCount: Int get() = countNodes(root)

    private fun countNodes(node: Node?): Int {
        if (node == null) return 0
        return 1 + countNodes(node.left) + countNodes(node.right)
    }

    // ── AVL helpers ──

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
        val x = y.left!!
        y.left = x.right
        x.right = y
        update(y)
        update(x)
        return x
    }

    private fun rotateLeft(x: Node): Node {
        val y = x.right!!
        x.right = y.left
        y.left = x
        update(x)
        update(y)
        return y
    }

    private fun balance(node: Node): Node {
        update(node)
        val bf = balanceFactor(node)
        if (bf > 1) {
            if (balanceFactor(node.left!!) < 0) {
                node.left = rotateLeft(node.left!!)
            }
            return rotateRight(node)
        }
        if (bf < -1) {
            if (balanceFactor(node.right!!) > 0) {
                node.right = rotateRight(node.right!!)
            }
            return rotateLeft(node)
        }
        return node
    }

    // ── Node creation ──

    /** Build newline offset array for a piece by scanning the buffer. */
    private fun buildNlOffsets(piece: Piece): IntArray {
        val buf = bufferOf(piece)
        val offsets = mutableListOf<Int>()
        for (i in piece.start until piece.start + piece.length) {
            if (buf[i] == '\n') offsets.add(i - piece.start)
        }
        return offsets.toIntArray()
    }

    private fun makeNode(piece: Piece): Node {
        val nlo = buildNlOffsets(piece)
        return Node(piece, nlo, piece.length, nlo.size)
    }

    /** Create a node from a piece when we already know the newline offsets. */
    private fun makeNodeFast(piece: Piece, nlOffsets: IntArray): Node {
        return Node(piece, nlOffsets, piece.length, nlOffsets.size)
    }

    /**
     * Split nlOffsets at a given intra-piece character offset.
     * Returns (leftOffsets, rightOffsets) where rightOffsets are rebased to 0.
     */
    private fun splitNlOffsets(nlOffsets: IntArray, splitAt: Int): Pair<IntArray, IntArray> {
        // Binary search for the split point
        var lo = 0
        var hi = nlOffsets.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (nlOffsets[mid] < splitAt) lo = mid + 1 else hi = mid
        }
        val leftOffsets = nlOffsets.copyOfRange(0, lo)
        val rightSize = nlOffsets.size - lo
        val rightOffsets = IntArray(rightSize)
        for (i in 0 until rightSize) {
            rightOffsets[i] = nlOffsets[lo + i] - splitAt
        }
        return Pair(leftOffsets, rightOffsets)
    }

    // ── Core operations ──

    /**
     * Get the full document text. Use sparingly — for save/materialise only.
     */
    fun text(): String {
        val sb = StringBuilder(length)
        appendInOrder(root, sb)
        return sb.toString()
    }

    private fun appendInOrder(node: Node?, sb: StringBuilder) {
        if (node == null) return
        appendInOrder(node.left, sb)
        val buf = bufferOf(node.piece)
        sb.append(buf, node.piece.start, node.piece.start + node.piece.length)
        appendInOrder(node.right, sb)
    }

    /**
     * Get line content by 0-based line index — O(log p + log k + line length).
     */
    fun line(lineIndex: Int): String {
        val startOffset = lineToOffset(lineIndex)
        val docLen = length
        if (startOffset >= docLen) return ""

        // Find the end of this line using newline offset arrays
        val endOffset = findNextNewline(startOffset)
        return substring(startOffset, endOffset)
    }

    /**
     * Find the document offset of the next '\n' at or after [from].
     * Uses nlOffsets arrays for O(log k) within-piece binary search.
     */
    private fun findNextNewline(from: Int): Int {
        return findNewlineInTree(root, from) ?: length
    }

    private fun findNewlineInTree(node: Node?, offset: Int): Int? {
        if (node == null) return null
        val leftChars = charCount(node.left)
        val pieceLen = node.piece.length

        if (offset < leftChars) {
            // Try left subtree first
            val result = findNewlineInTree(node.left, offset)
            if (result != null) return result
            // Try this node
            val inNodeResult = firstNewlineInPiece(node, 0)
            if (inNodeResult != null) return leftChars + inNodeResult
            // Try right subtree
            val rightResult = findNewlineInTree(node.right, 0)
            return rightResult?.let { it + leftChars + pieceLen }
        }

        if (offset < leftChars + pieceLen) {
            // Offset is in this piece
            val inPiece = offset - leftChars
            val inNodeResult = firstNewlineInPiece(node, inPiece)
            if (inNodeResult != null) return leftChars + inNodeResult
            // Try right subtree
            val rightResult = findNewlineInTree(node.right, 0)
            return rightResult?.let { it + leftChars + pieceLen }
        }

        // Offset is in right subtree
        val rightResult = findNewlineInTree(node.right, offset - leftChars - pieceLen)
        return rightResult?.let { it + leftChars + pieceLen }
    }

    /**
     * Find the first newline offset (relative to piece start) at or after [fromInPiece].
     * Uses binary search on nlOffsets — O(log k).
     */
    private fun firstNewlineInPiece(node: Node, fromInPiece: Int): Int? {
        val nlo = node.nlOffsets
        if (nlo.isEmpty()) return null
        // Binary search for first offset >= fromInPiece
        var lo = 0
        var hi = nlo.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (nlo[mid] < fromInPiece) lo = mid + 1 else hi = mid
        }
        return if (lo < nlo.size) nlo[lo] else null
    }

    /**
     * Get the character offset of the start of line `lineIndex` — O(log p + log k).
     */
    fun lineToOffset(lineIndex: Int): Int {
        if (lineIndex <= 0) return 0
        var remaining = lineIndex
        var offset = 0
        var node = root

        while (node != null) {
            val leftNl = newlineCount(node.left)
            val leftChars = charCount(node.left)

            if (remaining <= leftNl) {
                node = node.left
            } else {
                offset += leftChars
                remaining -= leftNl

                if (remaining <= node.pieceNewlines) {
                    // The target newline is in this piece — use nlOffsets O(1) lookup
                    val nlIdx = remaining - 1 // 0-based index into nlOffsets
                    val nlPos = node.nlOffsets[nlIdx] // offset relative to piece.start
                    return offset + nlPos + 1 // +1 to get position after the '\n'
                }

                offset += node.piece.length
                remaining -= node.pieceNewlines
                node = node.right
            }
        }

        return length
    }

    /**
     * Get the line index containing character offset `charOffset` — O(log p + log k).
     */
    fun offsetToLine(charOffset: Int): Int {
        if (charOffset <= 0) return 0
        val clampedOffset = minOf(charOffset, length)
        var remaining = clampedOffset
        var linesBefore = 0
        var node = root

        while (node != null) {
            val leftChars = charCount(node.left)
            val leftNl = newlineCount(node.left)

            if (remaining < leftChars) {
                node = node.left
            } else if (remaining < leftChars + node.piece.length) {
                linesBefore += leftNl
                // Count newlines before inPieceOffset using binary search
                val inPieceOffset = remaining - leftChars
                linesBefore += countNewlinesBefore(node.nlOffsets, inPieceOffset)
                return linesBefore
            } else {
                linesBefore += leftNl + node.pieceNewlines
                remaining -= leftChars + node.piece.length
                node = node.right
            }
        }

        return linesBefore
    }

    /**
     * Count how many entries in nlOffsets are < inPieceOffset. O(log k) via binary search.
     */
    private fun countNewlinesBefore(nlOffsets: IntArray, inPieceOffset: Int): Int {
        var lo = 0
        var hi = nlOffsets.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (nlOffsets[mid] < inPieceOffset) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Get the character at the given offset — O(log p).
     */
    fun charAt(offset: Int): Char {
        require(offset in 0 until length) { "Offset $offset out of range [0, $length)" }
        var remaining = offset
        var node = root

        while (node != null) {
            val leftChars = charCount(node.left)
            if (remaining < leftChars) {
                node = node.left
            } else if (remaining < leftChars + node.piece.length) {
                return bufferOf(node.piece)[node.piece.start + (remaining - leftChars)]
            } else {
                remaining -= leftChars + node.piece.length
                node = node.right
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
        val coalesced = tryCoalesce(offset, text, addStart)
        if (coalesced) {
            return EditRecord(EditRecord.Type.INSERT, offset, "", text)
        }

        additions.append(text)
        val newPiece = Piece(Buffer.ADDITIONS, addStart, text.length)
        val newNode = makeNode(newPiece)

        root = insertPieceAtOffset(root, offset, newNode)
        lastInsertAdditionsEnd = addStart + text.length

        return EditRecord(EditRecord.Type.INSERT, offset, "", text)
    }

    /**
     * Try to coalesce by extending the last piece if the insert is contiguous.
     */
    private fun tryCoalesce(offset: Int, text: String, addStart: Int): Boolean {
        if (lastInsertAdditionsEnd != addStart) return false
        if (root == null) return false
        if (offset == 0) return false

        val result = findPieceAtOffset(root!!, offset - 1) ?: return false
        val (node, localOffset) = result
        val piece = node.piece
        if (piece.buffer != Buffer.ADDITIONS ||
            piece.start + piece.length != addStart ||
            localOffset != piece.length - 1
        ) return false

        // Extend the piece
        additions.append(text)
        val oldLength = piece.length
        val newLength = oldLength + text.length
        node.piece = Piece(Buffer.ADDITIONS, piece.start, newLength)

        // Extend nlOffsets: compute new newline offsets for the appended text
        val newNlOffsets = mutableListOf<Int>()
        for (i in text.indices) {
            if (text[i] == '\n') newNlOffsets.add(oldLength + i)
        }
        if (newNlOffsets.isNotEmpty()) {
            node.nlOffsets = node.nlOffsets + newNlOffsets.toIntArray()
        }

        // Recompute augmentation up the tree
        recomputeAugmentation(root)
        lastInsertAdditionsEnd = addStart + text.length
        return true
    }

    /** Recompute charCount, newlineCount, and height for entire tree. */
    private fun recomputeAugmentation(node: Node?) {
        if (node == null) return
        recomputeAugmentation(node.left)
        recomputeAugmentation(node.right)
        update(node)
    }

    private fun findPieceAtOffset(node: Node, offset: Int): Pair<Node, Int>? {
        val leftChars = charCount(node.left)
        return if (offset < leftChars) {
            node.left?.let { findPieceAtOffset(it, offset) }
        } else if (offset < leftChars + node.piece.length) {
            Pair(node, offset - leftChars)
        } else {
            node.right?.let { findPieceAtOffset(it, offset - leftChars - node.piece.length) }
        }
    }

    /**
     * Insert a new node at the given character offset in the document.
     * May split an existing piece.
     */
    private fun insertPieceAtOffset(node: Node?, offset: Int, newNode: Node): Node {
        if (node == null) return newNode

        val leftChars = charCount(node.left)

        if (offset <= leftChars) {
            if (offset == leftChars) {
                node.left = insertAsRightmost(node.left, newNode)
            } else {
                node.left = insertPieceAtOffset(node.left, offset, newNode)
            }
            return balance(node)
        }

        val nodeEnd = leftChars + node.piece.length

        if (offset >= nodeEnd) {
            node.right = insertPieceAtOffset(node.right, offset - nodeEnd, newNode)
            return balance(node)
        }

        // offset is within this node's piece — split using cached nlOffsets
        val splitAt = offset - leftChars
        val piece = node.piece
        val leftPiece = Piece(piece.buffer, piece.start, splitAt)
        val rightPiece = Piece(piece.buffer, piece.start + splitAt, piece.length - splitAt)

        // Split nlOffsets — O(log k) binary search + O(k) copy
        val (leftNlo, rightNlo) = splitNlOffsets(node.nlOffsets, splitAt)

        node.piece = leftPiece
        node.nlOffsets = leftNlo

        val rightNode = makeNodeFast(rightPiece, rightNlo)

        // Order: leftPiece(this), newNode, rightPiece
        node.right = insertAsLeftmost(node.right, rightNode)
        node.right = insertAsLeftmost(node.right, newNode)

        return balance(node)
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

    private fun deleteRange(node: Node?, offset: Int, count: Int): Node? {
        if (node == null || count == 0) return node
        val leftChars = charCount(node.left)
        val pieceLen = node.piece.length
        val nodeEnd = leftChars + pieceLen

        // Entirely in left subtree
        if (offset + count <= leftChars) {
            node.left = deleteRange(node.left, offset, count)
            return balance(node)
        }

        // Entirely in right subtree
        if (offset >= nodeEnd) {
            node.right = deleteRange(node.right, offset - nodeEnd, count)
            return balance(node)
        }

        // Intersects this node
        var result: Node? = node
        var remaining = count
        var currentOffset = offset

        // Delete from left subtree
        if (currentOffset < leftChars) {
            val leftDelete = leftChars - currentOffset
            node.left = deleteRange(node.left, currentOffset, leftDelete)
            remaining -= leftDelete
            currentOffset = leftChars
        }

        val newLeftChars = charCount(node.left)

        // Delete from this piece
        if (remaining > 0 && currentOffset < nodeEnd) {
            val inPiece = currentOffset - leftChars
            val deleteInPiece = minOf(remaining, pieceLen - inPiece)

            if (inPiece == 0 && deleteInPiece == pieceLen) {
                // Delete entire piece
                result = merge(node.left, node.right)
                remaining -= deleteInPiece
                if (remaining > 0) {
                    result = deleteRange(result, newLeftChars, remaining)
                }
                return result
            } else if (inPiece == 0) {
                // Trim from left
                val piece = node.piece
                val newPiece = Piece(piece.buffer, piece.start + deleteInPiece, piece.length - deleteInPiece)
                // Slice nlOffsets: keep entries >= deleteInPiece, rebase
                node.nlOffsets = sliceNlOffsetsFrom(node.nlOffsets, deleteInPiece)
                node.piece = newPiece
                remaining -= deleteInPiece
            } else if (inPiece + deleteInPiece == pieceLen) {
                // Trim from right
                val piece = node.piece
                val newPiece = Piece(piece.buffer, piece.start, inPiece)
                node.nlOffsets = sliceNlOffsetsTo(node.nlOffsets, inPiece)
                node.piece = newPiece
                remaining -= deleteInPiece
            } else {
                // Split: keep left, delete middle, keep right
                val piece = node.piece
                val leftPart = Piece(piece.buffer, piece.start, inPiece)
                val rightPart = Piece(piece.buffer, piece.start + inPiece + deleteInPiece,
                    piece.length - inPiece - deleteInPiece)

                val leftNlo = sliceNlOffsetsTo(node.nlOffsets, inPiece)
                val rightNlo = sliceNlOffsetsFrom(node.nlOffsets, inPiece + deleteInPiece)

                node.piece = leftPart
                node.nlOffsets = leftNlo
                val rightNode = makeNodeFast(rightPart, rightNlo)
                node.right = insertAsLeftmost(node.right, rightNode)
                remaining -= deleteInPiece
            }
        }

        // Delete from right subtree
        if (remaining > 0) {
            node.right = deleteRange(node.right, 0, remaining)
        }

        return if (result === node) balance(node) else result
    }

    /** Keep nlOffsets entries < cutoff. */
    private fun sliceNlOffsetsTo(nlOffsets: IntArray, cutoff: Int): IntArray {
        var lo = 0
        var hi = nlOffsets.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (nlOffsets[mid] < cutoff) lo = mid + 1 else hi = mid
        }
        return nlOffsets.copyOfRange(0, lo)
    }

    /** Keep nlOffsets entries >= cutoff, rebase to 0. */
    private fun sliceNlOffsetsFrom(nlOffsets: IntArray, cutoff: Int): IntArray {
        var lo = 0
        var hi = nlOffsets.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (nlOffsets[mid] < cutoff) lo = mid + 1 else hi = mid
        }
        val result = IntArray(nlOffsets.size - lo)
        for (i in result.indices) {
            result[i] = nlOffsets[lo + i] - cutoff
        }
        return result
    }

    private fun merge(left: Node?, right: Node?): Node? {
        if (left == null) return right
        if (right == null) return left
        val (newLeft, maxNode) = removeMax(left)
        maxNode.left = newLeft
        maxNode.right = right
        return balance(maxNode)
    }

    private fun removeMax(node: Node): Pair<Node?, Node> {
        if (node.right == null) {
            val detached = node
            val remaining = node.left
            detached.left = null
            return Pair(remaining, detached)
        }
        val (newRight, maxNode) = removeMax(node.right!!)
        node.right = newRight
        return Pair(balance(node), maxNode)
    }

    /**
     * Replace [count] characters at [offset] with [text].
     */
    fun replace(offset: Int, count: Int, text: String): EditRecord {
        val deleted = if (count > 0) substring(offset, offset + count) else ""
        if (count > 0) {
            root = deleteRange(root, offset, count)
            lastInsertAdditionsEnd = -1
        }
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

        if (start < leftChars) {
            substringInOrder(node.left, start, minOf(end, leftChars), sb)
        }

        if (start < nodeEnd && end > leftChars) {
            val buf = bufferOf(node.piece)
            val from = maxOf(start - leftChars, 0)
            val to = minOf(end - leftChars, pieceLen)
            sb.append(buf, node.piece.start + from, node.piece.start + to)
        }

        if (end > nodeEnd) {
            substringInOrder(node.right, start - nodeEnd, end - nodeEnd, sb)
        }
    }

    private fun bufferOf(piece: Piece): CharSequence {
        return when (piece.buffer) {
            Buffer.ORIGINAL -> original
            Buffer.ADDITIONS -> additions
        }
    }

    companion object {
        /** Create a piece table from initial content. */
        fun create(content: String = ""): PieceTable {
            val pt = PieceTable(content, StringBuilder())
            if (content.isNotEmpty()) {
                val piece = Piece(Buffer.ORIGINAL, 0, content.length)
                pt.root = pt.makeNode(piece)
            }
            return pt
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
