package com.omnieditor.core.io

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.Channels
import kotlin.random.Random

class PieceTableDocumentTest {

    private lateinit var journalDir: File

    @Before
    fun setUp() {
        journalDir = File(System.getProperty("java.io.tmpdir"), "omni-journal-test-${System.nanoTime()}")
        journalDir.mkdirs()
    }

    @After
    fun tearDown() {
        journalDir.deleteRecursively()
    }

    // ── Basic editing ──

    @Test
    fun `create empty document`() {
        val doc = PieceTableDocument.create()
        doc.lineCount shouldBe 1
        doc.dirty shouldBe false
    }

    @Test
    fun `create document with content`() {
        val doc = PieceTableDocument.create("hello\nworld")
        doc.lineCount shouldBe 2
        doc.line(0).toString() shouldBe "hello"
        doc.line(1).toString() shouldBe "world"
    }

    @Test
    fun `edit replaces line range`() {
        val doc = PieceTableDocument.create("line0\nline1\nline2")
        doc.edit(1L..1L, "replaced")
        doc.dirty shouldBe true
    }

    // ── Undo / Redo ──

    @Test
    fun `undo reverses an insert`() {
        val doc = PieceTableDocument.create("hello")
        doc.edit(0L..0L, "XXX")
        doc.undo()
        doc.text() shouldBe "hello"
    }

    @Test
    fun `redo re-applies an undone edit`() {
        val doc = PieceTableDocument.create("hello")
        doc.edit(0L..0L, "XXX")
        val afterEdit = doc.text()
        doc.undo()
        doc.text() shouldBe "hello"
        doc.redo()
        doc.text() shouldBe afterEdit
    }

    @Test
    fun `undo returns null when nothing to undo`() {
        val doc = PieceTableDocument.create("hello")
        doc.undo() shouldBe null
    }

    @Test
    fun `redo returns null when nothing to redo`() {
        val doc = PieceTableDocument.create("hello")
        doc.redo() shouldBe null
    }

    @Test
    fun `new edit clears redo stack`() {
        val doc = PieceTableDocument.create("hello")
        doc.edit(0L..0L, "A")
        doc.undo()
        doc.redoCount shouldBe 1
        doc.edit(0L..0L, "B")
        doc.redoCount shouldBe 0
    }

    // ── editGeneration (R-18a) ──

    @Test
    fun `editGeneration starts at 0`() {
        val doc = PieceTableDocument.create("hello")
        doc.editGeneration shouldBe 0L
    }

    @Test
    fun `editGeneration increments on edit`() {
        val doc = PieceTableDocument.create("hello")
        val gen0 = doc.editGeneration
        doc.edit(0L..0L, "world")
        doc.editGeneration shouldBe gen0 + 1L
    }

    @Test
    fun `editGeneration increments on replaceAll`() {
        val doc = PieceTableDocument.create("hello")
        val gen0 = doc.editGeneration
        doc.replaceAll(0, 5, "world")
        doc.editGeneration shouldBe gen0 + 1L
    }

    @Test
    fun `editGeneration increments on undo`() {
        val doc = PieceTableDocument.create("hello")
        doc.edit(0L..0L, "world")
        val genAfterEdit = doc.editGeneration
        doc.undo()
        doc.editGeneration shouldBe genAfterEdit + 1L
    }

    @Test
    fun `editGeneration increments on redo`() {
        val doc = PieceTableDocument.create("hello")
        doc.edit(0L..0L, "world")
        doc.undo()
        val genAfterUndo = doc.editGeneration
        doc.redo()
        doc.editGeneration shouldBe genAfterUndo + 1L
    }

    @Test
    fun `editGeneration is monotonically increasing across multiple edits`() {
        val doc = PieceTableDocument.create("hello")
        var prev = doc.editGeneration
        repeat(5) {
            doc.edit(0L..0L, "x")
            val cur = doc.editGeneration
            (cur > prev) shouldBe true
            prev = cur
        }
    }

    // ── Materialise ──

    @Test
    fun `materialise writes content to channel`() = runTest {
        val doc = PieceTableDocument.create("hello world")
        val baos = ByteArrayOutputStream()
        doc.materialise(Channels.newChannel(baos))
        baos.toString("UTF-8") shouldBe "hello world"
    }

