package com.omnieditor.core.diff

import com.omnieditor.core.model.Granularity
import com.omnieditor.core.model.LinePair
import io.kotest.matchers.shouldBe
import org.junit.Test

class WordMergeTest {

    @Test
    fun `merge takes all from left when all selections are LEFT`() {
        val result = WordMerge.merge(
            leftLine = "hello world foo",
            rightLine = "hello earth bar",
            granularity = Granularity.WORD,
            selections = listOf(WordMerge.Side.LEFT, WordMerge.Side.LEFT),
        )
        result shouldBe "hello world foo"
    }

    @Test
    fun `merge takes all from right when all selections are RIGHT`() {
        val result = WordMerge.merge(
            leftLine = "hello world foo",
            rightLine = "hello earth bar",
            granularity = Granularity.WORD,
            selections = listOf(WordMerge.Side.RIGHT, WordMerge.Side.RIGHT),
        )
        result shouldBe "hello earth bar"
    }

    @Test
    fun `merge mixes left and right selections`() {
        val result = WordMerge.merge(
            leftLine = "aaa bbb ccc",
            rightLine = "aaa XXX YYY",
            granularity = Granularity.WORD,
            selections = listOf(WordMerge.Side.LEFT, WordMerge.Side.RIGHT),
        )
        // First changed range (bbb→XXX) takes left, second (ccc→YYY) takes right
        result shouldBe "aaa bbb YYY"
    }

    @Test
    fun `merge with no changes returns original`() {
        val result = WordMerge.merge(
            leftLine = "identical line",
            rightLine = "identical line",
            granularity = Granularity.WORD,
            selections = emptyList(),
        )
        result shouldBe "identical line"
    }

    // ── C.3 property tests: word-merge identity invariants ──

    @Test
    fun `C3 all LEFT selections produce byte-identical left line`() {
        val testCases = listOf(
            Pair("hello world", "hello earth"),
            Pair("foo bar baz", "foo XXX YYY"),
            Pair("abc def ghi jkl", "abc DEF ghi JKL"),
            Pair("one two three", "ONE TWO THREE"),
            Pair("", "something"),
            Pair("unchanged", "unchanged"),
            Pair("a b c d e f", "a X c X e X"),
            Pair("café résumé", "cafe resume"),
            Pair("line with    spaces", "line without spaces"),
            Pair("mixed CASE words", "mixed case WORDS"),
        )
        for ((left, right) in testCases) {
            val diff = IntraLineDiff.compute(
                LinePair(0L, 0L, left, right), Granularity.WORD,
            )
            val allLeft = List(diff.leftRanges.size) { WordMerge.Side.LEFT }
            val result = WordMerge.merge(left, right, Granularity.WORD, allLeft)
            result shouldBe left
        }
    }

    @Test
    fun `C3 all RIGHT selections produce byte-identical right line`() {
        val testCases = listOf(
            Pair("hello world", "hello earth"),
            Pair("foo bar baz", "foo XXX YYY"),
            Pair("abc def ghi jkl", "abc DEF ghi JKL"),
            Pair("one two three", "ONE TWO THREE"),
            Pair("unchanged", "unchanged"),
            Pair("a b c d e f", "a X c X e X"),
            Pair("café résumé", "cafe resume"),
            Pair("line with    spaces", "line without spaces"),
        )
        for ((left, right) in testCases) {
            val diff = IntraLineDiff.compute(
                LinePair(0L, 0L, left, right), Granularity.WORD,
            )
            val allRight = List(diff.leftRanges.size) { WordMerge.Side.RIGHT }
            val result = WordMerge.merge(left, right, Granularity.WORD, allRight)
            result shouldBe right
        }
    }
}
