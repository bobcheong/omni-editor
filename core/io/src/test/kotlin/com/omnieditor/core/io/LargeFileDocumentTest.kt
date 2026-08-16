package com.omnieditor.core.io

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.Channels

class LargeFileDocumentTest {

    private lateinit var tempFile: File

    @Before
    fun setUp() {
        tempFile = File.createTempFile("large-doc-test", ".txt")
        tempFile.writeText("line0\nline1\nline2\n")
    }

    @After
    fun tearDown() {
        tempFile.delete()
    }

    @Test
    fun `lineCount matches newlines plus 1`() = runTest {
        val doc = LargeFileDocument.open(tempFile)
        doc.use {
            // "line0\nline1\nline2\n" has 3 newlines → 4 lines (last is empty)
            it.lineCount shouldBe 4
        }
    }

    @Test
    fun `line returns correct content`() = runTest {
        val doc = LargeFileDocument.open(tempFile)
        doc.use {
            it.line(0).toString() shouldBe "line0"
            it.line(1).toString() shouldBe "line1"
            it.line(2).toString() shouldBe "line2"
            it.line(3).toString() shouldBe ""
        }
    }

    @Test
    fun `dirty is always false`() = runTest {
        val doc = LargeFileDocument.open(tempFile)
        doc.use { it.dirty shouldBe false }
    }

    @Test
    fun `editGeneration is always 0`() = runTest {
        val doc = LargeFileDocument.open(tempFile)
        doc.use { it.editGeneration shouldBe 0L }
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `edit throws UnsupportedOperationException`() = runTest {
        val doc = LargeFileDocument.open(tempFile)
        doc.use { it.edit(0L..0L, "modified") }
    }

    @Test
    fun `materialise writes content back`() = runTest {
        val doc = LargeFileDocument.open(tempFile)
        doc.use {
            val baos = ByteArrayOutputStream()
            it.materialise(Channels.newChannel(baos))
            baos.toString("UTF-8") shouldContain "line0"
        }
    }

    @Test
    fun `file without trailing newline`() = runTest {
        tempFile.writeText("hello\nworld")
        val doc = LargeFileDocument.open(tempFile)
        doc.use {
            it.lineCount shouldBe 2
            it.line(0).toString() shouldBe "hello"
            it.line(1).toString() shouldBe "world"
        }
    }
}