    @Test
    fun `materialise re-emits UTF-8 BOM when source had one`() = runTest {
        val doc = PieceTableDocument.create("hello", encoding = "UTF-8", bomLength = 3)
        val baos = ByteArrayOutputStream()
        doc.materialise(Channels.newChannel(baos))
        val bytes = baos.toByteArray()
        // UTF-8 BOM: EF BB BF
        bytes[0] shouldBe 0xEF.toByte()
        bytes[1] shouldBe 0xBB.toByte()
        bytes[2] shouldBe 0xBF.toByte()
        // Content follows
        String(bytes, 3, bytes.size - 3, Charsets.UTF_8) shouldBe "hello"
    }

    @Test
    fun `materialise emits no BOM when source had none`() = runTest {
        val doc = PieceTableDocument.create("hello", encoding = "UTF-8", bomLength = 0)
        val baos = ByteArrayOutputStream()
        doc.materialise(Channels.newChannel(baos))
        val bytes = baos.toByteArray()
        String(bytes, Charsets.UTF_8) shouldBe "hello"
        // First byte should NOT be BOM
        bytes[0] shouldBe 'h'.code.toByte()
    }

    @Test
    fun `materialise re-emits UTF-16LE BOM`() = runTest {
        val doc = PieceTableDocument.create("hi", encoding = "UTF-16LE", bomLength = 2)
        val baos = ByteArrayOutputStream()
        doc.materialise(Channels.newChannel(baos))
        val bytes = baos.toByteArray()
        // UTF-16 LE BOM: FF FE
        bytes[0] shouldBe 0xFF.toByte()
        bytes[1] shouldBe 0xFE.toByte()
    }

    // ── Journalling and crash recovery ──

    @Test
    fun `journal is written on edit`() {
        val doc = PieceTableDocument.create("hello", journalDir = journalDir, documentId = "doc1")
        doc.edit(0L..0L, "X")
        val journalFile = File(journalDir, "doc1.journal")
        journalFile.exists() shouldBe true
        journalFile.readLines().isNotEmpty() shouldBe true
    }

    @Test
    fun `recover restores edits after crash`() {
        val original = "line1\nline2\nline3"

        // Create document and make edits
        val doc = PieceTableDocument.create(original, journalDir = journalDir, documentId = "doc2")
        doc.edit(1L..1L, "REPLACED")
        val textAfterEdit = doc.text()

        // Simulate crash: create a new document from the original + journal
        val recovered = PieceTableDocument.recover(original, journalDir, "doc2")
        recovered shouldNotBe null
        recovered!!.text() shouldBe textAfterEdit
    }

    @Test
    fun `recover with undo entries`() {
        val original = "hello"
        val doc = PieceTableDocument.create(original, journalDir = journalDir, documentId = "doc3")
        doc.edit(0L..0L, "X")
        doc.undo()
        // After undo, document should be back to original

        val recovered = PieceTableDocument.recover(original, journalDir, "doc3")
        recovered shouldNotBe null
        recovered!!.text() shouldBe original
    }

    @Test
    fun `recover returns null without journal`() {
        val recovered = PieceTableDocument.recover("hello", journalDir, "nonexistent")
        recovered shouldBe null
    }

    // ── Property test: 10,000 random edits + full undo = original ──

    @Test
    fun `property - 10000 random edits then full undo yields exact original`() {
        val original = buildString {
            for (i in 0 until 100) {
                appendLine("line $i: some initial content here for the property test")
            }
        }

        val doc = PieceTableDocument.create(original)
        val rng = Random(42) // deterministic seed

        // Perform 10,000 random edits via the public replaceAll API (insert/delete/replace).
        // Each call is a single journalled, undoable step — no reflection needed.
        for (i in 0 until 10_000) {
            val len = doc.length
            if (len == 0) {
                // Insert at position 0
                doc.replaceAll(0, 0, "x${rng.nextInt(100)}")
                continue
            }

            when (rng.nextInt(3)) {
                0 -> {
                    // Insert at random position (replace 0 chars)
                    val pos = rng.nextInt(len + 1)
                    doc.replaceAll(pos, 0, "ins${rng.nextInt(100)}")
                }
                1 -> {
                    // Delete random range (replace with empty)
                    val pos = rng.nextInt(len)
                    val delLen = minOf(rng.nextInt(5) + 1, len - pos)
                    doc.replaceAll(pos, delLen, "")
                }
                2 -> {
                    // Replace random range
                    val pos = rng.nextInt(len)
                    val repLen = minOf(rng.nextInt(3) + 1, len - pos)
                    doc.replaceAll(pos, repLen, "rep${rng.nextInt(100)}")
                }
            }
        }

        // Full undo — should restore the exact original
        while (doc.undo() != null) { /* keep undoing */ }

        doc.text() shouldBe original
    }

