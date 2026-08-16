package com.omnieditor.core.diff

import com.omnieditor.core.model.Granularity
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
}
