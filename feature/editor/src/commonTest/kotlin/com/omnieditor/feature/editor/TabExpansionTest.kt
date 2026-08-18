package com.omnieditor.feature.editor

import io.kotest.matchers.shouldBe
import org.junit.Test

class TabExpansionTest {

    // ── expandTabs ──────────────────────────────────────────────────────────

    @Test
    fun `tab at column 0 produces tabWidth spaces`() {
        expandTabs("\thello", 4) shouldBe "    hello"
    }

    @Test
    fun `tab mid-line aligns to next tab stop`() {
        expandTabs("ab\tc", 4) shouldBe "ab  c"
    }

    @Test
    fun `tab at exact tab stop produces full tabWidth spaces`() {
        expandTabs("abcd\te", 4) shouldBe "abcd    e"
    }

    @Test
    fun `multiple tabs expand independently`() {
        expandTabs("\t\t", 4) shouldBe "        "
    }

    @Test
    fun `tab width 8`() {
        expandTabs("ab\tc", 8) shouldBe "ab      c"
    }

    @Test
    fun `tab width 2`() {
        expandTabs("a\tb", 2) shouldBe "a b"
    }

    @Test
    fun `no tabs returns original text`() {
        expandTabs("hello world", 4) shouldBe "hello world"
    }

    @Test
    fun `empty string`() {
        expandTabs("", 4) shouldBe ""
    }

    @Test
    fun `only tabs`() {
        expandTabs("\t", 4) shouldBe "    "
    }

    @Test
    fun `tab width 1 replaces tab with single space`() {
        expandTabs("a\tb", 1) shouldBe "a b"
    }

    @Test
    fun `mixed content with multiple tabs`() {
        expandTabs("a\tbb\tc", 4) shouldBe "a   bb  c"
    }

    // ── buildDisplayToCharMap ────────────────────────────────────────────────

    @Test
    fun `display-to-char map for tab at start`() {
        val map = buildDisplayToCharMap("\tab", 4)
        // Tab expands to 4 spaces: display positions 0,1,2,3 -> char 0 (the tab)
        map[0] shouldBe 0
        map[1] shouldBe 0
        map[2] shouldBe 0
        map[3] shouldBe 0
        // 'a' at display position 4 -> char 1
        map[4] shouldBe 1
        // 'b' at display position 5 -> char 2
        map[5] shouldBe 2
        // End sentinel
        map[6] shouldBe 3
    }

    @Test
    fun `display-to-char map no tabs`() {
        val map = buildDisplayToCharMap("abc", 4)
        map[0] shouldBe 0
        map[1] shouldBe 1
        map[2] shouldBe 2
        map[3] shouldBe 3 // end sentinel
    }

    @Test
    fun `display-to-char map mid-line tab`() {
        // "ab\tc" with tabWidth=4 -> "ab  c" (2 spaces for tab)
        val map = buildDisplayToCharMap("ab\tc", 4)
        map[0] shouldBe 0 // 'a'
        map[1] shouldBe 1 // 'b'
        map[2] shouldBe 2 // tab (first space)
        map[3] shouldBe 2 // tab (second space)
        map[4] shouldBe 3 // 'c'
        map[5] shouldBe 4 // end sentinel
    }

    @Test
    fun `display-to-char map empty string`() {
        val map = buildDisplayToCharMap("", 4)
        map.size shouldBe 1
        map[0] shouldBe 0 // end sentinel
    }

    // ── buildCharToDisplayMap ────────────────────────────────────────────────

    @Test
    fun `char-to-display map for tab at start`() {
        val map = buildCharToDisplayMap("\tab", 4)
        map[0] shouldBe 0 // tab starts at display 0
        map[1] shouldBe 4 // 'a' at display 4
        map[2] shouldBe 5 // 'b' at display 5
        map[3] shouldBe 6 // end sentinel (total display width)
    }

    @Test
    fun `char-to-display map no tabs`() {
        val map = buildCharToDisplayMap("abc", 4)
        map[0] shouldBe 0
        map[1] shouldBe 1
        map[2] shouldBe 2
        map[3] shouldBe 3 // end sentinel
    }

    @Test
    fun `char-to-display map mid-line tab`() {
        // "ab\tc" with tabWidth=4 -> display "ab  c"
        val map = buildCharToDisplayMap("ab\tc", 4)
        map[0] shouldBe 0 // 'a'
        map[1] shouldBe 1 // 'b'
        map[2] shouldBe 2 // tab starts at display 2
        map[3] shouldBe 4 // 'c' at display 4
        map[4] shouldBe 5 // end sentinel
    }

    @Test
    fun `char-to-display map empty string`() {
        val map = buildCharToDisplayMap("", 4)
        map.size shouldBe 1
        map[0] shouldBe 0 // end sentinel
    }

    // ── Round-trip consistency ───────────────────────────────────────────────

    @Test
    fun `display-to-char and char-to-display are consistent`() {
        val text = "a\tbb\tccc\td"
        val tabWidth = 4
        val d2c = buildDisplayToCharMap(text, tabWidth)
        val c2d = buildCharToDisplayMap(text, tabWidth)
        val expanded = expandTabs(text, tabWidth)

        // Every char position maps to a display position, and that display
        // position maps back to the same char position.
        for (ci in text.indices) {
            val di = c2d[ci]
            d2c[di] shouldBe ci
        }

        // Display map length matches expanded text length + 1
        d2c.size shouldBe expanded.length + 1
        c2d.size shouldBe text.length + 1
    }

    @Test
    fun `round-trip for text with no tabs`() {
        val text = "hello world"
        val d2c = buildDisplayToCharMap(text, 4)
        val c2d = buildCharToDisplayMap(text, 4)

        // 1:1 mapping when no tabs
        for (i in text.indices) {
            d2c[i] shouldBe i
            c2d[i] shouldBe i
        }
    }

    @Test
    fun `consecutive tabs expand correctly`() {
        val text = "\t\t"
        val tabWidth = 4
        expandTabs(text, tabWidth) shouldBe "        " // 8 spaces

        val d2c = buildDisplayToCharMap(text, tabWidth)
        // First tab: display 0-3 -> char 0
        for (i in 0..3) d2c[i] shouldBe 0
        // Second tab: display 4-7 -> char 1
        for (i in 4..7) d2c[i] shouldBe 1
        // End sentinel
        d2c[8] shouldBe 2

        val c2d = buildCharToDisplayMap(text, tabWidth)
        c2d[0] shouldBe 0 // first tab starts at 0
        c2d[1] shouldBe 4 // second tab starts at 4
        c2d[2] shouldBe 8 // end sentinel
    }

    // ── Edge cases ──────────────────────────────────────────────────────────

    @Test
    fun `tab width 0 collapses tabs`() {
        expandTabs("a\tb", 0) shouldBe "ab"
    }

    @Test
    fun `CJK characters count as single columns for tab expansion`() {
        // Tab expansion is character-based, not display-width-based.
        // CJK chars each count as 1 column for tab-stop calculation.
        // This is the correct behavior — monospace alignment with CJK
        // would need a separate east-asian-width pass (future R-38).
        expandTabs("\u4e16\t", 4) shouldBe "\u4e16   "
    }

    @Test
    fun `display-to-char map preserves char identity for non-tab chars`() {
        // For every non-tab character, the display position maps back
        // to that character's index.
        val text = "Hello, World! 123"
        val map = buildDisplayToCharMap(text, 4)
        for (i in text.indices) {
            map[i] shouldBe i
        }
    }
}