    // ── replaceAll API ──

    @Test
    fun `replaceAll inserts text at position`() {
        val doc = PieceTableDocument.create("hello world")
        doc.replaceAll(5, 0, " beautiful")
        doc.text() shouldBe "hello beautiful world"
    }

    @Test
    fun `replaceAll deletes text`() {
        val doc = PieceTableDocument.create("hello world")
        doc.replaceAll(5, 6, "")
        doc.text() shouldBe "hello"
    }

    @Test
    fun `replaceAll replaces text`() {
        val doc = PieceTableDocument.create("hello world")
        doc.replaceAll(6, 5, "kotlin")
        doc.text() shouldBe "hello kotlin"
    }

    @Test
    fun `replaceAll is a single undo step`() {
        val doc = PieceTableDocument.create("abc")
        doc.replaceAll(0, 1, "X")
        doc.replaceAll(1, 1, "Y")
        // Two replaceAll calls = two undo steps
        doc.undoCount shouldBe 2
        doc.undo()
        doc.text() shouldBe "Xbc"
        doc.undo()
        doc.text() shouldBe "abc"
    }

    @Test
    fun `replaceAll whole document is one undo step`() {
        val doc = PieceTableDocument.create("line1\nline2\nline3")
        val newContent = "sorted1\nsorted2\nsorted3"
        doc.replaceAll(0, doc.length, newContent)
        doc.undoCount shouldBe 1
        doc.undo()
        doc.text() shouldBe "line1\nline2\nline3"
    }

    @Test
    fun `replaceAll exposes document length`() {
        val doc = PieceTableDocument.create("hello")
        doc.length shouldBe 5
        doc.replaceAll(5, 0, " world")
        doc.length shouldBe 11
    }

    // ── R-04: mid-line insert line count bug ──

    @Test
    fun `R-04 mid-line insert preserves line count`() {
        // A 5-line file: editing line 2 to append "X" should keep 5 lines.
        // Bug: edit() computes endOffset = lineToOffset(range.last + 1), which includes
        // the trailing \n terminator of line 2. The replacement "cccX" has no \n,
        // so line 2 and line 3 merge — the document ends up with 4 lines instead of 5.
        val doc = PieceTableDocument.create("aaa\nbbb\nccc\nddd\neee")
        val line2 = doc.line(2).toString()          // "ccc"
        val newLine2 = line2 + "X"                  // "cccX"
        doc.edit(2L..2L, newLine2)
        // Bug causes this to be 4, not 5
        doc.lineCount shouldBe 5
        doc.line(2).toString() shouldBe "cccX"
    }

    // ── Dirty state ──

    @Test
    fun `dirty is false initially`() {
        val doc = PieceTableDocument.create("hello")
        doc.dirty shouldBe false
    }

    @Test
    fun `dirty is true after edit`() {
        val doc = PieceTableDocument.create("hello")
        doc.edit(0L..0L, "X")
        doc.dirty shouldBe true
    }

    @Test
    fun `dirty is false after full undo`() {
        val doc = PieceTableDocument.create("hello")
        doc.edit(0L..0L, "X")
        doc.undo()
        doc.dirty shouldBe false
    }

    @Test
    fun `markSaved clears dirty after edit`() {
        val doc = PieceTableDocument.create("hello")
        doc.edit(0L..0L, "X")
        doc.dirty shouldBe true
        doc.markSaved()
        doc.dirty shouldBe false
    }

    @Test
    fun `dirty is true after edit following markSaved`() {
        val doc = PieceTableDocument.create("hello")
        doc.edit(0L..0L, "X")
        doc.markSaved()
        doc.edit(0L..0L, "Y")
        doc.dirty shouldBe true
    }

    @Test
    fun `undo back to saved depth clears dirty`() {
        // Edit → markSaved → edit again → undo: dirty should be false again
        val doc = PieceTableDocument.create("hello")
        doc.edit(0L..0L, "A")
        doc.markSaved()
        doc.edit(0L..0L, "B")
        doc.dirty shouldBe true
        doc.undo()
        doc.dirty shouldBe false
    }

