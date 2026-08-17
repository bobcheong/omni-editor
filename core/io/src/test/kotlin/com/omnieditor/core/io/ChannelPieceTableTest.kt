package com.omnieditor.core.io

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.comparables.shouldBeLessThan
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking

class ChannelPieceTableTest {

    private lateinit var tempFile: File
    private lateinit var raf: RandomAccessFile
    private lateinit var channel: FileChannel

    private fun createTable(content: String): ChannelPieceTable {
        tempFile = File.createTempFile("channel-pt-test", ".txt")
        tempFile.writeText(content, Charsets.UTF_8)
        raf = RandomAccessFile(tempFile, "r")
        channel = raf.channel
        val indexResult = runBlocking { FileIndexer.index(tempFile) }
        return ChannelPieceTable(channel, indexResult.index, Charsets.UTF_8, indexResult.encoding.bomLength)
    }

    @After
    fun tearDown() {
        if (::channel.isInitialized) channel.close()
        if (::raf.isInitialized) raf.close()
        if (::tempFile.isInitialized) tempFile.delete()
    }

    // ── Parity with PieceTableTest: same tests, channel-backed ──

    @Test
    fun `empty table has zero length`() {
        val pt = createTable("")
        pt.length shouldBe 0
        pt.lineCount shouldBe 1
    }

    @Test
    fun `create from content`() {
        val pt = createTable("hello world")
        pt.substring(0, pt.length) shouldBe "hello world"
        pt.length shouldBe 11
    }

    @Test
    fun `insert at beginning`() {
        val pt = createTable("world")
        pt.insert(0, "hello ")
        pt.substring(0, pt.length) shouldBe "hello world"
    }

    @Test
    fun `insert at end`() {
        val pt = createTable("hello")
        pt.insert(5, " world")
        pt.substring(0, pt.length) shouldBe "hello world"
    }

    @Test
    fun `insert in middle`() {
        val pt = createTable("hllo")
        pt.insert(1, "e")
        pt.substring(0, pt.length) shouldBe "hello"
    }

    @Test
    fun `delete from beginning`() {
        val pt = createTable("hello world")
        pt.delete(0, 6)
        pt.substring(0, pt.length) shouldBe "world"
    }

    @Test
    fun `delete from end`() {
        val pt = createTable("hello world")
        pt.delete(5, 6)
        pt.substring(0, pt.length) shouldBe "hello"
    }

    @Test
    fun `delete from middle`() {
        val pt = createTable("hello world")
        pt.delete(4, 4)
        pt.substring(0, pt.length) shouldBe "hellrld"
    }

    @Test
    fun `replace in middle`() {
        val pt = createTable("hello world")
        pt.replace(6, 5, "there")
        pt.substring(0, pt.length) shouldBe "hello there"
    }

    @Test
    fun `multiple inserts`() {
        val pt = createTable("ac")
        pt.insert(1, "b")
        pt.substring(0, pt.length) shouldBe "abc"
        pt.insert(3, "d")
        pt.substring(0, pt.length) shouldBe "abcd"
        pt.insert(0, "z")
        pt.substring(0, pt.length) shouldBe "zabcd"
    }

    @Test
    fun `line count with newlines`() {
        val pt = createTable("a\nb\nc")
        pt.lineCount shouldBe 3
    }

    @Test
    fun `line access`() {
        val pt = createTable("alpha\nbeta\ngamma")
        pt.line(0) shouldBe "alpha"
        pt.line(1) shouldBe "beta"
        pt.line(2) shouldBe "gamma"
    }

    @Test
    fun `line access after insert`() {
        val pt = createTable("alpha\ngamma")
        pt.insert(6, "beta\n")
        pt.line(0) shouldBe "alpha"
        pt.line(1) shouldBe "beta"
        pt.line(2) shouldBe "gamma"
    }

    @Test
    fun `substring extraction`() {
        val pt = createTable("hello world")
        pt.substring(0, 5) shouldBe "hello"
        pt.substring(6, 11) shouldBe "world"
        pt.substring(3, 8) shouldBe "lo wo"
    }

    @Test
    fun `charAt returns correct characters`() {
        val pt = createTable("hello")
        pt.charAt(0) shouldBe 'h'
        pt.charAt(4) shouldBe 'o'
    }

    @Test
    fun `lineToOffset returns correct offsets`() {
        val pt = createTable("alpha\nbeta\ngamma")
        pt.lineToOffset(0) shouldBe 0
        pt.lineToOffset(1) shouldBe 6
        pt.lineToOffset(2) shouldBe 11
    }

    @Test
    fun `offsetToLine returns correct lines`() {
        val pt = createTable("alpha\nbeta\ngamma")
        pt.offsetToLine(0) shouldBe 0
        pt.offsetToLine(5) shouldBe 0
        pt.offsetToLine(6) shouldBe 1
        pt.offsetToLine(11) shouldBe 2
    }

    @Test
    fun `delete returns correct deleted text`() {
        val pt = createTable("hello world")
        val record = pt.delete(5, 6)
        record.deleted shouldBe " world"
    }

    @Test
    fun `length and lineCount are O(1)`() {
        val pt = createTable("a\nb\nc")
        pt.length shouldBe 5
        pt.lineCount shouldBe 3
        pt.insert(5, "\nd")
        pt.length shouldBe 7
        pt.lineCount shouldBe 4
    }

    // ── Channel-specific tests ──

    @Test
    fun `multi-byte UTF-8 content preserved after edit`() {
        // "café résumé" = 11 chars. Insert at char offset 3 (between 'f' and 'é').
        // 'é' is 2 bytes in UTF-8; the split must fall on a char boundary, not a byte boundary.
        val pt = createTable("café résumé")
        pt.insert(3, " ")
        pt.substring(0, pt.length) shouldBe "caf é résumé"
    }

    @Test
    fun `piece split never breaks multi-byte char`() {
        // 'é' is 2 bytes in UTF-8; split in the middle of the word
        val pt = createTable("résumé")
        pt.insert(1, "X")
        pt.substring(0, pt.length) shouldBe "rXésumé"
        pt.charAt(0) shouldBe 'r'
        pt.charAt(1) shouldBe 'X'
        pt.charAt(2) shouldBe 'é'
    }

    @Test
    fun `CJK characters preserved`() {
        val pt = createTable("你好世界")
        pt.insert(2, "X")
        pt.substring(0, pt.length) shouldBe "你好X世界"
    }

    @Test
    fun `trailing newline produces empty final line`() {
        val pt = createTable("line1\nline2\n")
        pt.lineCount shouldBe 3
        pt.line(2) shouldBe ""
    }

    @Test
    fun `insert preserves line model`() {
        val pt = createTable("a\nb")
        pt.insert(pt.length, "\nc")
        pt.lineCount shouldBe 3
        pt.line(0) shouldBe "a"
        pt.line(1) shouldBe "b"
        pt.line(2) shouldBe "c"
    }
}
