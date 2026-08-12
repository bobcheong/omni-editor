package com.omnieditor.feature.editor

import com.omnieditor.core.io.PieceTableDocument
import com.omnieditor.core.model.LineEnding
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.Test

/**
 * Unit tests for R-36a programmatic text tools.
 *
 * These tests drive [EditorViewModel] directly without an Android runtime, confirming:
 * - Each text tool rewrites the document as one undoable unit.
 * - Tools changing >50 % of lines go through [PendingTool] confirmation (spec §2.3).
 * - [countChangedLines] counts correctly.
 * - Go-to-line moves the caret (0-based) and adjusts scroll.
 * - Case conversion methods are absent (excluded from v0.1, spec §2.3).
 * - Join/split/convertLineEnding/convertEncoding work.
 */
class EditorViewModelTextToolsTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Open a fresh, un-injected ViewModel with [content] loaded. */
    private fun vmWith(content: String): EditorViewModel {
        val vm = EditorViewModel()
        vm.openDocument(content)
        return vm
    }

    private fun EditorViewModel.currentText(): String =
        (uiState.value as EditorUiState.Loaded).editorState.document.text()

    // ── sortLines ─────────────────────────────────────────────────────────────

    @Test
    fun `sortLines reorders lines alphabetically`() {
        val vm = vmWith("banana\napple\ncherry")
        vm.sortLines()
        // 2 of 3 lines change positions → 2 > (3/2=1) → confirmation required
        val pending = vm.pendingTool.value
        pending.shouldNotBeNull()
        pending.changedLines shouldBe 2
        vm.confirmPendingTool()
        vm.currentText() shouldBe "apple\nbanana\ncherry"
    }

    @Test
    fun `sortLines with already-sorted content does not change text`() {
        val vm = vmWith("apple\nbanana\ncherry")
        vm.sortLines()
        vm.pendingTool.value.shouldBeNull()
        vm.currentText() shouldBe "apple\nbanana\ncherry"
    }

    // ── deduplicateLines ──────────────────────────────────────────────────────

    @Test
    fun `deduplicateLines removes duplicate lines`() {
        val vm = vmWith("a\nb\na\nc\nb")
        vm.deduplicateLines()
        // 3 of 5 lines removed → >50% → confirmation required
        val pending = vm.pendingTool.value
        pending.shouldNotBeNull()
        pending.changedLines shouldBe 3
        pending.totalLines shouldBe 5
        // Confirm and verify result
        vm.confirmPendingTool()
        vm.currentText() shouldBe "a\nb\nc"
    }

    @Test
    fun `deduplicateLines cancel leaves document unchanged`() {
        val original = "a\nb\na\nc\nb"
        val vm = vmWith(original)
        vm.deduplicateLines()
        vm.pendingTool.value.shouldNotBeNull()
        vm.cancelPendingTool()
        vm.pendingTool.value.shouldBeNull()
        vm.currentText() shouldBe original
    }

    // ── trimTrailing ──────────────────────────────────────────────────────────

    @Test
    fun `trimTrailing removes trailing spaces`() {
        val vm = vmWith("hello   \nworld  \nfoo")
        vm.trimTrailing()
        // 2 of 3 lines changed → >50% → confirmation
        val pending = vm.pendingTool.value
        pending.shouldNotBeNull()
        vm.confirmPendingTool()
        vm.currentText() shouldBe "hello\nworld\nfoo"
    }

    // ── reverseLines ──────────────────────────────────────────────────────────

    @Test
    fun `reverseLines inverts line order`() {
        val vm = vmWith("a\nb\nc")
        vm.reverseLines()
        // All 3 lines change → >50% → confirmation
        vm.pendingTool.value.shouldNotBeNull()
        vm.confirmPendingTool()
        vm.currentText() shouldBe "c\nb\na"
    }

    // ── removeBlankLines ──────────────────────────────────────────────────────

    @Test
    fun `removeBlankLines removes empty lines`() {
        val vm = vmWith("a\n\nb\n\nc")
        vm.removeBlankLines()
        // 2 of 5 blank lines removed → >50% → confirmation
        vm.pendingTool.value.shouldNotBeNull()
        vm.confirmPendingTool()
        vm.currentText() shouldBe "a\nb\nc"
    }

    // ── tabsToSpaces / spacesToTabs ───────────────────────────────────────────

    @Test
    fun `tabsToSpaces converts tab to 4 spaces`() {
        // 1 line changes of 1 total → 1 > (1/2=0) → confirmation required
        val vm = vmWith("\thello")
        vm.tabsToSpaces()
        vm.pendingTool.value.shouldNotBeNull()
        vm.confirmPendingTool()
        vm.currentText() shouldBe "    hello"
    }

    @Test
    fun `tabsToSpaces on two lines where only one has tab — no confirmation`() {
        // 1 of 4 lines changes → 1 > (4/2=2) = false → no confirmation
        val vm = vmWith("no-tab\nno-tab\nno-tab\n\thello")
        vm.tabsToSpaces()
        vm.pendingTool.value.shouldBeNull()
        vm.currentText() shouldBe "no-tab\nno-tab\nno-tab\n    hello"
    }

    @Test
    fun `spacesToTabs converts leading 4 spaces to tab`() {
        // 1 line changes of 1 total → 1 > (1/2=0) → confirmation required
        val vm = vmWith("    hello")
        vm.spacesToTabs()
        vm.pendingTool.value.shouldNotBeNull()
        vm.confirmPendingTool()
        vm.currentText() shouldBe "\thello"
    }

    // ── joinLines ─────────────────────────────────────────────────────────────

    @Test
    fun `joinLines collapses all lines into one separated by space`() {
        val vm = vmWith("hello\nworld\nfoo")
        vm.joinLines()
        // All 3 lines collapsed to 1 → all lines changed → >50% → confirmation
        vm.pendingTool.value.shouldNotBeNull()
        vm.confirmPendingTool()
        vm.currentText() shouldBe "hello world foo"
    }

    @Test
    fun `joinLines on single line is a no-op (no confirmation)`() {
        val vm = vmWith("only one line")
        vm.joinLines()
        // 0 of 1 lines changed
        vm.pendingTool.value.shouldBeNull()
        vm.currentText() shouldBe "only one line"
    }

    // ── splitLines ────────────────────────────────────────────────────────────

    @Test
    fun `splitLines splits each line at delimiter`() {
        val vm = vmWith("a,b,c\nd,e")
        vm.splitLines(",")
        // Original: 2 lines; result: 5 lines — all original lines change → >50%
        vm.pendingTool.value.shouldNotBeNull()
        vm.confirmPendingTool()
        vm.currentText() shouldBe "a\nb\nc\nd\ne"
    }

    @Test
    fun `splitLines with empty delimiter is no-op`() {
        val original = "hello\nworld"
        val vm = vmWith(original)
        vm.splitLines("")
        vm.pendingTool.value.shouldBeNull()
        vm.currentText() shouldBe original
    }

    // ── convertLineEnding ─────────────────────────────────────────────────────

    @Test
    fun `convertLineEnding to CRLF replaces LF with CRLF`() {
        val vm = vmWith("a\nb\nc")
        vm.convertLineEnding("\r\n")
        // convertLineEnding bypasses the confirmation path (it writes directly)
        vm.currentText() shouldBe "a\r\nb\r\nc"
    }

    @Test
    fun `convertLineEnding to LF normalises CRLF`() {
        val vm = vmWith("a\r\nb\r\nc")
        vm.convertLineEnding("\n")
        vm.currentText() shouldBe "a\nb\nc"
    }

    @Test
    fun `convertLineEnding is no-op when text already uses target ending`() {
        val original = "a\nb\nc"
        val vm = vmWith(original)
        vm.convertLineEnding("\n")
        vm.currentText() shouldBe original
    }

    // ── convertEncoding ───────────────────────────────────────────────────────

    @Test
    fun `convertEncoding from UTF-8 to UTF-8 is no-op`() {
        val original = "hello"
        val vm = vmWith(original)
        vm.convertEncoding("UTF-8", "UTF-8")
        vm.currentText() shouldBe original
    }

    @Test
    fun `convertEncoding with invalid charset emits Error state`() {
        val vm = vmWith("hello")
        vm.convertEncoding("UTF-8", "INVALID-CHARSET-XYZ")
        vm.uiState.value shouldBe EditorUiState.Error(
            "Encoding conversion from UTF-8 to INVALID-CHARSET-XYZ failed"
        )
    }

    // ── countChangedLines ─────────────────────────────────────────────────────

    @Test
    fun `countChangedLines counts differing lines`() {
        val vm = EditorViewModel()
        vm.countChangedLines("a\nb\nc", "a\nX\nc") shouldBe 1
        vm.countChangedLines("a\nb\nc", "a\nb\nc") shouldBe 0
        vm.countChangedLines("a\nb", "a\nb\nc") shouldBe 1   // extra line
        vm.countChangedLines("a\nb\nc", "a\nb") shouldBe 1   // removed line
    }

    // ── goToLine ──────────────────────────────────────────────────────────────

    @Test
    fun `goToLine moves caret to correct 0-based line`() {
        val vm = vmWith("line0\nline1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9")
        vm.goToLine(7L)   // 0-based: line 7
        val state = (vm.uiState.value as EditorUiState.Loaded).editorState
        state.caretLine shouldBe 7L
        state.caretColumn shouldBe 0
    }

    @Test
    fun `goToLine adjusts firstVisibleLine to keep caret in view`() {
        val lines = (0..20).joinToString("\n") { "line$it" }
        val vm = vmWith(lines)
        vm.goToLine(15L)
        val state = (vm.uiState.value as EditorUiState.Loaded).editorState
        // firstVisibleLine = max(0, 15-5) = 10
        state.firstVisibleLine shouldBe 10L
    }

    @Test
    fun `goToLine clamps to last line on overflow`() {
        val vm = vmWith("only\ntwo")
        vm.goToLine(999L)
        val state = (vm.uiState.value as EditorUiState.Loaded).editorState
        state.caretLine shouldBe 1L   // last line (0-based)
    }

    // ── undo for text tools ───────────────────────────────────────────────────

    @Test
    fun `sortLines is a single undo step`() {
        val original = "banana\napple\ncherry"
        val vm = vmWith(original)
        vm.sortLines()
        vm.confirmPendingTool()   // 2 of 3 lines change → confirmation required
        vm.undo()
        vm.currentText() shouldBe original
    }

    @Test
    fun `removeBlankLines can be undone after confirmation`() {
        val original = "a\n\nb\n\nc"
        val vm = vmWith(original)
        vm.removeBlankLines()
        vm.confirmPendingTool()
        vm.undo()
        vm.currentText() shouldBe original
    }

    // ── case conversion excluded from v0.1 ────────────────────────────────────

    @Test
    fun `EditorViewModel does not expose toUpperCase method`() {
        // Verify at compile time by ensuring the method name is absent.
        // If this file compiles, the assertion is satisfied.
        val methods = EditorViewModel::class.java.declaredMethods.map { it.name }
        assert("toUpperCase" !in methods) {
            "toUpperCase must not be in EditorViewModel in v0.1 (excluded per spec §2.3)"
        }
    }

    @Test
    fun `EditorViewModel does not expose toLowerCase method`() {
        val methods = EditorViewModel::class.java.declaredMethods.map { it.name }
        assert("toLowerCase" !in methods) {
            "toLowerCase must not be in EditorViewModel in v0.1 (excluded per spec §2.3)"
        }
    }
}
