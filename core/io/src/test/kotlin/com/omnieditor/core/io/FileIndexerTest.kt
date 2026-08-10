package com.omnieditor.core.io

import com.omnieditor.core.model.LineEnding
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class FileIndexerTest {

    private fun tempFile(content: ByteArray): File {
        val f = File.createTempFile("omni-test-", ".txt")
        f.deleteOnExit()
        f.writeBytes(content)
        return f
    }

    @Test
    fun `indexes a simple text file`() = runTest {
        // D-7: "hello\nworld\n" has 2 newlines → 3 lines
        val file = tempFile("hello\nworld\n".toByteArray())
        val result = FileIndexer.index(file)
        result.index.lineCount shouldBe 3
        result.encoding.charset shouldBe "UTF-8"
        result.index.lineEnding shouldBe LineEnding.LF
        result.index.length(0) shouldBe 5
        result.index.length(1) shouldBe 5
        result.index.length(2) shouldBe 0 // trailing empty line
    }

    @Test
    fun `indexes empty file`() = runTest {
        val file = tempFile(ByteArray(0))
        val result = FileIndexer.index(file)
        result.index.lineCount shouldBe 1
        result.index.length(0) shouldBe 0
        result.fileSize shouldBe 0
    }

    @Test
    fun `detects UTF-16LE BOM in file`() = runTest {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val text = "hi\n".toByteArray(Charsets.UTF_16LE)
        val file = tempFile(bom + text)
        val result = FileIndexer.index(file)
        result.encoding.charset shouldBe "UTF-16LE"
        result.encoding.bomLength shouldBe 2
    }

    @Test
    fun `detects CRLF line endings in file`() = runTest {
        val file = tempFile("line1\r\nline2\r\n".toByteArray())
        val result = FileIndexer.index(file)
        result.index.lineEnding shouldBe LineEnding.CRLF
        // D-7: "line1\r\nline2\r\n" has 2 newlines → 3 lines
        result.index.lineCount shouldBe 3
    }

    @Test
    fun `reports progress during indexing`() = runTest {
        val content = (0 until 5000).joinToString("\n") { "line $it" } + "\n"
        val file = tempFile(content.toByteArray())
        val progressCalls = mutableListOf<com.omnieditor.core.model.Progress>()
        FileIndexer.index(file) { progressCalls.add(it) }
        progressCalls.isNotEmpty() shouldBe true
    }

    /**
     * R-09: CRLF split across a chunk boundary must not produce a spurious extra line.
     *
     * Uses the injectable chunkSize parameter added by R-09. With chunkSize=16:
     *   - bytes 0-14: 'A' (15 bytes)
     *   - byte 15: '\r'  (last byte of chunk 0)
     *   - byte 16: '\n'  (first byte of chunk 1)
     *   - bytes 17-26: "SecondLine"
     *   - bytes 27-28: '\r\n'
     *
     * Without the fix the '\r' at byte 15 would be treated as a lone CR (one spurious
     * line) and the '\n' at byte 16 as a bare LF (another spurious line), giving 4 lines
     * instead of the correct 3.
     */
    @Test
    fun `R-09 CRLF split across chunk boundary indexes correctly`() = runTest {
        val chunkSize = 16L
        // 15 A's so that '\r' lands at byte index 15 — the last byte of chunk 0.
        val prefix = ByteArray((chunkSize - 1).toInt()) { 'A'.code.toByte() }
        val content = prefix +
            byteArrayOf('\r'.code.toByte(), '\n'.code.toByte()) +
            "SecondLine\r\n".toByteArray()
        val file = tempFile(content)

        val result = FileIndexer.indexLargeFile(file, chunkSize)

        // 3 lines: "AAAAAAAAAAAAAAA", "SecondLine", ""
        result.index.lineCount shouldBe 3
        result.index.lineEnding shouldBe LineEnding.CRLF
        result.index.length(0) shouldBe 15  // 15 A's, no terminator
        result.index.length(1) shouldBe 10  // "SecondLine"
        result.index.length(2) shouldBe 0   // trailing empty line
    }

    @Test
    fun `indexes file with mixed content correctly`() = runTest {
        val content = buildString {
            append("first line\n")
            append("\n") // empty line
            append("  indented\n")
            append("last")
        }
        val file = tempFile(content.toByteArray())
        val result = FileIndexer.index(file)
        result.index.lineCount shouldBe 4
        result.index.length(0) shouldBe 10 // "first line"
        result.index.length(1) shouldBe 0  // empty
        result.index.length(2) shouldBe 10 // "  indented"
        result.index.length(3) shouldBe 4  // "last"
    }
}
