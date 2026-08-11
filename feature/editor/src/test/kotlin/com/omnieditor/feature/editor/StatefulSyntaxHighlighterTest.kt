package com.omnieditor.feature.editor

import com.omnieditor.core.diff.syntax.SyntaxEngine
import com.omnieditor.core.diff.syntax.TokenType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

class StatefulSyntaxHighlighterTest {

    private val kotlinGrammar = SyntaxEngine.grammarFor("kotlin")!!

    // ── Basic tokenization ──

    @Test
    fun `tokenizes a normal line with keyword`() {
        val h = StatefulSyntaxHighlighter(kotlinGrammar)
        val tokens = h.tokenizeLine(0, "fun main() {}")
        tokens.any { it.type == TokenType.KEYWORD } shouldBe true
    }

    @Test
    fun `empty line returns empty token list`() {
        val h = StatefulSyntaxHighlighter(kotlinGrammar)
        h.tokenizeLine(0, "") shouldBe emptyList()
    }

    // ── Block-comment state carry ──

    @Test
    fun `carries BLOCK_COMMENT state across lines`() {
        val h = StatefulSyntaxHighlighter(kotlinGrammar)

        // Line 0: opens a block comment
        val t0 = h.tokenizeLine(0, "/* start")
        t0.any { it.type == TokenType.COMMENT } shouldBe true

        // Line 1: should be entirely in comment
        val t1 = h.tokenizeLine(1, "   still inside")
        t1.all { it.type == TokenType.COMMENT } shouldBe true

        // Line 2: closes the comment
        val t2 = h.tokenizeLine(2, "   end */")
        t2.any { it.type == TokenType.COMMENT } shouldBe true

        // Line 3: back to normal — keyword should be highlighted
        val t3 = h.tokenizeLine(3, "val x = 1")
        t3.any { it.type == TokenType.KEYWORD } shouldBe true
    }

    // ── Multi-line string carry ──

    @Test
    fun `carries MULTILINE_STRING state across lines`() {
        val h = StatefulSyntaxHighlighter(kotlinGrammar)

        // Line 0: opens triple-quoted string
        h.tokenizeLine(0, "val s = \"\"\"")

        // Line 1: entirely inside the string
        val t1 = h.tokenizeLine(1, "  inside the string")
        t1.all { it.type == TokenType.STRING } shouldBe true

        // Line 2: closes the string
        val t2 = h.tokenizeLine(2, "\"\"\"")
        t2.any { it.type == TokenType.STRING } shouldBe true

        // Line 3: back to normal
        val t3 = h.tokenizeLine(3, "fun next() {}")
        t3.any { it.type == TokenType.KEYWORD } shouldBe true
    }

    // ── Invalidation ──

    @Test
    fun `invalidateFrom causes re-lex from that line`() {
        val h = StatefulSyntaxHighlighter(kotlinGrammar)

        // Lex a few lines normally
        h.tokenizeLine(0, "val a = 1")
        h.tokenizeLine(1, "val b = 2")
        h.tokenizeLine(2, "val c = 3")

        // Invalidate from line 1 (simulates edit at line 1)
        h.invalidateFrom(1)

        // Re-lexing line 2 should still produce correct tokens (not crash)
        val tokens = h.tokenizeLine(2, "val c = 3")
        tokens shouldNotBe null
        tokens.any { it.type == TokenType.KEYWORD } shouldBe true
    }

    @Test
    fun `invalidateFrom with lower value widens the invalid range`() {
        val h = StatefulSyntaxHighlighter(kotlinGrammar)
        h.tokenizeLine(0, "val a = 1")
        h.tokenizeLine(1, "val b = 2")
        h.tokenizeLine(2, "val c = 3")

        h.invalidateFrom(2)
        h.invalidateFrom(1) // should widen to 1

        // Lexing line 1 should still work correctly
        val tokens = h.tokenizeLine(1, "val b = 2")
        tokens.any { it.type == TokenType.KEYWORD } shouldBe true
    }

    // ── notifyLineCountChanged ──

    @Test
    fun `notifyLineCountChanged does not crash on shrink`() {
        val h = StatefulSyntaxHighlighter(kotlinGrammar)
        h.tokenizeLine(0, "fun a() {}")
        h.tokenizeLine(1, "fun b() {}")
        h.tokenizeLine(2, "fun c() {}")

        // Simulate deletion of last line
        h.notifyLineCountChanged(2)

        // Subsequent lex of remaining lines should still work
        val tokens = h.tokenizeLine(0, "fun a() {}")
        tokens.any { it.type == TokenType.KEYWORD } shouldBe true
    }

    // ── Grammar without multiline strings ──

    @Test
    fun `java grammar block comments carried correctly`() {
        val javaGrammar = SyntaxEngine.grammarFor("java")!!
        val h = StatefulSyntaxHighlighter(javaGrammar)

        h.tokenizeLine(0, "/* open")
        val t1 = h.tokenizeLine(1, "  still in comment")
        t1.all { it.type == TokenType.COMMENT } shouldBe true

        val t2 = h.tokenizeLine(2, "  close */ int x;")
        t2.any { it.type == TokenType.COMMENT } shouldBe true
        // After close, TYPE token should appear
        t2.any { it.type == TokenType.TYPE } shouldBe true
    }
}
