package com.omnieditor.feature.editor

import com.omnieditor.core.io.PieceTableDocument
import io.kotest.matchers.shouldBe
import org.junit.Test

class EditorStateTest {

    /**
     * R-05: deleteSelection() destroys the tail of the last selected line.
     *
     * Bug location: EditorState.deleteSelection() line ~131:
     *   document.edit(startLine..endLine, document.line(startLine).substring(0, startCol.toInt()))
     *
     * The replacement only contains the prefix of startLine up to startCol. It does NOT
     * append the suffix of endLine after endCol. So any content on endLine after the
     * selection end is silently dropped.
     *
     * Input:  "aaaBBBcc\nddeee"   (2 lines)
     * Select: line 0 col 3 → line 1 col 2  (selects "BBBcc\ndd")
     * Expected after delete: "aaa" + "eee" = "aaaeee"  (1 line)
     * Actual (bugged):       "aaa"                      (1 line, "eee" lost)
     */
    @Test
    fun `R-05 delete selection preserves tail of last line`() {
        val doc = PieceTableDocument.create("aaaBBBcc\nddeee")
        val state = EditorState(doc)

        // Position caret at line 1, col 2 (after "dd")
        state.caretLine = 1L
        state.caretColumn = 2

        // Anchor at line 0, col 3 (after "aaa") — selection covers "BBBcc\ndd"
        state.selectionAnchorLine = 0L
        state.selectionAnchorColumn = 3

        state.deleteSelection()

        // After deleting "BBBcc\ndd", the two line fragments "aaa" and "eee" should join.
        // Bug: the "eee" tail of line 1 is dropped entirely.
        doc.lineCount shouldBe 1
        doc.line(0).toString() shouldBe "aaaeee"
    }
}
