package com.omnieditor.core.diff

import com.omnieditor.core.model.EngineMode
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class DiffEngineAutoTest {

    @Test
    fun `compareAuto uses FULL_INDEX below threshold`() = runTest {
        val left = listOf("a", "b", "c")
        val right = listOf("a", "x", "c")
        val result = DiffEngine.compareAuto(
            leftLineCount = 3,
            rightLineCount = 3,
            leftLine = { left[it.toInt()] },
            rightLine = { right[it.toInt()] },
        )
        result.engineMode shouldBe EngineMode.FULL_INDEX
        result.hunks.size shouldBe 1
    }

    @Test
    fun `compareAuto uses BLOCK_MATCH above threshold`() = runTest(timeout = 30.seconds) {
        // Generate lines above BlockDiff.DEFAULT_LINE_THRESHOLD (250k)
        val lineCount = 260_000L
        val result = DiffEngine.compareAuto(
            leftLineCount = lineCount,
            rightLineCount = lineCount,
            leftLine = { "line $it" },
            rightLine = { if (it == 130_000L) "CHANGED" else "line $it" },
        )
        result.engineMode shouldBe EngineMode.BLOCK_MATCH
    }

    @Test
    fun `compareAuto produces same hunks as compare for small files`() = runTest {
        val left = listOf("alpha", "beta", "gamma", "delta")
        val right = listOf("alpha", "BETA", "gamma", "DELTA")
        val full = DiffEngine.compare(
            leftLineCount = 4, rightLineCount = 4,
            leftLine = { left[it.toInt()] },
            rightLine = { right[it.toInt()] },
        )
        val auto = DiffEngine.compareAuto(
            leftLineCount = 4, rightLineCount = 4,
            leftLine = { left[it.toInt()] },
            rightLine = { right[it.toInt()] },
        )
        auto.hunks shouldBe full.hunks
    }
}
