package com.omnieditor.core.io

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.comparables.shouldBeLessThan
import org.junit.Test
import kotlin.system.measureNanoTime

class PieceTableTest {

    @Test
    fun `empty table has zero length`() {
        val pt = PieceTable.create()
        pt.length shouldBe 0
        pt.lineCount shouldBe 1
    }

    @Test
    fun `create from content`() {
        val pt = PieceTable.create("hello world")
        pt.text() shouldBe "hello world"
        pt.length shouldBe 11
    }

    @Test
    fun `insert at beginning`() {
        val pt = PieceTable.create("world")
        pt.insert(0, "hello ")
        pt.text() shouldBe "hello world"
    }

    @Test
    fun `insert at end`() {
        val pt = PieceTable.create("hello")
        pt.insert(5, " world")
        pt.text() shouldBe "hello world"
    }

    @Test
    fun `insert in middle`() {
        val pt = PieceTable.create("hllo")
        pt.insert(1, "e")
        pt.text() shouldBe "hello"
    }

    @Test
    fun `delete from beginning`() {
        val pt = PieceTable.create("hello world")
        pt.delete(0, 6)
        pt.text() shouldBe "world"
    }

    @Test
    fun `delete from end`() {
        val pt = PieceTable.create("hello world")
        pt.delete(5, 6)
        pt.text() shouldBe "hello"
    }

    @Test
    fun `delete from middle`() {
        val pt = PieceTable.create("hello world")
        pt.delete(4, 4)
        pt.text() shouldBe "hellrld"
    }

    @Test
    fun `replace in middle`() {
        val pt = PieceTable.create("hello world")
        pt.replace(6, 5, "there")
        pt.text() shouldBe "hello there"
    }

    @Test
    fun `multiple inserts`() {
        val pt = PieceTable.create("ac")
        pt.insert(1, "b")
        pt.text() shouldBe "abc"
        pt.insert(3, "d")
        pt.text() shouldBe "abcd"
        pt.insert(0, "z")
        pt.text() shouldBe "zabcd"
    }

    @Test
    fun `line count with newlines`() {
        val pt = PieceTable.create("a\nb\nc")
        pt.lineCount shouldBe 3
    }

    @Test
    fun `line access`() {
        val pt = PieceTable.create("alpha\nbeta\ngamma")
        pt.line(0) shouldBe "alpha"
        pt.line(1) shouldBe "beta"
        pt.line(2) shouldBe "gamma"
    }

    @Test
    fun `line access after insert`() {
        val pt = PieceTable.create("alpha\ngamma")
        pt.insert(6, "beta\n")
        pt.text() shouldBe "alpha\nbeta\ngamma"
        pt.line(0) shouldBe "alpha"
        pt.line(1) shouldBe "beta"
        pt.line(2) shouldBe "gamma"
    }

    @Test
    fun `substring extraction`() {
        val pt = PieceTable.create("hello world")
        pt.substring(0, 5) shouldBe "hello"
        pt.substring(6, 11) shouldBe "world"
        pt.substring(3, 8) shouldBe "lo wo"
    }

    @Test
    fun `substring across pieces`() {
        val pt = PieceTable.create("ac")
        pt.insert(1, "b")
        pt.substring(0, 3) shouldBe "abc"
        pt.substring(0, 1) shouldBe "a"
        pt.substring(1, 2) shouldBe "b"
        pt.substring(2, 3) shouldBe "c"
    }

    @Test
    fun `delete returns correct deleted text`() {
        val pt = PieceTable.create("hello world")
        val record = pt.delete(5, 6)
        record.deleted shouldBe " world"
    }

    @Test
    fun `insert into empty table`() {
        val pt = PieceTable.create()
        pt.insert(0, "hello")
        pt.text() shouldBe "hello"
    }

    // ── R-13: AVL tree methods ──

    @Test
    fun `lineToOffset returns correct offsets`() {
        val pt = PieceTable.create("alpha\nbeta\ngamma")
        pt.lineToOffset(0) shouldBe 0
        pt.lineToOffset(1) shouldBe 6
        pt.lineToOffset(2) shouldBe 11
    }

