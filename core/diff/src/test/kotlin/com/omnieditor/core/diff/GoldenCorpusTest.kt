package com.omnieditor.core.diff

import com.omnieditor.core.model.HunkType
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/**
 * T-02: Golden corpus test harness.
 *
 * Loads file pairs from testfixtures/golden/ and their expected hunk sets.
 * Each test case is a directory containing:
 *   - left:          the left file (any encoding)
 *   - right:         the right file (any encoding)
 *   - expected.json: the expected hunks as JSON
 *
 * Until the diff engine exists (T-07), these tests verify only that the corpus
 * loads correctly and the expected files parse. Once DiffEngine is implemented,
 * each test will call the engine and assert expected == actual.
 */
class GoldenCorpusTest {

    @Serializable
    data class ExpectedHunk(
        val leftStart: Long,
        val leftEnd: Long,
        val rightStart: Long,
        val rightEnd: Long,
        val type: String,
    )

    @Serializable
    data class ExpectedResult(val hunks: List<ExpectedHunk> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    private val goldenRoot: File by lazy {
        // Walk up from the test class's location to find the repo root
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null && !File(dir, "testfixtures/golden").isDirectory) {
            dir = dir.parentFile
        }
        File(dir, "testfixtures/golden").also {
            require(it.isDirectory) { "Golden corpus not found. Run tools/generate-golden-corpus.sh first." }
        }
    }

    private fun loadCase(name: String): Triple<File, File, ExpectedResult> {
        val dir = File(goldenRoot, name)
        require(dir.isDirectory) { "Golden case $name not found at ${dir.absolutePath}" }
        val left = File(dir, "left")
        val right = File(dir, "right")
        val expectedFile = File(dir, "expected.json")
        require(left.exists()) { "Missing left file in $name" }
        require(right.exists()) { "Missing right file in $name" }
        require(expectedFile.exists()) { "Missing expected.json in $name" }
        val expected = json.decodeFromString<ExpectedResult>(expectedFile.readText())
        return Triple(left, right, expected)
    }

    // ── Corpus loading tests (T-02 acceptance: corpus loads in a test harness) ──

    @Test
    fun `corpus - identical files have no expected hunks`() {
        val (_, _, expected) = loadCase("identical")
        expected.hunks.size shouldBe 0
    }

    @Test
    fun `corpus - single line change has one CHANGED hunk`() {
        val (_, _, expected) = loadCase("single-line-change")
        expected.hunks.size shouldBe 1
        expected.hunks[0].type shouldBe "CHANGED"
    }

    @Test
    fun `corpus - insert at head has one ADDED hunk`() {
        val (_, _, expected) = loadCase("insert-at-head")
        expected.hunks.size shouldBe 1
        expected.hunks[0].type shouldBe "ADDED"
        expected.hunks[0].rightStart shouldBe 0
        expected.hunks[0].rightEnd shouldBe 1
    }

    @Test
    fun `corpus - insert at tail has one ADDED hunk`() {
        val (_, _, expected) = loadCase("insert-at-tail")
        expected.hunks.size shouldBe 1
        expected.hunks[0].type shouldBe "ADDED"
    }

    @Test
    fun `corpus - whole file replaced has one CHANGED hunk`() {
        val (_, _, expected) = loadCase("whole-file-replaced")
        expected.hunks.size shouldBe 1
        expected.hunks[0].type shouldBe "CHANGED"
    }

    @Test
    fun `corpus - CRLF vs LF has no hunks with default rules`() {
        val (_, _, expected) = loadCase("crlf-vs-lf")
        expected.hunks.size shouldBe 0
    }

    @Test
    fun `corpus - UTF-8 vs UTF-16LE BOM has no hunks after decode`() {
        val (_, _, expected) = loadCase("utf8-vs-utf16le-bom")
        expected.hunks.size shouldBe 0
    }

    @Test
    fun `corpus - no trailing newline has one ADDED hunk under line model`() {
        // R-06 / D-7: "line one\nline two" is 2 lines, "line one\nline two\n" is 3 lines.
        // The trailing empty line is real, so it appears as an addition.
        val (_, _, expected) = loadCase("no-trailing-newline")
        expected.hunks.size shouldBe 1
        expected.hunks[0].type shouldBe "ADDED"
    }

    @Test
    fun `corpus - empty vs non-empty has one ADDED hunk`() {
        val (_, _, expected) = loadCase("empty-vs-nonempty")
        expected.hunks.size shouldBe 1
        expected.hunks[0].type shouldBe "ADDED"
    }

    @Test
    fun `corpus - one byte files has one CHANGED hunk`() {
        val (_, _, expected) = loadCase("one-byte-files")
        expected.hunks.size shouldBe 1
        expected.hunks[0].type shouldBe "CHANGED"
    }

    @Test
    fun `corpus - 10k lines with one change has one CHANGED hunk`() {
        val (left, right, expected) = loadCase("large-identical-one-change")
        expected.hunks.size shouldBe 1
        expected.hunks[0].leftStart shouldBe 4999
        expected.hunks[0].type shouldBe "CHANGED"
        // Verify files actually have 10000 lines
        left.readLines().size shouldBe 10000
        right.readLines().size shouldBe 10000
    }

    @Test
    fun `corpus - reordered blocks has expected hunks`() {
        val (_, _, expected) = loadCase("reordered-blocks")
        expected.hunks.size shouldBe 4
    }

    @Test
    fun `corpus - whitespace only diff has CHANGED hunks`() {
        val (_, _, expected) = loadCase("whitespace-only-diff")
        expected.hunks.size shouldBe 3
        expected.hunks.all { it.type == "CHANGED" } shouldBe true
    }

    @Test
    fun `corpus - case only diff has CHANGED hunks`() {
        val (_, _, expected) = loadCase("case-only-diff")
        expected.hunks.size shouldBe 2
        expected.hunks.all { it.type == "CHANGED" } shouldBe true
    }

    @Test
    fun `corpus - minified long line has one CHANGED hunk`() {
        val (left, right, expected) = loadCase("minified-long-line")
        expected.hunks.size shouldBe 1
        expected.hunks[0].type shouldBe "CHANGED"
        // Verify the line is actually ~400KB
        val leftLine = left.readLines().first()
        (leftLine.length >= 390_000) shouldBe true
    }

    @Test
    fun `all corpus cases load without error`() {
        val cases = goldenRoot.listFiles()?.filter { it.isDirectory && it.name != "generated" } ?: emptyList()
        cases.size shouldBe 15
        for (dir in cases) {
            val (left, right, expected) = loadCase(dir.name)
            // Just verify they load — actual diff comparison comes in T-07
        }
    }
}
