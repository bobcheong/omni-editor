package com.omnieditor.core.io

import com.omnieditor.core.model.LineEnding
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

class FileIndexerTest {

    private fun tempFile(content: ByteArray): File {
        val f = File.createTempFile("omni-test-", ".txt")
        f.deleteOnExit()
        f.writeBytes(content)
        return f
    }

    @Test
    fun `indexes a simple text file`() = runTest {
        val file = tempFile("hello\nworld\n".toByteArray())
        val result = FileIndexer.index(file)
        result.index.lineCount shouldBe 2
        result.encoding.charset shouldBe "UTF-8"
        result.index.lineEnding shouldBe LineEnding.LF
        result.index.length(0) shouldBe 5
        result.index.length(1) shouldBe 5
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
        result.index.lineCount shouldBe 2
    }

    @Test
    fun `reports progress during indexing`() = runTest {
        val content = (0 until 5000).joinToString("\n") { "line $it" } + "\n"
        val file = tempFile(content.toByteArray())
        val progressCalls = mutableListOf<com.omnieditor.core.model.Progress>()
        FileIndexer.index(file) { progressCalls.add(it) }
        progressCalls.isNotEmpty() shouldBe true
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
