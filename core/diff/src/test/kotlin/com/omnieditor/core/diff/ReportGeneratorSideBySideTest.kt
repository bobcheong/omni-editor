package com.omnieditor.core.diff

import com.omnieditor.core.model.CompareResult
import com.omnieditor.core.model.CompareStats
import com.omnieditor.core.model.EngineMode
import com.omnieditor.core.model.Hunk
import com.omnieditor.core.model.HunkType
import com.omnieditor.core.model.RuleSet
import io.kotest.matchers.string.shouldContain
import org.junit.Test

class ReportGeneratorSideBySideTest {

    private val meta = ReportGenerator.ReportMeta(
        leftLabel = "left.txt", rightLabel = "right.txt",
        timestamp = "2026-08-17", rules = RuleSet.DEFAULT, engineMode = "FULL_INDEX",
    )

    @Test
    fun `htmlSideBySide produces table with left and right columns`() {
        val result = CompareResult(
            hunks = listOf(Hunk(1, 2, 1, 2, HunkType.CHANGED)),
            stats = CompareStats(linesChanged = 1, hunkCount = 1),
            engineMode = EngineMode.FULL_INDEX, generatedAt = 0,
        )
        val html = ReportGenerator.htmlSideBySide(
            result = result,
            leftLines = listOf("aaa", "bbb", "ccc"),
            rightLines = listOf("aaa", "BBB", "ccc"),
            meta = meta,
        )
        html shouldContain "<table"
        html shouldContain "left.txt"
        html shouldContain "right.txt"
        html shouldContain "bbb"
        html shouldContain "BBB"
    }

    @Test
    fun `htmlSideBySide with SELECTION scope only includes specified range`() {
        val result = CompareResult(
            hunks = listOf(
                Hunk(1, 2, 1, 2, HunkType.CHANGED),
                Hunk(3, 4, 3, 4, HunkType.CHANGED),
            ),
            stats = CompareStats(linesChanged = 2, hunkCount = 2),
            engineMode = EngineMode.FULL_INDEX, generatedAt = 0,
        )
        val html = ReportGenerator.htmlSideBySide(
            result = result,
            leftLines = listOf("a", "b", "c", "d", "e"),
            rightLines = listOf("a", "B", "c", "D", "e"),
            meta = meta,
            scope = ReportGenerator.ReportScope.SELECTION,
            scopeRange = 0L..2L,
        )
        html shouldContain "b"
        html shouldContain "B"
    }

    @Test
    fun `report header includes rules and engine mode`() {
        val result = CompareResult(
            hunks = emptyList(),
            stats = CompareStats(), engineMode = EngineMode.FULL_INDEX, generatedAt = 0,
        )
        val html = ReportGenerator.htmlSideBySide(
            result = result, leftLines = listOf("x"), rightLines = listOf("x"), meta = meta,
        )
        html shouldContain "FULL_INDEX"
        html shouldContain "left.txt"
    }
}
