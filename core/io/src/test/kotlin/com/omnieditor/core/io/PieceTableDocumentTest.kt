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
        doc.edit(0L..0L, "XXX\n")
        doc.undo()
        doc.text() shouldBe "hello"
    }

    @Test
    fun `redo re-applies an undone edit`() {
        val doc = PieceTableDocument.create("hello")
        doc.edit(0L..0L, "XXX\n")
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
        doc.edit(0L..0L, "A\n")
        doc.undo()
        doc.redoCount shouldBe 1
        doc.edit(0L..0L, "B\n")
        doc.redoCount shouldBe 0
    }

    // ── Materialise ──

    @Test
    fun `materialise writes content to channel`() = runTest {
        val doc = PieceTableDocument.create("hello world")
        val baos = ByteArrayOutputStream()
        doc.materialise(Channels.newChannel(baos))
        baos.toString("UTF-8") shouldBe "hello world"
    }

    // ── Journalling and crash recovery ──

    @Test
    fun `journal is written on edit`() {
        val doc = PieceTableDocument.create("hello", journalDir = journalDir, documentId = "doc1")
        doc.edit(0L..0L, "X\n")
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
        doc.edit(0L..0L, "X\n")
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

        // Perform 10,000 random edits
        for (i in 0 until 10_000) {
            val text = doc.text()
            if (text.isEmpty()) {
                // Insert
                val insertText = "x${rng.nextInt(100)}"
                doc.edit(0L..(-1L), insertText) // insert at line 0
                continue
            }

            val len = text.length
            when (rng.nextInt(3)) {
                0 -> {
                    // Insert at random position
                    val pos = rng.nextInt(len + 1)
                    val insertText = "ins${rng.nextInt(100)}"
                    val table = getPieceTable(doc)
                    table.insert(pos, insertText)
                    // Track in undo stack manually via the document
                    pushUndoRecord(doc, EditRecord(EditRecord.Type.INSERT, pos, "", insertText))
                }
                1 -> {
                    // Delete random range
                    if (len > 0) {
                        val pos = rng.nextInt(len)
                        val delLen = minOf(rng.nextInt(5) + 1, len - pos)
                        val table = getPieceTable(doc)
                        val record = table.delete(pos, delLen)
                        pushUndoRecord(doc, record)
                    }
                }
                2 -> {
                    // Replace
                    if (len > 0) {
                        val pos = rng.nextInt(len)
                        val repLen = minOf(rng.nextInt(3) + 1, len - pos)
                        val newText = "rep${rng.nextInt(100)}"
                        val table = getPieceTable(doc)
                        val record = table.replace(pos, repLen, newText)
                        pushUndoRecord(doc, record)
                    }
                }
            }
        }

        // Full undo — should restore the exact original
        while (doc.undo() != null) { /* keep undoing */ }

        doc.text() shouldBe original
    }

    /**
     * Access the underlying PieceTable for direct manipulation in the property test.
     * In production, edits go through the document's edit() method which works on lines.
     * For the property test, we need character-level control.
     */
    private fun getPieceTable(doc: PieceTableDocument): PieceTable {
        val field = PieceTableDocument::class.java.getDeclaredField("table")
        field.isAccessible = true
        return field.get(doc) as PieceTable
    }

    private fun pushUndoRecord(doc: PieceTableDocument, record: EditRecord) {
        val undoField = PieceTableDocument::class.java.getDeclaredField("undoStack")
        undoField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stack = undoField.get(doc) as MutableList<JournalEntry>

        val counterField = PieceTableDocument::class.java.getDeclaredField("editIdCounter")
        counterField.isAccessible = true
        val id = counterField.getLong(doc) + 1
        counterField.setLong(doc, id)

        stack.add(JournalEntry(id, record))

        // Clear redo
        val redoField = PieceTableDocument::class.java.getDeclaredField("redoStack")
        redoField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (redoField.get(doc) as MutableList<*>).clear()
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
        doc.edit(0L..0L, "X\n")
        doc.dirty shouldBe true
    }

    @Test
    fun `dirty is false after full undo`() {
        val doc = PieceTableDocument.create("hello")
        doc.edit(0L..0L, "X\n")
        doc.undo()
        doc.dirty shouldBe false
    }
}
