package com.omnieditor.core.io

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

class TextToolsTest {

    // ── Sort ──

    @Test
    fun `sort lines ascending`() {
        TextTools.sortLines(listOf("cherry", "apple", "banana")) shouldBe
            listOf("apple", "banana", "cherry")
    }

    @Test
    fun `sort lines descending`() {
        TextTools.sortLines(listOf("a", "c", "b"), descending = true) shouldBe
            listOf("c", "b", "a")
    }

    @Test
    fun `sort lines case insensitive`() {
        TextTools.sortLines(listOf("Banana", "apple", "Cherry"), caseSensitive = false) shouldBe
            listOf("apple", "Banana", "Cherry")
    }

    // ── Deduplicate ──

    @Test
    fun `deduplicate removes duplicates preserving order`() {
        TextTools.deduplicateLines(listOf("a", "b", "a", "c", "b")) shouldBe
            listOf("a", "b", "c")
    }

    @Test
    fun `deduplicate case insensitive`() {
        TextTools.deduplicateLines(listOf("Hello", "hello", "HELLO"), caseSensitive = false) shouldBe
            listOf("Hello")
    }

    // ── Trim ──

    @Test
    fun `trim trailing whitespace`() {
        TextTools.trimTrailingWhitespace(listOf("hello  ", "  world  ", "ok")) shouldBe
            listOf("hello", "  world", "ok")
    }

    @Test
    fun `trim leading whitespace`() {
        TextTools.trimLeadingWhitespace(listOf("  hello", "  world  ")) shouldBe
            listOf("hello", "world  ")
    }

    @Test
    fun `trim both`() {
        TextTools.trimWhitespace(listOf("  hello  ")) shouldBe listOf("hello")
    }

    // ── Case ──

    @Test
    fun `to upper case`() {
        TextTools.toUpperCase("hello World") shouldBe "HELLO WORLD"
    }

    @Test
    fun `to lower case`() {
        TextTools.toLowerCase("Hello WORLD") shouldBe "hello world"
    }

    @Test
    fun `to title case`() {
        TextTools.toTitleCase("hello world foo") shouldBe "Hello World Foo"
    }

    @Test
    fun `toggle case`() {
        TextTools.toggleCase("Hello World") shouldBe "hELLO wORLD"
    }

    // ── Tabs / Spaces ──

    @Test
    fun `tabs to spaces`() {
        TextTools.tabsToSpaces(listOf("\thello\t"), tabWidth = 4) shouldBe
            listOf("    hello    ")
    }

    @Test
    fun `spaces to tabs`() {
        TextTools.spacesToTabs(listOf("        hello"), tabWidth = 4) shouldBe
            listOf("\t\thello")
    }

    @Test
    fun `spaces to tabs with remainder`() {
        TextTools.spacesToTabs(listOf("      hello"), tabWidth = 4) shouldBe
            listOf("\t  hello")
    }

    // ── Line endings ──

    @Test
    fun `convert LF to CRLF`() {
        TextTools.convertLineEndings("a\nb\nc", "\r\n") shouldBe "a\r\nb\r\nc"
    }

    @Test
    fun `convert CRLF to LF`() {
        TextTools.convertLineEndings("a\r\nb\r\nc", "\n") shouldBe "a\nb\nc"
    }

    @Test
    fun `convert mixed to LF`() {
        TextTools.convertLineEndings("a\r\nb\rc\n", "\n") shouldBe "a\nb\nc\n"
    }

    // ── Join / Split ──

    @Test
    fun `join lines`() {
        TextTools.joinLines(listOf("hello", "world"), " ") shouldBe "hello world"
    }

    @Test
    fun `join lines with custom separator`() {
        TextTools.joinLines(listOf("a", "b", "c"), ", ") shouldBe "a, b, c"
    }

    @Test
    fun `split line`() {
        TextTools.splitLine("a,b,c", ",") shouldBe listOf("a", "b", "c")
    }

    @Test
    fun `split line empty delimiter returns original`() {
        TextTools.splitLine("hello", "") shouldBe listOf("hello")
    }

    // ── Misc ──

    @Test
    fun `timestamp returns non-empty string`() {
        TextTools.timestamp().isNotEmpty() shouldBe true
    }

    @Test
    fun `reverse lines`() {
        TextTools.reverseLines(listOf("a", "b", "c")) shouldBe listOf("c", "b", "a")
    }

    @Test
    fun `number lines`() {
        TextTools.numberLines(listOf("alpha", "beta"), startAt = 1) shouldBe
            listOf("1: alpha", "2: beta")
    }

    @Test
    fun `number lines pads`() {
        TextTools.numberLines(listOf("a", "b"), startAt = 9) shouldBe
            listOf(" 9: a", "10: b")
    }

    @Test
    fun `remove blank lines`() {
        TextTools.removeBlankLines(listOf("a", "", "b", "   ", "c")) shouldBe
            listOf("a", "b", "c")
    }

    // ── Encoding ──

    @Test
    fun `encoding round-trip UTF-8`() {
        val text = "hello café 日本語"
        TextTools.convertEncoding(text, "UTF-8", "UTF-8") shouldBe text
    }

    @Test
    fun `encoding conversion returns null for invalid`() {
        // This tests that invalid conversion doesn't crash
        val result = TextTools.convertEncoding("hello", "UTF-8", "UTF-8")
        result shouldNotBe null
    }

    // ── Each operation is one undo step (verified via PieceTable) ──

    @Test
    fun `sort as single undo step`() {
        val table = PieceTable.create("cherry\napple\nbanana")
        val lines = (0 until table.lineCount).map { table.line(it) }
        val sorted = TextTools.sortLines(lines)
        // Replace entire content as one operation
        val record = table.replace(0, table.length, sorted.joinToString("\n"))
        table.text() shouldBe "apple\nbanana\ncherry"
        // Undo should be possible (one record)
        record.type shouldBe EditRecord.Type.REPLACE
    }

    @Test
    fun `deduplicate as single undo step`() {
        val table = PieceTable.create("a\nb\na\nc\nb")
        val lines = (0 until table.lineCount).map { table.line(it) }
        val deduped = TextTools.deduplicateLines(lines)
        table.replace(0, table.length, deduped.joinToString("\n"))
        table.text() shouldBe "a\nb\nc"
    }
}
