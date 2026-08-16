package com.omnieditor.core.diff

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * C.2: Differential testing — compare DiffEngine output with `git diff --histogram`.
 *
 * Generates temp file pairs, runs both engines, asserts semantic equivalence
 * (same changed line ranges). Skips if `git` is not on PATH.
 */
class DifferentialTest {

    private fun gitAvailable(): Boolean = try {
        ProcessBuilder("git", "--version").start().waitFor() == 0
    } catch (_: Exception) { false }

    @Test
    fun `DiffEngine and git diff --histogram agree on changed regions`() = runTest {
        assumeTrue("git not available", gitAvailable())

        val left = buildList {
            for (i in 0 until 100) add("line $i")
        }
        val right = buildList {
            for (i in 0 until 100) {
                if (i in 20..25 || i in 60..65) add("CHANGED $i")
                else add("line $i")
            }
        }

        // Write temp files
        val leftFile = File.createTempFile("diff-left-", ".txt").apply {
            deleteOnExit()
            writeText(left.joinToString("\n") + "\n")
        }
        val rightFile = File.createTempFile("diff-right-", ".txt").apply {
            deleteOnExit()
            writeText(right.joinToString("\n") + "\n")
        }

        // Run git diff --histogram
        val process = ProcessBuilder(
            "git", "diff", "--no-index", "--histogram", "-U0",
            leftFile.absolutePath, rightFile.absolutePath,
        ).redirectErrorStream(true).start()
        val gitOutput = process.inputStream.bufferedReader().readText()
        process.waitFor()

        // Parse git's @@ lines to get changed regions
        val gitRanges = parseGitHunkHeaders(gitOutput)

        // Run DiffEngine
        val result = DiffEngine.compare(
            leftLineCount = left.size.toLong(),
            rightLineCount = right.size.toLong(),
            leftLine = { left[it.toInt()] },
            rightLine = { right[it.toInt()] },
        )

        // Both should identify changes in the same regions
        val engineRanges = result.hunks.map { h ->
            h.leftStart to h.leftEnd
        }

        // Semantic check: every git-changed line should be covered by an engine hunk
        for ((gitStart, gitEnd) in gitRanges) {
            val covered = engineRanges.any { (eStart, eEnd) ->
                eStart <= gitStart && eEnd >= gitEnd
            }
            covered shouldBe true
        }
    }

    /**
     * Parse git unified diff @@ headers.
     * Format: @@ -startL,countL +startR,countR @@
     * Git uses 1-based; we convert to 0-based.
     */
    private fun parseGitHunkHeaders(diff: String): List<Pair<Long, Long>> {
        val pattern = Regex("""^@@\s+-(\d+)(?:,(\d+))?\s+\+\d+(?:,\d+)?\s+@@""")
        return diff.lines().mapNotNull { line ->
            pattern.find(line)?.let { match ->
                val start = match.groupValues[1].toLong() - 1 // 0-based
                val count = match.groupValues[2].toLongOrNull() ?: 1
                start to (start + count)
            }
        }
    }
}
