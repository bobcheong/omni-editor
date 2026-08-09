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
     * R-09: CRLF split across a chunk boundary is counted as a lone CR (spurious line).
     *
     * Bug location: FileIndexer.indexLargeFile(), line ~136:
     *   val isCrlf = i + 1 < chunkBytes.size && chunkBytes[i] == '\r' && chunkBytes[i+1] == '\n'
     *
     * When '\r' is the last byte of a chunk (i == chunkBytes.size - 1), the check
     * `i + 1 < chunkBytes.size` is false, so isCrlf = false and isCr = true. The '\r'
     * is treated as a lone CR line terminator, creating a spurious empty line. The '\n'
     * at the start of the next chunk is then treated as a second LF terminator, creating
     * another spurious empty line.
     *
     * To trigger the bug, a file must have '\r\n' spanning a 64 MB chunk boundary.
     * The chunk size (64 MB) is a local constant in a private function and is not
     * injectable — there is no public API to set it smaller for testing.
     *
     * This test documents the bug by:
     *   1. Asserting that CHUNK_SIZE_FOR_TEST (the value we need to be injectable) does
     *      not yet exist as a testable constant, causing the assertion to fail.
     *   2. Noting what the fix must do: expose the chunk size so tests can set it to
     *      a small value (e.g. 8 bytes) and create a file with '\r\n' at the boundary.
     *
     * When R-09 is fixed, the chunk size will be made injectable and this test will be
     * rewritten to actually trigger the boundary condition.
     */
    @Test
    fun `R-09 CRLF split across chunk boundary indexes correctly`() = runTest {
        // The chunk size in indexLargeFile is a private local constant (64 MB).
        // It is not exposed as a testable/injectable field on FileIndexer.
        // Until it is injectable, we cannot create a file that exercises the boundary.
        //
        // This assertion documents the requirement: FileIndexer must expose a
        // chunk size that tests can set to a small value. The test fails until R-09
        // makes the chunk size injectable (e.g. via a @VisibleForTesting constant).
        //
        // Expected failure message when the bug is present:
        //   "CHUNK_SIZE_FOR_TESTING does not exist — chunk size is not injectable"
        val chunkSizeIsInjectable = try {
            FileIndexer::class.java.getDeclaredField("CHUNK_SIZE_FOR_TESTING")
            true
        } catch (_: NoSuchFieldException) {
            false
        }

        // This assertion FAILS until R-09 makes the chunk size injectable:
        chunkSizeIsInjectable shouldBe true

        // Once injectable, the real test scenario is:
        //   val chunkSize = 16L  // tiny chunk
        //   val prefix = ByteArray((chunkSize - 1).toInt()) { 'A'.code.toByte() }
        //   val content = prefix + byteArrayOf('\r'.code.toByte(), '\n'.code.toByte()) +
        //                 "SecondLine\r\n".toByteArray()
        //   // '\r' is at offset chunkSize-1 (last byte of chunk 0)
        //   // '\n' is at offset chunkSize   (first byte of chunk 1)
        //   val file = tempFile(content)
        //   val result = FileIndexer.indexWithChunkSize(file, chunkSize)
        //   result.index.lineCount shouldBe 2          // NOT 3 or 4
        //   result.index.lineEnding shouldBe LineEnding.CRLF
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