    @Test
    fun `undo past saved depth keeps dirty true`() {
        // No markSaved, so savedDepth == 0. After edit+undo the depth is 0 again → clean.
        // Make two edits, markSaved after first, undo both → dirty because we're below saved depth.
        val doc = PieceTableDocument.create("hello")
        doc.edit(0L..0L, "A") // depth 1
        doc.markSaved()        // savedDepth = 1
        doc.undo()             // depth 0 — below saved depth → dirty
        doc.dirty shouldBe true
    }

    @Test
    fun `markSaved at creation point means fresh document is clean`() {
        // A brand-new document with no edits — dirty should be false (savedDepth 0 == stackSize 0).
        val doc = PieceTableDocument.create()
        doc.dirty shouldBe false
        doc.markSaved() // no-op effectively
        doc.dirty shouldBe false
    }

    // ── R-17b: Typing-level undo coalescing ──

    @Test
    fun `typing multiple characters on same line coalesces into one undo step`() {
        // Start: "hello", simulate typing to build "helloworld" one char at a time.
        val doc = PieceTableDocument.create("hello")
        // Each editCoalesced replaces line 0 with progressively longer text.
        doc.editCoalesced(0L..0L, "hellow", isTyping = true)
        doc.editCoalesced(0L..0L, "hellowo", isTyping = true)
        doc.editCoalesced(0L..0L, "hellowor", isTyping = true)
        doc.editCoalesced(0L..0L, "helloworl", isTyping = true)
        doc.editCoalesced(0L..0L, "helloworld", isTyping = true)
        // All five typing edits should have collapsed to a single undo entry.
        doc.undoCount shouldBe 1
        doc.undo()
        doc.text() shouldBe "hello"
    }

    @Test
    fun `typing then undoing removes the whole coalesced word`() {
        val doc = PieceTableDocument.create("hello")
        val chars = "world"
        var content = "hello"
        for (c in chars) {
            content += c
            doc.editCoalesced(0L..0L, content, isTyping = true)
        }
        // One undo should restore original content, not just remove last char.
        doc.undo()
        doc.text() shouldBe "hello"
    }

    @Test
    fun `caret jump to different line breaks coalescing`() {
        // Two-line document. Type on line 0, then type on line 1 → two undo steps.
        val doc = PieceTableDocument.create("ab\ncd")
        doc.editCoalesced(0L..0L, "aXb", isTyping = true) // type X on line 0
        doc.editCoalesced(1L..1L, "cYd", isTyping = true) // type Y on line 1 (different line)
        doc.undoCount shouldBe 2
        doc.undo() // undoes line-1 change
        doc.line(1).toString() shouldBe "cd"
        doc.undo() // undoes line-0 change
        doc.line(0).toString() shouldBe "ab"
    }

    @Test
    fun `non-typing editCoalesced does not coalesce`() {
        val doc = PieceTableDocument.create("hello")
        doc.editCoalesced(0L..0L, "helloA", isTyping = false)
        doc.editCoalesced(0L..0L, "helloAB", isTyping = false)
        // Non-typing calls must not coalesce: each is its own undo step.
        doc.undoCount shouldBe 2
    }

    @Test
    fun `markSaved breaks coalescing`() {
        val doc = PieceTableDocument.create("hello")
        doc.editCoalesced(0L..0L, "helloA", isTyping = true)
        doc.markSaved() // should break the coalescing window
        doc.editCoalesced(0L..0L, "helloAB", isTyping = true)
        // After markSaved the second edit cannot merge with the first.
        doc.undoCount shouldBe 2
    }

    @Test
    fun `breakCoalescing explicitly breaks the window`() {
        val doc = PieceTableDocument.create("hello")
        doc.editCoalesced(0L..0L, "helloA", isTyping = true)
        doc.breakCoalescing()
        doc.editCoalesced(0L..0L, "helloAB", isTyping = true)
        doc.undoCount shouldBe 2
    }

    @Test
    fun `coalescing preserves redo-stack clearing`() {
        val doc = PieceTableDocument.create("hello")
        doc.editCoalesced(0L..0L, "helloA", isTyping = true)
        doc.undo()
        doc.redoCount shouldBe 1
        // A new typing edit should clear redo just like a normal edit does.
        doc.editCoalesced(0L..0L, "helloB", isTyping = true)
        doc.redoCount shouldBe 0
    }

