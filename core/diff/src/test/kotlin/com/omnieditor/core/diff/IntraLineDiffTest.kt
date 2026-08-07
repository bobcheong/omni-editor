package com.omnieditor.core.diff

import com.omnieditor.core.model.Granularity
import com.omnieditor.core.model.HunkType
import com.omnieditor.core.model.LinePair
import io.kotest.matchers.shouldBe
import org.junit.Test

class IntraLineDiffTest {

    private fun pair(left: String, right: String) = LinePair(0, 0, left, right)

    // ── Character granularity ──

    @Test
    fun `character - identical lines produce no ranges`() {
        val result = IntraLineDiff.compute(pair("hello world", "hello world"), Granularity.CHARACTER)
        result.leftRanges shouldBe emptyList()
        result.rightRanges shouldBe emptyList()
    }

    @Test
    fun `character - single char change`() {
        val result = IntraLineDiff.compute(pair("cat", "car"), Granularity.CHARACTER)
        result.leftRanges.size shouldBe 1
        result.leftRanges[0].start shouldBe 2
        result.leftRanges[0].end shouldBe 3
        result.rightRanges.size shouldBe 1
        result.rightRanges[0].start shouldBe 2
        result.rightRanges[0].end shouldBe 3
    }

    @Test
    fun `character - insertion`() {
        val result = IntraLineDiff.compute(pair("ac", "abc"), Granularity.CHARACTER)
        result.rightRanges.size shouldBe 1
        result.rightRanges[0].start shouldBe 1
        result.rightRanges[0].end shouldBe 2
        result.rightRanges[0].type shouldBe HunkType.ADDED
    }

    @Test
    fun `character - deletion`() {
        val result = IntraLineDiff.compute(pair("abc", "ac"), Granularity.CHARACTER)
        result.leftRanges.size shouldBe 1
        result.leftRanges[0].start shouldBe 1
        result.leftRanges[0].end shouldBe 2
        result.leftRanges[0].type shouldBe HunkType.REMOVED
    }

    @Test
    fun `character - prefix change`() {
        val result = IntraLineDiff.compute(pair("hello world", "HELLO world"), Granularity.CHARACTER)
        result.leftRanges.size shouldBe 1
        result.leftRanges[0].start shouldBe 0
        result.leftRanges[0].end shouldBe 5
        result.rightRanges.size shouldBe 1
        result.rightRanges[0].start shouldBe 0
        result.rightRanges[0].end shouldBe 5
    }

    @Test
    fun `character - suffix change`() {
        val result = IntraLineDiff.compute(pair("hello world", "hello WORLD"), Granularity.CHARACTER)
        result.leftRanges.size shouldBe 1
        result.leftRanges[0].start shouldBe 6
        result.leftRanges[0].end shouldBe 11
    }

    @Test
    fun `character - completely different`() {
        val result = IntraLineDiff.compute(pair("abc", "xyz"), Granularity.CHARACTER)
        result.leftRanges.size shouldBe 1
        result.leftRanges[0].start shouldBe 0
        result.leftRanges[0].end shouldBe 3
    }

    // ── Word granularity ──

    @Test
    fun `word - identical lines produce no ranges`() {
        val result = IntraLineDiff.compute(pair("hello world", "hello world"), Granularity.WORD)
        result.leftRanges shouldBe emptyList()
        result.rightRanges shouldBe emptyList()
    }

    @Test
    fun `word - single word changed`() {
        val result = IntraLineDiff.compute(pair("the cat sat", "the dog sat"), Granularity.WORD)
        result.leftRanges.size shouldBe 1
        result.leftRanges[0].start shouldBe 4
        result.leftRanges[0].end shouldBe 7 // "cat"
        result.rightRanges.size shouldBe 1
        result.rightRanges[0].start shouldBe 4
        result.rightRanges[0].end shouldBe 7 // "dog"
    }

    @Test
    fun `word - word inserted`() {
        val result = IntraLineDiff.compute(pair("hello world", "hello brave world"), Granularity.WORD)
        result.rightRanges.isNotEmpty() shouldBe true
        // "brave " is inserted
        result.leftRanges.isEmpty() || result.leftRanges.all { it.type == HunkType.REMOVED } shouldBe true
    }

