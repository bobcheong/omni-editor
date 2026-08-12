package com.omnieditor.feature.editor

import com.omnieditor.core.io.PieceTableDocument
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

class EditorStateTest {

    private fun stateOf(text: String): EditorState =
        EditorState(PieceTableDocument.create(text))

    // ── Existing regression test ─────────────────────────────────────────

    @Test
    fun `R-05 delete selection preserves tail of last line`() {
        val doc = PieceTableDocument.create("aaaBBBcc\nddeee")
        val state = EditorState(doc)
        state.caretLine = 1L
        state.caretColumn = 2
        state.selectionAnchorLine = 0L
        state.selectionAnchorColumn = 3
        state.deleteSelection()
        doc.lineCount shouldBe 1
        doc.line(0).toString() shouldBe "aaaeee"
    }

    // ── R-15 SelectionMode ───────────────────────────────────────────────

    @Test
    fun `selectionMode defaults to LINEAR`() {
        val state = stateOf("hello")
        state.selectionMode shouldBe SelectionMode.LINEAR
    }

    @Test
    fun `selectionMode can be set to COLUMN`() {
        val state = stateOf("hello")
        state.selectionMode = SelectionMode.COLUMN
        state.selectionMode shouldBe SelectionMode.COLUMN
    }

    // ── R-15 moveCaretWithSelection ──────────────────────────────────────

    @Test
    fun `moveCaretWithSelection sets anchor on first call`() {
        val state = stateOf("hello world")
        state.moveCaret(0, 3)
        state.selectionAnchorLine.shouldBeNull()

        state.moveCaretWithSelection(0, 7)

        state.selectionAnchorLine shouldBe 0L
        state.selectionAnchorColumn shouldBe 3
        state.caretLine shouldBe 0L
        state.caretColumn shouldBe 7
        state.hasSelection shouldBe true
    }

    @Test
    fun `moveCaretWithSelection extends existing selection`() {
        val state = stateOf("line one\nline two\nline three")
        state.moveCaret(0, 0)
        state.moveCaretWithSelection(1, 4)
        state.moveCaretWithSelection(2, 5)

        state.selectionAnchorLine shouldBe 0L
        state.selectionAnchorColumn shouldBe 0
        state.caretLine shouldBe 2L
        state.caretColumn shouldBe 5
    }

    // ── R-15 setSelection ────────────────────────────────────────────────

    @Test
    fun `setSelection explicitly sets anchor and caret`() {
        val state = stateOf("hello world")
        state.setSelection(0, 2, 0, 8)

        state.selectionAnchorLine shouldBe 0L
        state.selectionAnchorColumn shouldBe 2
        state.caretLine shouldBe 0L
        state.caretColumn shouldBe 8
        state.hasSelection shouldBe true
    }

    @Test
    fun `setSelection clamps to valid ranges`() {
        val state = stateOf("short")
        state.setSelection(0, 0, 99, 99)

        state.caretLine shouldBe 0L
        state.caretColumn shouldBe 5 // "short" is 5 chars
    }

    // ── R-15 selectionBounds ─────────────────────────────────────────────

    @Test
    fun `selectionBounds returns null without selection`() {
        val state = stateOf("hello")
        state.selectionBounds().shouldBeNull()
    }

    @Test
    fun `selectionBounds returns ordered range when anchor before caret`() {
        val state = stateOf("hello world")
        state.setSelection(0, 2, 0, 8)
        val bounds = state.selectionBounds()
        bounds.shouldNotBeNull()
        bounds.startLine shouldBe 0L
        bounds.startColumn shouldBe 2
        bounds.endLine shouldBe 0L
        bounds.endColumn shouldBe 8
    }

    @Test
    fun `selectionBounds returns ordered range when caret before anchor`() {
        val state = stateOf("hello world")
        state.setSelection(0, 8, 0, 2)
        val bounds = state.selectionBounds()
        bounds.shouldNotBeNull()
        bounds.startLine shouldBe 0L
        bounds.startColumn shouldBe 2
        bounds.endLine shouldBe 0L
        bounds.endColumn shouldBe 8
    }

    // ── R-15 selectWordAt ────────────────────────────────────────────────

    @Test
    fun `selectWordAt selects word under cursor`() {
        val state = stateOf("hello world")
        state.selectWordAt(0, 2)
        val bounds = state.selectionBounds()
        bounds.shouldNotBeNull()
        bounds.startColumn shouldBe 0
        bounds.endColumn shouldBe 5
    }

    @Test
    fun `selectWordAt selects second word`() {
        val state = stateOf("hello world")
        state.selectWordAt(0, 7)
        val bounds = state.selectionBounds()
        bounds.shouldNotBeNull()
        bounds.startColumn shouldBe 6
        bounds.endColumn shouldBe 11
    }

    @Test
    fun `selectWordAt on space selects single character`() {
        val state = stateOf("hello world")
        state.selectWordAt(0, 5)
        val bounds = state.selectionBounds()
        bounds.shouldNotBeNull()
        bounds.startColumn shouldBe 5
        bounds.endColumn shouldBe 6
    }

    @Test
    fun `selectWordAt on empty line moves caret`() {
        val state = stateOf("")
        state.selectWordAt(0, 0)
        state.caretLine shouldBe 0L
        state.caretColumn shouldBe 0
        state.hasSelection shouldBe false
    }

    @Test
    fun `selectWordAt handles underscores as word chars`() {
        val state = stateOf("my_var = 42")
        state.selectWordAt(0, 1)
        val bounds = state.selectionBounds()
        bounds.shouldNotBeNull()
        bounds.startColumn shouldBe 0
        bounds.endColumn shouldBe 6
    }

    // ── R-15 selectLineAt ────────────────────────────────────────────────

    @Test
    fun `selectLineAt selects entire line`() {
        val state = stateOf("first\nsecond\nthird")
        state.selectLineAt(1)
        val bounds = state.selectionBounds()
        bounds.shouldNotBeNull()
        bounds.startLine shouldBe 1L
        bounds.startColumn shouldBe 0
        bounds.endLine shouldBe 1L
        bounds.endColumn shouldBe 6 // "second".length
    }

    @Test
    fun `selectLineAt clamps to valid line`() {
        val state = stateOf("only line")
        state.selectLineAt(99)
        val bounds = state.selectionBounds()
        bounds.shouldNotBeNull()
        bounds.startLine shouldBe 0L
        bounds.startColumn shouldBe 0
        bounds.endColumn shouldBe 9
    }

    // ── R-15 selectedText with new helpers ───────────────────────────────

    @Test
    fun `selectedText returns word after selectWordAt`() {
        val state = stateOf("hello world")
        state.selectWordAt(0, 7)
        state.selectedText() shouldBe "world"
    }

    @Test
    fun `selectedText returns line after selectLineAt`() {
        val state = stateOf("alpha\nbeta\ngamma")
        state.selectLineAt(1)
        state.selectedText() shouldBe "beta"
    }
}