    @Test
    fun `editCoalesced with isTyping false then isTyping true does not coalesce`() {
        val doc = PieceTableDocument.create("hello")
        // First edit is non-typing
        doc.editCoalesced(0L..0L, "helloX", isTyping = false)
        // Second edit is typing on same line — must NOT merge with non-typing entry
        doc.editCoalesced(0L..0L, "helloXY", isTyping = true)
        doc.undoCount shouldBe 2
        doc.undo()
        doc.text() shouldBe "helloX"
        doc.undo()
        doc.text() shouldBe "hello"
    }

    @Test
    fun `replaceAll does not interfere with coalescing tracking`() {
        val doc = PieceTableDocument.create("hello")
        doc.editCoalesced(0L..0L, "helloA", isTyping = true)
        // replaceAll is a non-typing bulk op, should break coalescing window
        doc.replaceAll(0, doc.length, "replaced")
        doc.editCoalesced(0L..0L, "replacedX", isTyping = true)
        // There should be 3 separate undo steps: typing-A, replaceAll, typing-X
        doc.undoCount shouldBe 3
    }

    // ── Batch undo (R-54 prerequisite) ──

    @Test
    fun `batch groups multiple edits into single undo step`() {
        val doc = PieceTableDocument.create("line0\nline1\nline2")
        doc.beginBatch()
        doc.edit(0L..0L, "  line0")
        doc.edit(1L..1L, "  line1")
        doc.edit(2L..2L, "  line2")
        doc.commitBatch()

        doc.line(0).toString() shouldBe "  line0"
        doc.line(1).toString() shouldBe "  line1"
        doc.line(2).toString() shouldBe "  line2"

        // Single undo should revert ALL three edits
        doc.undo()
        doc.line(0).toString() shouldBe "line0"
        doc.line(1).toString() shouldBe "line1"
        doc.line(2).toString() shouldBe "line2"

        // Single redo should reapply all three
        doc.redo()
        doc.line(0).toString() shouldBe "  line0"
        doc.line(1).toString() shouldBe "  line1"
        doc.line(2).toString() shouldBe "  line2"
    }

    @Test
    fun `batch with single edit behaves like normal edit`() {
        val doc = PieceTableDocument.create("hello")
        doc.beginBatch()
        doc.edit(0L..0L, "world")
        doc.commitBatch()

        doc.line(0).toString() shouldBe "world"
        doc.undo()
        doc.line(0).toString() shouldBe "hello"
    }

    @Test
    fun `nested batch is flat - inner commit is ignored`() {
        val doc = PieceTableDocument.create("a\nb")
        doc.beginBatch()
        doc.edit(0L..0L, "A")
        doc.beginBatch() // nested — should be ignored
        doc.edit(1L..1L, "B")
        doc.commitBatch() // ends the outer batch
        // No second commitBatch needed

        doc.undo()
        doc.line(0).toString() shouldBe "a"
        doc.line(1).toString() shouldBe "b"
    }

    @Test
    fun `empty batch is no-op`() {
        val doc = PieceTableDocument.create("hello")
        val genBefore = doc.editGeneration
        doc.beginBatch()
        doc.commitBatch()
        doc.editGeneration shouldBe genBefore
    }

    @Test
    fun `batch does not write individual edits to journal - recover yields one undo step`() {
        // Finding 1 regression: during a batch, individual edit() calls must NOT
        // append to the journal. commitBatch() appends a single compound entry.
        // Crash recovery must therefore produce exactly 1 undo step, not N+1.
        val original = "line0\nline1\nline2"
        val doc = PieceTableDocument.create(original, journalDir = journalDir, documentId = "batch-journal")
        doc.beginBatch()
        try {
            doc.edit(0L..0L, "  line0")
            doc.edit(1L..1L, "  line1")
            doc.edit(2L..2L, "  line2")
        } finally {
            doc.commitBatch()
        }

        val textAfterBatch = doc.text()
        doc.close()

        // Recover from journal
        val recovered = PieceTableDocument.recover(original, journalDir, "batch-journal")
        recovered shouldNotBe null
        recovered!!.text() shouldBe textAfterBatch
        // The compound entry is the only entry: exactly 1 undo step
        recovered.undoCount shouldBe 1
        recovered.undo()
        recovered.text() shouldBe original
    }
}