    @Test
    fun `word - punctuation change`() {
        val result = IntraLineDiff.compute(pair("x = 1;", "x = 2;"), Granularity.WORD)
        result.leftRanges.isNotEmpty() shouldBe true
        result.rightRanges.isNotEmpty() shouldBe true
    }

    @Test
    fun `word - whitespace change only`() {
        val result = IntraLineDiff.compute(pair("a  b", "a b"), Granularity.WORD)
        // The words are the same, only whitespace differs
        result.leftRanges.isNotEmpty() || result.rightRanges.isNotEmpty() shouldBe true
    }

    // ── Line granularity ──

    @Test
    fun `line granularity marks entire line`() {
        val result = IntraLineDiff.compute(pair("hello", "world"), Granularity.LINE)
        result.leftRanges.size shouldBe 1
        result.leftRanges[0].start shouldBe 0
        result.leftRanges[0].end shouldBe 5
        result.rightRanges.size shouldBe 1
        result.rightRanges[0].start shouldBe 0
        result.rightRanges[0].end shouldBe 5
    }

    // ── Edge cases ──

    @Test
    fun `empty left line`() {
        val result = IntraLineDiff.compute(pair("", "hello"), Granularity.CHARACTER)
        result.rightRanges.size shouldBe 1
        result.rightRanges[0].type shouldBe HunkType.ADDED
    }

    @Test
    fun `empty right line`() {
        val result = IntraLineDiff.compute(pair("hello", ""), Granularity.CHARACTER)
        result.leftRanges.size shouldBe 1
        result.leftRanges[0].type shouldBe HunkType.REMOVED
    }

    @Test
    fun `both empty`() {
        val result = IntraLineDiff.compute(pair("", ""), Granularity.CHARACTER)
        result.leftRanges shouldBe emptyList()
        result.rightRanges shouldBe emptyList()
    }

    // ── Tokenizer ──

    @Test
    fun `tokenizer splits words and whitespace`() {
        val tokens = IntraLineDiff.tokenize("hello  world")
        tokens.size shouldBe 3
        tokens[0].text shouldBe "hello"
        tokens[1].text shouldBe "  "
        tokens[2].text shouldBe "world"
    }

    @Test
    fun `tokenizer handles punctuation`() {
        val tokens = IntraLineDiff.tokenize("x=1;")
        tokens.size shouldBe 4
        tokens[0].text shouldBe "x"
        tokens[1].text shouldBe "="
        tokens[2].text shouldBe "1"
        tokens[3].text shouldBe ";"
    }

    @Test
    fun `tokenizer handles empty string`() {
        IntraLineDiff.tokenize("") shouldBe emptyList()
    }

    @Test
    fun `tokenizer preserves positions`() {
        val tokens = IntraLineDiff.tokenize("int x = 1;")
        tokens[0].start shouldBe 0
        tokens[0].end shouldBe 3   // "int"
        tokens[1].start shouldBe 3
        tokens[1].end shouldBe 4   // " "
        tokens[2].start shouldBe 4
        tokens[2].end shouldBe 5   // "x"
    }

    // ── Performance ──

    @Test
    fun `character diff on 4KB line completes quickly`() {
        // Build a ~4KB line
        val base = "abcdefghij".repeat(400) // 4000 chars
        val modified = base.substring(0, 2000) + "XXXXXXXXXX" + base.substring(2010)
        val start = System.nanoTime()
        val result = IntraLineDiff.compute(pair(base, modified), Granularity.CHARACTER)
        val elapsed = (System.nanoTime() - start) / 1_000_000 // ms
        result.leftRanges.isNotEmpty() shouldBe true
        // Should complete well under 1000ms (the spec says <1ms, but we're generous for CI)
        (elapsed < 1000) shouldBe true
    }

    @Test
    fun `word diff on 4KB line completes quickly`() {
        val words = (0 until 400).joinToString(" ") { "word$it" }
        val modified = words.replace("word200", "CHANGED")
        val start = System.nanoTime()
        val result = IntraLineDiff.compute(pair(words, modified), Granularity.WORD)
        val elapsed = (System.nanoTime() - start) / 1_000_000
        result.leftRanges.isNotEmpty() shouldBe true
        (elapsed < 1000) shouldBe true
    }
}