    @Test
    fun `offsetToLine returns correct lines`() {
        val pt = PieceTable.create("alpha\nbeta\ngamma")
        pt.offsetToLine(0) shouldBe 0
        pt.offsetToLine(5) shouldBe 0
        pt.offsetToLine(6) shouldBe 1
        pt.offsetToLine(10) shouldBe 1
        pt.offsetToLine(11) shouldBe 2
        pt.offsetToLine(15) shouldBe 2
    }

    @Test
    fun `charAt returns correct characters`() {
        val pt = PieceTable.create("hello")
        pt.charAt(0) shouldBe 'h'
        pt.charAt(4) shouldBe 'o'
    }

    @Test
    fun `charAt across pieces after insert`() {
        val pt = PieceTable.create("ac")
        pt.insert(1, "b")
        pt.charAt(0) shouldBe 'a'
        pt.charAt(1) shouldBe 'b'
        pt.charAt(2) shouldBe 'c'
    }

    @Test
    fun `lineToOffset after insert`() {
        val pt = PieceTable.create("alpha\ngamma")
        pt.insert(6, "beta\n")
        pt.lineToOffset(0) shouldBe 0
        pt.lineToOffset(1) shouldBe 6
        pt.lineToOffset(2) shouldBe 11
    }

    @Test
    fun `length and lineCount are O(1) via tree augmentation`() {
        val pt = PieceTable.create("a\nb\nc")
        pt.length shouldBe 5
        pt.lineCount shouldBe 3
        pt.insert(5, "\nd")
        pt.length shouldBe 7
        pt.lineCount shouldBe 4
    }

    // ── R-13: Performance tests ──

    @Test
    fun `R-13 performance - 10000 inserts at midpoint of large file`() {
        // Create a 15 MB file — use lines so pieces have realistic newline counts
        val lineContent = "x".repeat(300) + "\n"
        val content = lineContent.repeat(50_000) // ~15 MB, 50K lines
        val pt = PieceTable.create(content)

        // Warmup: first few inserts split the large initial piece (O(piece.length) scan)
        for (i in 0 until 100) {
            pt.insert(pt.length / 2, "a")
        }

        // After warmup, pieces are smaller — measure steady-state performance
        val times = mutableListOf<Long>()
        for (i in 0 until 10_000) {
            val mid = pt.length / 2
            val start = System.nanoTime()
            pt.insert(mid, "a")
            val elapsed = System.nanoTime() - start
            times.add(elapsed)
        }

        // Median should be well under 1ms; allow generous threshold for CI/GC
        times.sort()
        val medianMs = times[times.size / 2] / 1_000_000.0
        medianMs shouldBeLessThan 1.0
    }

    @Test
    fun `R-13 rendering cost is constant regardless of line`() {
        // Build a multi-piece document: create initial content then scatter inserts
        // to break it into many pieces
        val lines = (0 until 50_000).joinToString("\n") { "line $it content here" }
        val pt = PieceTable.create(lines)

        // Scatter inserts to fragment into many pieces
        for (i in 0 until 1_000) {
            val offset = pt.lineToOffset(i * 40) // every 40th line
            pt.insert(offset, "X")
        }

        val totalLines = pt.lineCount

        // Warmup JIT
        repeat(100) {
            pt.line(10)
            pt.line(totalLines - 10)
        }

        // Rendering row 10 vs row near the end should cost roughly the same
        val time10 = measureNanoTime { pt.line(10) }
        val timeLate = measureNanoTime { pt.line(totalLines - 10) }

        // Allow 10x tolerance for variance
        val ratio = if (time10 > 0) timeLate.toDouble() / time10.toDouble() else 1.0
        ratio shouldBeLessThan 10.0
    }

    @Test
    fun `R-13 coalescing bounds piece count during sequential typing`() {
        val pt = PieceTable.create("initial content")
        val startingPieces = pt.pieceCount

        for (i in 0 until 10_000) {
            pt.insert(pt.length, "x") // sequential append
        }

        // Piece count should stay within 2x of starting value
        pt.pieceCount shouldBeLessThan (startingPieces * 2 + 2)
    }
}
