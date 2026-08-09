package com.omnieditor.core.io

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * R-06 / ADR-007: Line model consistency — lineCount = newlines + 1 everywhere.
 *
 * Verifies that [LineIndex], [PieceTable], and a reference `newlines + 1` function
 * all agree on line count for every edge case.
 */
class LineModelConsistencyTest {

    /**
     * Reference line count: count '\n' characters and add 1.
     * This is the canonical definition from D-7 / ADR-007.
     */
    private fun referenceLineCount(input: String): Int = input.count { it == '\n' } + 1

    @Test
    fun `lineCount is consistent across all implementations`() = runTest {
        val cases = listOf(
            "",           // 0 newlines → 1 line
            "a",          // 0 newlines → 1 line
            "a\n",        // 1 newline  → 2 lines
            "a\n\n",      // 2 newlines → 3 lines
            "\n",         // 1 newline  → 2 lines
            "a\r\nb",     // 1 newline  → 2 lines (CRLF counts as one terminator)
            "a\r\nb\r\n", // 2 newlines → 3 lines
        )

        for (input in cases) {
            val expected = referenceLineCount(input)

            val pieceTable = PieceTable.create(input)
            val ptCount = pieceTable.lineCount

            val lineIndex = LineIndex.build(input.toByteArray())
            val liCount = lineIndex.lineCount.toInt()

            ptCount shouldBe expected
            liCount shouldBe expected
        }
    }

    @Test
    fun `trailing newline produces an extra empty line`() = runTest {
        // "a" is 1 line; "a\n" is 2 lines (the trailing empty line is real)
        val withoutNewline = LineIndex.build("a".toByteArray())
        val withNewline = LineIndex.build("a\n".toByteArray())

        withoutNewline.lineCount shouldBe 1
        withNewline.lineCount shouldBe 2
        withNewline.length(1) shouldBe 0 // the trailing empty line
    }

    @Test
    fun `Kotlin String lines() matches the model`() {
        // Verify Kotlin's String.lines() matches newlines + 1 for our test cases.
        // String.lines() in Kotlin DOES include the trailing empty element.
        val cases = listOf(
            "" to 1,
            "a" to 1,
            "a\n" to 2,
            "a\n\n" to 3,
            "\n" to 2,
        )
        for ((input, expected) in cases) {
            val kotlinLines = input.lines().size
            kotlinLines shouldBe expected
        }
    }

    @Test
    fun `File readLines drops trailing empty line - known divergence`() {
        // java.io.BufferedReader.readLine() drops the trailing empty line.
        // This is a known divergence from the model. Code that reads files
        // for diffing must NOT use File.readLines() — it must use a splitter
        // that matches the model.
        val withTrailing = "a\nb\n"
        val readLinesCount = withTrailing.reader().readLines().size
        val modelCount = referenceLineCount(withTrailing)

        // readLines drops the trailing empty → 2 lines instead of 3
        readLinesCount shouldBe 2
        modelCount shouldBe 3
        // These are intentionally different — the model is authoritative
    }
}
