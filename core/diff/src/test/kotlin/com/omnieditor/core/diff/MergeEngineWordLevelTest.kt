package com.omnieditor.core.diff

import com.omnieditor.core.model.CompareResult
import com.omnieditor.core.model.CompareStats
import com.omnieditor.core.model.EngineMode
import com.omnieditor.core.model.Hunk
import com.omnieditor.core.model.HunkType
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.Test

class MergeEngineWordLevelTest {

    @Test
    fun `mergeWordLevel produces action replacing line with merged content`() {
        val result = CompareResult(
            hunks = listOf(Hunk(0, 1, 0, 1, HunkType.CHANGED)),
            stats = CompareStats(linesChanged = 1, hunkCount = 1),
            engineMode = EngineMode.FULL_INDEX, generatedAt = 0,
        )
        val leftLines = listOf("hello world foo")
        val rightLines = listOf("hello earth bar")

        val action = MergeEngine.mergeWordLevel(
            hunkIndex = 0,
            result = result,
            leftLines = leftLines,
            rightLines = rightLines,
            direction = MergeEngine.Direction.LEFT_TO_RIGHT,
            selections = listOf(WordMerge.Side.LEFT, WordMerge.Side.RIGHT),
        )

        action shouldHaveSize 1
        // First changed range takes left ("world"), second takes right ("bar")
        action[0].replacementLines[0] shouldBe "hello world bar"
    }
}
