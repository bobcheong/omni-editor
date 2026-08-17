package com.omnieditor.core.io

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.Channels

class LargeFileEditableDocumentTest {

    private lateinit var tempFile: File

    @Before
    fun setUp() {
        tempFile = File.createTempFile("editable-large-doc-test", ".txt")
        tempFile.writeText("line0\nline1\nline2\n")
    }

    @After
    fun tearDown() {
        tempFile.delete()
    }

    @Test
    fun `lineCount matches newlines plus 1`() = runTest {
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use { it.lineCount shouldBe 4 }
    }

    @Test
    fun `line returns correct content`() = runTest {
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use {
            it.line(0).toString() shouldBe "line0"
            it.line(1).toString() shouldBe "line1"
            it.line(2).toString() shouldBe "line2"
            it.line(3).toString() shouldBe ""
        }
    }

    @Test
    fun `edit changes line content`() = runTest {
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use {
            it.edit(1L..1L, "modified")
            it.line(1).toString() shouldBe "modified"
        }
    }

    @Test
    fun `edit marks document dirty`() = runTest {
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use {
            it.dirty shouldBe false
            it.edit(0L..0L, "changed")
            it.dirty shouldBe true
        }
    }

    @Test
    fun `undo reverses edit`() = runTest {
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use {
            it.edit(1L..1L, "modified")
            it.line(1).toString() shouldBe "modified"
            it.undo()
            it.line(1).toString() shouldBe "line1"
        }
    }

    @Test
    fun `redo re-applies edit`() = runTest {
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use {
            it.edit(1L..1L, "modified")
            it.undo()
            it.redo()
            it.line(1).toString() shouldBe "modified"
        }
    }

    @Test
    fun `materialise writes edited content`() = runTest {
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use {
            it.edit(1L..1L, "CHANGED")
            val baos = ByteArrayOutputStream()
            it.materialise(Channels.newChannel(baos))
            val output = baos.toString("UTF-8")
            output shouldContain "CHANGED"
            output shouldContain "line0"
        }
    }

    @Test
    fun `materialise detects external modification`() = runTest {
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use {
            it.edit(0L..0L, "edited")
            // Externally modify the file
            Thread.sleep(50) // ensure lastModified differs
            tempFile.writeText("externally changed content")
            try {
                val baos = ByteArrayOutputStream()
                it.materialise(Channels.newChannel(baos))
                // Should not reach here
                throw AssertionError("Expected OmniException")
            } catch (e: com.omnieditor.core.model.OmniException) {
                (e.error is com.omnieditor.core.model.OmniError.ExternallyModified) shouldBe true
            }
        }
    }

    @Test
    fun `batch edit creates single undo step`() = runTest {
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use {
            it.beginBatch()
            try {
                it.edit(0L..0L, "A")
                it.edit(1L..1L, "B")
            } finally {
                it.commitBatch()
            }
            it.line(0).toString() shouldBe "A"
            it.line(1).toString() shouldBe "B"
            // Single undo should revert both
            it.undo()
            it.line(0).toString() shouldBe "line0"
            it.line(1).toString() shouldBe "line1"
        }
    }

    @Test
    fun `editGeneration increments on each edit`() = runTest {
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use {
            val gen0 = it.editGeneration
            it.edit(0L..0L, "a")
            val gen1 = it.editGeneration
            (gen1 > gen0) shouldBe true
        }
    }

    @Test
    fun `file without trailing newline`() = runTest {
        tempFile.writeText("hello\nworld")
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use {
            it.lineCount shouldBe 2
            it.line(0).toString() shouldBe "hello"
            it.line(1).toString() shouldBe "world"
        }
    }

    @Test
    fun `markSaved clears dirty flag`() = runTest {
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use {
            it.edit(0L..0L, "changed")
            it.dirty shouldBe true
            it.markSaved()
            it.dirty shouldBe false
        }
    }
}
