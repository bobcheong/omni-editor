package com.omnieditor.feature.editor

import com.omnieditor.core.io.PieceTableDocument
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Tests for R-16 IME-related EditorState operations:
 * deleteBackward, deleteForward, deleteSurrounding,
 * composing text, and commit.
 */
class ImeEditorStateTest {

    private fun stateOf(text: String): EditorState =
        EditorState(PieceTableDocument.create(text))

    // ── deleteBackward ───────────────────────────────────────────────────

    @Test
    fun `deleteBackward removes character before caret`() {
        val state = stateOf("hello")
        state.moveCaret(0, 3)
        state.deleteBackward()
        state.document.line(0).toString() shouldBe "helo"
        state.caretColumn shouldBe 2
    }

    @Test
    fun `deleteBackward at start of line joins with previous`() {
        val state = stateOf("abc\ndef")
        state.moveCaret(1, 0)
        state.deleteBackward()
        state.document.lineCount shouldBe 1
        state.document.line(0).toString() shouldBe "abcdef"
        state.caretLine shouldBe 0L
        state.caretColumn shouldBe 3
    }

    @Test
    fun `deleteBackward at start of first line is a no-op`() {
        val state = stateOf("hello")
        state.moveCaret(0, 0)
        state.deleteBackward()
        state.document.line(0).toString() shouldBe "hello"
        state.caretColumn shouldBe 0
    }

    @Test
    fun `deleteBackward with selection deletes selection`() {
        val state = stateOf("hello world")
        state.setSelection(0, 5, 0, 11)
        state.deleteBackward()
        state.document.line(0).toString() shouldBe "hello"
        state.hasSelection shouldBe false
    }

    @Test
    fun `deleteBackward is a no-op in read-only mode`() {
        val state = stateOf("hello")
        state.readOnly = true
        state.moveCaret(0, 3)
        state.deleteBackward()
        state.document.line(0).toString() shouldBe "hello"
    }

    // ── deleteForward ────────────────────────────────────────────────────

    @Test
    fun `deleteForward removes character after caret`() {
        val state = stateOf("hello")
        state.moveCaret(0, 2)
        state.deleteForward()
        state.document.line(0).toString() shouldBe "helo"
        state.caretColumn shouldBe 2
    }

    @Test
    fun `deleteForward at end of line joins with next`() {
        val state = stateOf("abc\ndef")
        state.moveCaret(0, 3)
        state.deleteForward()
        state.document.lineCount shouldBe 1
        state.document.line(0).toString() shouldBe "abcdef"
        state.caretLine shouldBe 0L
        state.caretColumn shouldBe 3
    }

    @Test
    fun `deleteForward at end of last line is a no-op`() {
        val state = stateOf("hello")
        state.moveCaret(0, 5)
        state.deleteForward()
        state.document.line(0).toString() shouldBe "hello"
    }

    @Test
    fun `deleteForward with selection deletes selection`() {
        val state = stateOf("hello world")
        state.setSelection(0, 0, 0, 5)
        state.deleteForward()
        state.document.line(0).toString() shouldBe " world"
    }

    @Test
    fun `deleteForward is a no-op in read-only mode`() {
        val state = stateOf("hello")
        state.readOnly = true
        state.moveCaret(0, 2)
        state.deleteForward()
        state.document.line(0).toString() shouldBe "hello"
    }

    // ── deleteSurrounding ────────────────────────────────────────────────

    @Test
    fun `deleteSurrounding removes chars before and after caret`() {
        val state = stateOf("abcdef")
        state.moveCaret(0, 3)
        state.deleteSurrounding(beforeLength = 2, afterLength = 1)
        state.document.line(0).toString() shouldBe "aef"
        state.caretColumn shouldBe 1
    }

    @Test
    fun `deleteSurrounding clamps to line boundaries`() {
        val state = stateOf("abc")
        state.moveCaret(0, 1)
        state.deleteSurrounding(beforeLength = 5, afterLength = 5)
        state.document.line(0).toString() shouldBe ""
        state.caretColumn shouldBe 0
    }

    @Test
    fun `deleteSurrounding is a no-op in read-only mode`() {
        val state = stateOf("abcdef")
        state.readOnly = true
        state.moveCaret(0, 3)
        state.deleteSurrounding(beforeLength = 1, afterLength = 1)
        state.document.line(0).toString() shouldBe "abcdef"
    }

    // ── Composing text ───────────────────────────────────────────────────

    @Test
    fun `setComposingText inserts composing text at caret`() {
        val state = stateOf("hello")
        state.moveCaret(0, 5)
        state.setComposingText("world")
        state.document.line(0).toString() shouldBe "helloworld"
        state.isComposing shouldBe true
        state.composingStart shouldBe 5
        state.composingEnd shouldBe 10
        state.composingLine shouldBe 0L
        state.caretColumn shouldBe 10
    }

    @Test
    fun `setComposingText replaces existing composing region`() {
        val state = stateOf("hello")
        state.moveCaret(0, 5)
        state.setComposingText("AB")
        state.document.line(0).toString() shouldBe "helloAB"

        // Replace composing text with something else
        state.setComposingText("XYZ")
        state.document.line(0).toString() shouldBe "helloXYZ"
        state.composingStart shouldBe 5
        state.composingEnd shouldBe 8
        state.caretColumn shouldBe 8
    }

    @Test
    fun `commitComposingText clears composing region`() {
        val state = stateOf("hello")
        state.moveCaret(0, 5)
        state.setComposingText("world")
        state.commitComposingText()
        state.isComposing shouldBe false
        state.composingStart shouldBe -1
        state.composingEnd shouldBe -1
        // Text should remain
        state.document.line(0).toString() shouldBe "helloworld"
    }

    @Test
    fun `setComposingText with empty string clears composing region`() {
        val state = stateOf("hello")
        state.moveCaret(0, 5)
        state.setComposingText("AB")
        state.setComposingText("")
        state.isComposing shouldBe false
        state.document.line(0).toString() shouldBe "hello"
    }

    @Test
    fun `setComposingText is a no-op in read-only mode`() {
        val state = stateOf("hello")
        state.readOnly = true
        state.moveCaret(0, 5)
        state.setComposingText("world")
        state.isComposing shouldBe false
        state.document.line(0).toString() shouldBe "hello"
    }

    @Test
    fun `composing text in middle of line`() {
        val state = stateOf("hello world")
        state.moveCaret(0, 5)
        state.setComposingText(" beautiful")
        state.document.line(0).toString() shouldBe "hello beautiful world"
        state.composingStart shouldBe 5
        state.composingEnd shouldBe 15
    }

    // ── isComposing ──────────────────────────────────────────────────────

    @Test
    fun `isComposing is false initially`() {
        val state = stateOf("hello")
        state.isComposing shouldBe false
    }

    @Test
    fun `clearComposingRegion resets composing markers`() {
        val state = stateOf("hello")
        state.moveCaret(0, 5)
        state.setComposingText("x")
        state.isComposing shouldBe true
        state.clearComposingRegion()
        state.isComposing shouldBe false
    }
}
