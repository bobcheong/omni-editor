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

    // ── #12 deleteLine ────────────────────────────────────────────────────

    @Test
    fun `deleteLine removes caret line and moves caret to next`() {
        val state = stateOf("aaa\nbbb\nccc")
        state.moveCaret(1, 0)
        state.deleteLine()
        state.document.lineCount shouldBe 2
        state.document.line(0).toString() shouldBe "aaa"
        state.document.line(1).toString() shouldBe "ccc"
        state.caretLine shouldBe 1L
    }

    @Test
    fun `deleteLine on last line removes it`() {
        val state = stateOf("aaa\nbbb")
        state.moveCaret(1, 0)
        state.deleteLine()
        state.document.lineCount shouldBe 1
        state.document.line(0).toString() shouldBe "aaa"
        state.caretLine shouldBe 0L
    }

    @Test
    fun `deleteLine on single-line doc clears content`() {
        val state = stateOf("hello")
        state.deleteLine()
        state.document.lineCount shouldBe 1
        state.document.line(0).toString() shouldBe ""
        state.caretLine shouldBe 0L
        state.caretColumn shouldBe 0
    }

    // ── #12 duplicateLine ─────────────────────────────────────────────────

    @Test
    fun `duplicateLine inserts copy below`() {
        val state = stateOf("aaa\nbbb\nccc")
        state.moveCaret(1, 0)
        state.duplicateLine()
        state.document.lineCount shouldBe 4
        state.document.line(1).toString() shouldBe "bbb"
        state.document.line(2).toString() shouldBe "bbb"
        state.caretLine shouldBe 2L
    }

    // ── #12 insertLineAbove ───────────────────────────────────────────────

    @Test
    fun `insertLineAbove adds empty line above caret`() {
        val state = stateOf("aaa\nbbb")
        state.moveCaret(1, 2)
        state.insertLineAbove()
        state.document.lineCount shouldBe 3
        state.document.line(1).toString() shouldBe ""
        state.document.line(2).toString() shouldBe "bbb"
        state.caretLine shouldBe 1L
        state.caretColumn shouldBe 0
    }

    // ── #12 insertLineBelow ───────────────────────────────────────────────

    @Test
    fun `insertLineBelow adds empty line below caret`() {
        val state = stateOf("aaa\nbbb")
        state.moveCaret(0, 1)
        state.insertLineBelow()
        state.document.lineCount shouldBe 3
        state.document.line(0).toString() shouldBe "aaa"
        state.document.line(1).toString() shouldBe ""
        state.document.line(2).toString() shouldBe "bbb"
        state.caretLine shouldBe 1L
        state.caretColumn shouldBe 0
    }

    // ── #12 moveLineUp ────────────────────────────────────────────────────

    @Test
    fun `moveLineUp swaps caret line with line above`() {
        val state = stateOf("aaa\nbbb\nccc")
        state.moveCaret(1, 0)
        state.moveLineUp()
        state.document.line(0).toString() shouldBe "bbb"
        state.document.line(1).toString() shouldBe "aaa"
        state.caretLine shouldBe 0L
    }

    @Test
    fun `moveLineUp at first line is no-op`() {
        val state = stateOf("aaa\nbbb")
        state.moveCaret(0, 0)
        state.moveLineUp()
        state.document.line(0).toString() shouldBe "aaa"
        state.caretLine shouldBe 0L
    }

    // ── #12 moveLineDown ──────────────────────────────────────────────────

    @Test
    fun `moveLineDown swaps caret line with line below`() {
        val state = stateOf("aaa\nbbb\nccc")
        state.moveCaret(1, 0)
        state.moveLineDown()
        state.document.line(1).toString() shouldBe "ccc"
        state.document.line(2).toString() shouldBe "bbb"
        state.caretLine shouldBe 2L
    }

    @Test
    fun `moveLineDown at last line is no-op`() {
        val state = stateOf("aaa\nbbb")
        state.moveCaret(1, 0)
        state.moveLineDown()
        state.document.line(1).toString() shouldBe "bbb"
        state.caretLine shouldBe 1L
    }

    // ── #12 indent ────────────────────────────────────────────────────────

    @Test
    fun `indent inserts spaces at line start`() {
        val state = stateOf("hello")
        state.moveCaret(0, 2)
        state.indent()
        state.document.line(0).toString() shouldBe "    hello"
        state.caretColumn shouldBe 6
    }

    @Test
    fun `indent with selection indents all selected lines`() {
        val state = stateOf("aaa\nbbb\nccc")
        state.setSelection(0, 0, 1, 3)
        state.indent()
        state.document.line(0).toString() shouldBe "    aaa"
        state.document.line(1).toString() shouldBe "    bbb"
        state.document.line(2).toString() shouldBe "ccc"
    }

    // ── #12 outdent ───────────────────────────────────────────────────────

    @Test
    fun `outdent removes leading spaces`() {
        val state = stateOf("    hello")
        state.moveCaret(0, 6)
        state.outdent()
        state.document.line(0).toString() shouldBe "hello"
        state.caretColumn shouldBe 2
    }

    @Test
    fun `outdent with partial indent removes available spaces`() {
        val state = stateOf("  hello")
        state.moveCaret(0, 4)
        state.outdent()
        state.document.line(0).toString() shouldBe "hello"
        state.caretColumn shouldBe 2
    }

    @Test
    fun `outdent on non-indented line is no-op`() {
        val state = stateOf("hello")
        state.moveCaret(0, 2)
        state.outdent()
        state.document.line(0).toString() shouldBe "hello"
        state.caretColumn shouldBe 2
    }

    // ── #12 toggleComment ─────────────────────────────────────────────────

    @Test
    fun `toggleComment adds comment prefix to uncommented line`() {
        val state = stateOf("hello")
        state.moveCaret(0, 0)
        state.toggleComment("//")
        state.document.line(0).toString() shouldBe "// hello"
    }

    @Test
    fun `toggleComment removes comment prefix from commented line`() {
        val state = stateOf("// hello")
        state.moveCaret(0, 0)
        state.toggleComment("//")
        state.document.line(0).toString() shouldBe "hello"
    }

    @Test
    fun `toggleComment with hash prefix`() {
        val state = stateOf("print('hi')")
        state.moveCaret(0, 0)
        state.toggleComment("#")
        state.document.line(0).toString() shouldBe "# print('hi')"
    }

    @Test
    fun `toggleComment on selection toggles all lines`() {
        val state = stateOf("aaa\nbbb\nccc")
        state.setSelection(0, 0, 1, 3)
        state.toggleComment("//")
        state.document.line(0).toString() shouldBe "// aaa"
        state.document.line(1).toString() shouldBe "// bbb"
        state.document.line(2).toString() shouldBe "ccc"
    }

    @Test
    fun `toggleComment removes from all commented lines`() {
        val state = stateOf("// aaa\n// bbb\nccc")
        state.setSelection(0, 0, 1, 5)
        state.toggleComment("//")
        state.document.line(0).toString() shouldBe "aaa"
        state.document.line(1).toString() shouldBe "bbb"
    }

    // ── R-54: Batch undo for multi-line operations ────────────────────────

    @Test
    fun `indent 3 lines is single undo step`() {
        val state = stateOf("aaa\nbbb\nccc")
        state.selectionAnchorLine = 0L
        state.selectionAnchorColumn = 0
        state.caretLine = 2L
        state.caretColumn = 3
        state.indent(4)

        state.document.line(0).toString() shouldBe "    aaa"
        state.document.line(1).toString() shouldBe "    bbb"
        state.document.line(2).toString() shouldBe "    ccc"

        // Single undo reverts all three
        state.document.undo()
        state.document.line(0).toString() shouldBe "aaa"
        state.document.line(1).toString() shouldBe "bbb"
        state.document.line(2).toString() shouldBe "ccc"
    }

    @Test
    fun `outdent 3 lines is single undo step`() {
        val state = stateOf("    aaa\n    bbb\n    ccc")
        state.selectionAnchorLine = 0L
        state.selectionAnchorColumn = 0
        state.caretLine = 2L
        state.caretColumn = 7
        state.outdent(4)

        state.document.line(0).toString() shouldBe "aaa"
        state.document.line(1).toString() shouldBe "bbb"
        state.document.line(2).toString() shouldBe "ccc"

        state.document.undo()
        state.document.line(0).toString() shouldBe "    aaa"
        state.document.line(1).toString() shouldBe "    bbb"
        state.document.line(2).toString() shouldBe "    ccc"
    }

    @Test
    fun `toggleComment 3 lines is single undo step`() {
        val state = stateOf("aaa\nbbb\nccc")
        state.selectionAnchorLine = 0L
        state.selectionAnchorColumn = 0
        state.caretLine = 2L
        state.caretColumn = 3
        state.toggleComment("//")

        state.document.line(0).toString() shouldBe "// aaa"
        state.document.line(1).toString() shouldBe "// bbb"
        state.document.line(2).toString() shouldBe "// ccc"

        state.document.undo()
        state.document.line(0).toString() shouldBe "aaa"
        state.document.line(1).toString() shouldBe "bbb"
        state.document.line(2).toString() shouldBe "ccc"
    }

    @Test
    fun `moveLineUp with multi-line selection moves all selected lines`() {
        val state = stateOf("aaa\nbbb\nccc\nddd")
        state.selectionAnchorLine = 1L
        state.selectionAnchorColumn = 0
        state.caretLine = 2L
        state.caretColumn = 3
        state.moveLineUp()

        state.document.line(0).toString() shouldBe "bbb"
        state.document.line(1).toString() shouldBe "ccc"
        state.document.line(2).toString() shouldBe "aaa"
        state.document.line(3).toString() shouldBe "ddd"
    }

    @Test
    fun `moveLineDown with multi-line selection moves all selected lines`() {
        val state = stateOf("aaa\nbbb\nccc\nddd")
        state.selectionAnchorLine = 0L
        state.selectionAnchorColumn = 0
        state.caretLine = 1L
        state.caretColumn = 3
        state.moveLineDown()

        state.document.line(0).toString() shouldBe "ccc"
        state.document.line(1).toString() shouldBe "aaa"
        state.document.line(2).toString() shouldBe "bbb"
        state.document.line(3).toString() shouldBe "ddd"
    }

    // ── #12 commentPrefixForExtension ─────────────────────────────────────

    @Test
    fun `commentPrefixForExtension returns correct prefixes`() {
        commentPrefixForExtension("kt") shouldBe "//"
        commentPrefixForExtension("py") shouldBe "#"
        commentPrefixForExtension("sql") shouldBe "--"
        commentPrefixForExtension("css") shouldBe "/*"
        commentPrefixForExtension("unknown") shouldBe "//"
    }

    // ── #12 deleteLine with selection ─────────────────────────────────────

    @Test
    fun `deleteLine deletes all selected lines`() {
        val state = stateOf("aaa\nbbb\nccc\nddd")
        state.setSelection(1, 0, 2, 3)
        state.deleteLine()
        state.document.lineCount shouldBe 2
        state.document.line(0).toString() shouldBe "aaa"
        state.document.line(1).toString() shouldBe "ddd"
    }

    // ── #12 duplicateLine with selection ──────────────────────────────────

    @Test
    fun `duplicateLine duplicates selected range`() {
        val state = stateOf("aaa\nbbb\nccc")
        state.setSelection(0, 0, 1, 3)
        state.duplicateLine()
        state.document.lineCount shouldBe 5
        state.document.line(0).toString() shouldBe "aaa"
        state.document.line(1).toString() shouldBe "bbb"
        state.document.line(2).toString() shouldBe "aaa"
        state.document.line(3).toString() shouldBe "bbb"
        state.document.line(4).toString() shouldBe "ccc"
    }
}
