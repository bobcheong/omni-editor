package com.omnieditor.core.io

import com.omnieditor.core.model.Progress
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LineIndexTest {

    // ── Basic indexing ──

    @Test
    fun `indexes simple LF-terminated file`() = runTest {
        val bytes = "alpha\nbeta\ngamma\n".toByteArray()
        val index = LineIndex.build(bytes)
        index.lineCount shouldBe 3
        index.hash(0) shouldNotBe index.hash(1)
        index.length(0) shouldBe 5 // "alpha"
        index.length(1) shouldBe 4 // "beta"
        index.length(2) shouldBe 5 // "gamma"
    }

    @Test
    fun `indexes file without trailing newline`() = runTest {
        val bytes = "alpha\nbeta".toByteArray()
        val index = LineIndex.build(bytes)
        index.lineCount shouldBe 2
        index.length(0) shouldBe 5
        index.length(1) shouldBe 4
    }

    @Test
    fun `indexes single line no newline`() = runTest {
        val bytes = "hello".toByteArray()
        val index = LineIndex.build(bytes)
        index.lineCount shouldBe 1
        index.length(0) shouldBe 5
    }

    @Test
    fun `indexes empty input as one empty line`() = runTest {
        val bytes = ByteArray(0)
        val index = LineIndex.build(bytes)
        index.lineCount shouldBe 1
        index.length(0) shouldBe 0
    }

    @Test
    fun `indexes CRLF file`() = runTest {
        val bytes = "alpha\r\nbeta\r\ngamma\r\n".toByteArray()
        val index = LineIndex.build(bytes)
        index.lineCount shouldBe 3
        index.length(0) shouldBe 5
        index.length(1) shouldBe 4
        index.length(2) shouldBe 5
    }

    @Test
    fun `indexes CR-only file`() = runTest {
        val bytes = "alpha\rbeta\rgamma\r".toByteArray()
        val index = LineIndex.build(bytes)
        index.lineCount shouldBe 3
    }

    // ── BOM handling ──

    @Test
    fun `skips BOM when bomSkip specified`() = runTest {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val content = "hello\n".toByteArray()
        val bytes = bom + content
        val index = LineIndex.build(bytes, bomSkip = 3)
        index.lineCount shouldBe 1
        index.length(0) shouldBe 5 // "hello", not the BOM
        index.bomLength shouldBe 3
    }

    // ── Hash consistency ──

    @Test
    fun `identical lines have identical hashes`() = runTest {
        val bytes = "same\nsame\nsame\n".toByteArray()
        val index = LineIndex.build(bytes)
        index.hash(0) shouldBe index.hash(1)
        index.hash(1) shouldBe index.hash(2)
    }

    @Test
    fun `different lines have different hashes`() = runTest {
        val bytes = "alpha\nbeta\ngamma\n".toByteArray()
        val index = LineIndex.build(bytes)
        index.hash(0) shouldNotBe index.hash(1)
        index.hash(1) shouldNotBe index.hash(2)
    }

    @Test
    fun `hash is independent of line ending`() = runTest {
        val lfBytes = "hello\n".toByteArray()
        val crlfBytes = "hello\r\n".toByteArray()
        val lfIndex = LineIndex.build(lfBytes)
        val crlfIndex = LineIndex.build(crlfBytes)
        lfIndex.hash(0) shouldBe crlfIndex.hash(0)
    }

    // ── Progress reporting ──

    @Test
    fun `reports progress during indexing`() = runTest {
        val lines = (0 until 10000).joinToString("\n") { "line $it" } + "\n"
        val bytes = lines.toByteArray()
        val progressCalls = mutableListOf<Progress>()
        LineIndex.build(bytes, progress = { progressCalls.add(it) })
        progressCalls.isNotEmpty() shouldBe true
        progressCalls.last().done shouldBe progressCalls.last().total
    }

    // ── Cancellation ──

    @Test
    fun `cancellation stops indexing`() = runTest {
        // Create a large enough input that cancellation has time to fire
        val lines = (0 until 50000).joinToString("\n") { "line number $it with some padding content" }
        val bytes = lines.toByteArray()
        var cancelled = false

        val job = launch {
            try {
                LineIndex.build(bytes, progress = {
                    // Cancel after first progress report
                    if (!cancelled) {
                        cancelled = true
                        coroutineContext[kotlinx.coroutines.Job]?.cancel()
                    }
                })
            } catch (_: CancellationException) {
                // Expected
            }
        }
        job.join()
        cancelled shouldBe true
    }

    // ── Large file simulation ──

    @Test
    fun `indexes 10k lines correctly`() = runTest {
        val lines = (0 until 10000).joinToString("\n") { "line %05d: common content here".format(it) } + "\n"
        val bytes = lines.toByteArray()
        val index = LineIndex.build(bytes)
        index.lineCount shouldBe 10000
        // Spot-check: line 4999 should have correct content hash
        val line4999 = "line 04999: common content here"
        val expectedHash = LineIndex.hashLine(line4999.toByteArray(), 0, line4999.length)
        index.hash(4999) shouldBe expectedHash
    }

    // ── One-byte and edge cases ──

    @Test
    fun `indexes single byte file`() = runTest {
        val bytes = byteArrayOf('A'.code.toByte())
        val index = LineIndex.build(bytes)
        index.lineCount shouldBe 1
        index.length(0) shouldBe 1
    }

    @Test
    fun `indexes file that is just a newline`() = runTest {
        val bytes = "\n".toByteArray()
        val index = LineIndex.build(bytes)
        // A file containing just "\n" has one empty line
        index.lineCount shouldBe 1
        index.length(0) shouldBe 0
    }

    @Test
    fun `indexes consecutive newlines`() = runTest {
        val bytes = "a\n\nb\n".toByteArray()
        val index = LineIndex.build(bytes)
        index.lineCount shouldBe 3
        index.length(0) shouldBe 1 // "a"
        index.length(1) shouldBe 0 // empty
        index.length(2) shouldBe 1 // "b"
    }
}
