package com.omnieditor.core.diff

import com.omnieditor.core.model.RuleSet
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ReportGeneratorTest {

    private val meta = ReportGenerator.ReportMeta(
        leftLabel = "config.prod.yaml",
        rightLabel = "config.local.yaml",
        timestamp = "2026-08-08T12:00:00",
        rules = RuleSet.DEFAULT,
        engineMode = "FULL_INDEX",
    )

    private suspend fun diff(left: List<String>, right: List<String>) =
        DiffEngine.compare(
            left.size.toLong(), right.size.toLong(),
            { left[it.toInt()] }, { right[it.toInt()] },
        )

    // ── Unified diff patch ──

    @Test
    fun `patch has correct header`() = runTest {
        val result = diff(listOf("a", "b"), listOf("a", "c"))
        val patch = ReportGenerator.unifiedDiffPatch(result, listOf("a", "b"), listOf("a", "c"), meta)
        patch shouldStartWith "--- a/config.prod.yaml"
        patch shouldContain "+++ b/config.local.yaml"
    }

    @Test
    fun `patch marks removed lines with minus`() = runTest {
        val left = listOf("a", "b", "c")
        val right = listOf("a", "c")
        val result = diff(left, right)
        val patch = ReportGenerator.unifiedDiffPatch(result, left, right, meta)
        patch shouldContain "-b"
    }

    @Test
    fun `patch marks added lines with plus`() = runTest {
        val left = listOf("a", "c")
        val right = listOf("a", "b", "c")
        val result = diff(left, right)
        val patch = ReportGenerator.unifiedDiffPatch(result, left, right, meta)
        patch shouldContain "+b"
    }

    @Test
    fun `patch has hunk header`() = runTest {
        val left = listOf("a", "b", "c")
        val right = listOf("a", "X", "c")
        val result = diff(left, right)
        val patch = ReportGenerator.unifiedDiffPatch(result, left, right, meta)
        patch shouldContain "@@"
    }

    @Test
    fun `patch context lines prefixed with space`() = runTest {
        val left = listOf("a", "b", "c", "d", "e")
        val right = listOf("a", "b", "X", "d", "e")
        val result = diff(left, right)
        val patch = ReportGenerator.unifiedDiffPatch(result, left, right, meta, contextLines = 1)
        patch shouldContain " b"
        patch shouldContain " d"
    }

    @Test
    fun `patch for identical files is empty`() = runTest {
        val lines = listOf("a", "b", "c")
        val result = diff(lines, lines)
        val patch = ReportGenerator.unifiedDiffPatch(result, lines, lines, meta)
        patch shouldContain "--- a/"
        // No @@ hunks
        (patch.contains("@@")) shouldBe false
    }

    // ── Plain text summary ──

    @Test
    fun `summary includes labels and stats`() = runTest {
        val left = listOf("a", "b", "c")
        val right = listOf("a", "X", "c")
        val result = diff(left, right)
        val summary = ReportGenerator.plainTextSummary(result, meta)
        summary shouldContain "config.prod.yaml"
        summary shouldContain "config.local.yaml"
        summary shouldContain "Hunks:"
        summary shouldContain "FULL_INDEX"
    }

    @Test
    fun `summary lists hunks`() = runTest {
        val left = listOf("a", "b", "c")
        val right = listOf("a", "X", "c")
        val result = diff(left, right)
        val summary = ReportGenerator.plainTextSummary(result, meta)
        summary shouldContain "[CHG]"
    }

    @Test
    fun `summary shows active rules when non-default`() = runTest {
        val rules = RuleSet(ignoreCase = true, headSkip = 5)
        val customMeta = meta.copy(rules = rules)
        val result = diff(listOf("a"), listOf("b"))
        val summary = ReportGenerator.plainTextSummary(result, customMeta)
        summary shouldContain "Ignore case"
        summary shouldContain "Head skip: 5"
    }

    // ── HTML ──

    @Test
    fun `html has doctype and structure`() = runTest {
        val result = diff(listOf("a"), listOf("b"))
        val html = ReportGenerator.htmlUnified(result, listOf("a"), listOf("b"), meta)
        html shouldStartWith "<!DOCTYPE html>"
        html shouldContain "<table>"
        html shouldContain "</html>"
    }

    @Test
    fun `html includes header info`() = runTest {
        val result = diff(listOf("a"), listOf("b"))
        val html = ReportGenerator.htmlUnified(result, listOf("a"), listOf("b"), meta)
        html shouldContain "config.prod.yaml"
        html shouldContain "config.local.yaml"
        html shouldContain "FULL_INDEX"
    }

    @Test
    fun `html has add and del rows`() = runTest {
        val left = listOf("a", "old", "c")
        val right = listOf("a", "new", "c")
        val result = diff(left, right)
        val html = ReportGenerator.htmlUnified(result, left, right, meta)
        html shouldContain "class=\"del\""
        html shouldContain "class=\"add\""
        html shouldContain "old"
        html shouldContain "new"
    }

    @Test
    fun `html escapes special characters`() = runTest {
        val left = listOf("<script>alert('xss')</script>")
        val right = listOf("<div>safe</div>")
        val result = diff(left, right)
        val html = ReportGenerator.htmlUnified(result, left, right, meta)
        (html.contains("<script>")) shouldBe false
        html shouldContain "&lt;script&gt;"
    }

    @Test
    fun `html for identical files has no colored rows`() = runTest {
        val lines = listOf("a", "b", "c")
        val result = diff(lines, lines)
        val html = ReportGenerator.htmlUnified(result, lines, lines, meta)
        (html.contains("class=\"del\"")) shouldBe false
        (html.contains("class=\"add\"")) shouldBe false
    }
}
