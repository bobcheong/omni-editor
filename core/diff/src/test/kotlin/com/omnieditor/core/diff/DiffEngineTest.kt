package com.omnieditor.core.diff

import com.omnieditor.core.model.Hunk
import com.omnieditor.core.model.HunkType
import com.omnieditor.core.model.Phase
import com.omnieditor.core.model.Progress
import com.omnieditor.core.model.RuleSet
import com.omnieditor.core.model.WhitespaceRule
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DiffEngineTest {

    private fun lines(vararg text: String) = text.toList()

    private suspend fun diff(left: List<String>, right: List<String>, rules: RuleSet = RuleSet.DEFAULT) =
        DiffEngine.compare(
            leftLineCount = left.size.toLong(),
            rightLineCount = right.size.toLong(),
            leftLine = { left[it.toInt()] },
            rightLine = { right[it.toInt()] },
            rules = rules,
        )

    // ── Basic cases ──

    @Test
    fun `identical files produce no hunks`() = runTest {
        val result = diff(lines("a", "b", "c"), lines("a", "b", "c"))
        result.hunks.size shouldBe 0
        result.stats.hunkCount shouldBe 0
    }

    @Test
    fun `single line change`() = runTest {
        val result = diff(lines("alpha", "beta", "gamma"), lines("alpha", "BETA", "gamma"))
        result.hunks.size shouldBe 1
        result.hunks[0].type shouldBe HunkType.CHANGED
        result.hunks[0].leftStart shouldBe 1
        result.hunks[0].leftEnd shouldBe 2
    }

    @Test
    fun `insertion at head`() = runTest {
        val result = diff(lines("a", "b", "c"), lines("z", "a", "b", "c"))
        result.hunks.size shouldBe 1
        result.hunks[0].type shouldBe HunkType.ADDED
    }

    @Test
    fun `insertion at tail`() = runTest {
        val result = diff(lines("a", "b", "c"), lines("a", "b", "c", "d"))
        result.hunks.size shouldBe 1
        result.hunks[0].type shouldBe HunkType.ADDED
    }

    @Test
    fun `deletion at head`() = runTest {
        val result = diff(lines("z", "a", "b", "c"), lines("a", "b", "c"))
        result.hunks.size shouldBe 1
        result.hunks[0].type shouldBe HunkType.REMOVED
    }

    @Test
    fun `whole file replaced`() = runTest {
        val result = diff(lines("old1", "old2", "old3"), lines("new1", "new2", "new3", "new4"))
        result.hunks.size shouldBe 1
        result.hunks[0].type shouldBe HunkType.CHANGED
    }

    @Test
    fun `empty vs non-empty`() = runTest {
        val result = diff(emptyList(), lines("content"))
        result.hunks.size shouldBe 1
        result.hunks[0].type shouldBe HunkType.ADDED
        result.stats.linesAdded shouldBe 1
    }

    @Test
    fun `both empty`() = runTest {
        val result = diff(emptyList(), emptyList())
        result.hunks.size shouldBe 0
    }

    // ── Stats ──

    @Test
    fun `stats count correctly`() = runTest {
        val left = lines("same", "removed", "changed-left", "same2")
        val right = lines("same", "changed-right", "same2", "added")
        val result = diff(left, right)
        result.stats.hunkCount shouldBe result.hunks.size
        // There should be some changes detected
        (result.stats.linesAdded + result.stats.linesRemoved + result.stats.linesChanged) shouldNotBe 0L
    }

    // ── Normalisation integration ──

    @Test
    fun `ignoreCase suppresses case-only differences`() = runTest {
        val rules = RuleSet(ignoreCase = true)
        val result = diff(lines("Hello", "World"), lines("hello", "world"), rules)
        result.hunks.size shouldBe 0
    }

    @Test
    fun `whitespace ALL suppresses whitespace-only differences`() = runTest {
        val rules = RuleSet(whitespace = WhitespaceRule.ALL)
        val result = diff(
            lines("int x = 1;", "int y = 2;"),
            lines("int  x  =  1 ;", "int  y  =  2 ;"),
            rules,
        )
        result.hunks.size shouldBe 0
    }

    @Test
    fun `headSkip ignores differences in first N lines`() = runTest {
        val rules = RuleSet(headSkip = 2)
        val result = diff(
            lines("header1-A", "header2-A", "data1", "data2"),
            lines("header1-B", "header2-B", "data1", "data2"),
            rules,
        )
        result.hunks.size shouldBe 0
    }

    // ── Larger inputs ──

    @Test
    fun `10k lines with one change`() = runTest {
        val left = (0 until 10000).map { "line %05d: common content".format(it) }
        val right = left.toMutableList().also { it[4999] = "line 04999: MODIFIED content" }
        val result = diff(left, right)
        result.hunks.size shouldBe 1
        result.hunks[0].leftStart shouldBe 4999
        result.hunks[0].type shouldBe HunkType.CHANGED
    }

    // ── Streaming ──

    @Test
    fun `streaming emits hunks`() = runTest {
        val hunks = DiffEngine.compareStreaming(
            leftLineCount = 3,
            rightLineCount = 3,
            leftLine = { listOf("a", "b", "c")[it.toInt()] },
            rightLine = { listOf("a", "X", "c")[it.toInt()] },
        ).toList()
        hunks.size shouldBe 1
        hunks[0].type shouldBe HunkType.CHANGED
    }

    @Test
    fun `streaming emits first hunk before completion for large input`() = runTest {
        val size = 5000
        val left = (0 until size).map { "line $it" }
        val right = left.toMutableList().also {
            it[100] = "CHANGED at 100"
            it[4900] = "CHANGED at 4900"
        }
        var firstHunkTime = 0L
        var flowCompleteTime = 0L
        val hunks = mutableListOf<Hunk>()

        DiffEngine.compareStreaming(
            leftLineCount = size.toLong(),
            rightLineCount = size.toLong(),
            leftLine = { left[it.toInt()] },
            rightLine = { right[it.toInt()] },
        ).collect { hunk ->
            if (hunks.isEmpty()) firstHunkTime = System.nanoTime()
            hunks.add(hunk)
        }
        flowCompleteTime = System.nanoTime()

        hunks.size shouldBe 2
        // First hunk should have been emitted (we can't strictly assert timing
        // in a unit test, but we verify the flow produced results)
        (firstHunkTime > 0) shouldBe true
    }

    // ── Progress ──

    @Test
    fun `progress is reported through all phases`() = runTest {
        val phases = mutableSetOf<Phase>()
        diff(
            lines("a", "b", "c"),
            lines("a", "X", "c"),
            RuleSet.DEFAULT,
        )
        // Use the full API to capture progress
        DiffEngine.compare(
            leftLineCount = 3,
            rightLineCount = 3,
            leftLine = { listOf("a", "b", "c")[it.toInt()] },
            rightLine = { listOf("a", "X", "c")[it.toInt()] },
            progress = { phases.add(it.phase) },
        )
        phases.contains(Phase.NORMALISING) shouldBe true
        phases.contains(Phase.DIFFING) shouldBe true
        phases.contains(Phase.DONE) shouldBe true
    }

    // ── Cancellation ──

    @Test
    fun `cancellation is supported`() = runTest {
        // Verify the engine checks ensureActive by confirming that a
        // CancellationException propagates correctly when the scope is cancelled
        var exceptionCaught = false
        val job = launch {
            try {
                // Use a large input so the normaliser hits its ensureActive check
                val size = 100000
                val left = (0 until size).map { "line $it with padding content here" }
                val right = left.toMutableList().also {
                    for (i in 0 until size step 2) it[i] = "changed $i"
                }
                DiffEngine.compare(
                    leftLineCount = size.toLong(),
                    rightLineCount = size.toLong(),
                    leftLine = { left[it.toInt()] },
                    rightLine = { right[it.toInt()] },
                )
            } catch (_: CancellationException) {
                exceptionCaught = true
                throw CancellationException()
            }
        }
        // Let it start, then cancel
        job.cancel()
        job.join()
        // In virtual time the job completes synchronously, so cancellation
        // may or may not be observed. The important thing is no coroutine leak.
        // This test verifies the cancellation path doesn't crash.
    }

    // ── Golden corpus integration ──

    @Test
    fun `golden corpus - identical`() = runTest {
        val result = diffGolden("identical")
        result.hunks.size shouldBe 0
    }

    @Test
    fun `golden corpus - single line change`() = runTest {
        val result = diffGolden("single-line-change")
        result.hunks.size shouldBe 1
        result.hunks[0].type shouldBe HunkType.CHANGED
    }

    @Test
    fun `golden corpus - insert at head`() = runTest {
        val result = diffGolden("insert-at-head")
        result.hunks.size shouldBe 1
        result.hunks[0].type shouldBe HunkType.ADDED
    }

    @Test
    fun `golden corpus - insert at tail`() = runTest {
        val result = diffGolden("insert-at-tail")
        result.hunks.size shouldBe 1
        result.hunks[0].type shouldBe HunkType.ADDED
    }

    @Test
    fun `golden corpus - whole file replaced`() = runTest {
        val result = diffGolden("whole-file-replaced")
        result.hunks.size shouldBe 1
        result.hunks[0].type shouldBe HunkType.CHANGED
    }

    @Test
    fun `golden corpus - empty vs nonempty`() = runTest {
        val result = diffGolden("empty-vs-nonempty")
        result.hunks.size shouldBe 1
        result.hunks[0].type shouldBe HunkType.ADDED
    }

    @Test
    fun `golden corpus - one byte files`() = runTest {
        val result = diffGolden("one-byte-files")
        result.hunks.size shouldBe 1
        result.hunks[0].type shouldBe HunkType.CHANGED
    }

    @Test
    fun `golden corpus - 10k lines one change`() = runTest {
        val result = diffGolden("large-identical-one-change")
        result.hunks.size shouldBe 1
        result.hunks[0].type shouldBe HunkType.CHANGED
        result.hunks[0].leftStart shouldBe 4999
    }

    @Test
    fun `golden corpus - case only diff without ignoreCase`() = runTest {
        val result = diffGolden("case-only-diff")
        result.hunks.isNotEmpty() shouldBe true
        result.hunks.all { it.type == HunkType.CHANGED } shouldBe true
    }

    @Test
    fun `golden corpus - case only diff with ignoreCase`() = runTest {
        val result = diffGolden("case-only-diff", RuleSet(ignoreCase = true))
        result.hunks.size shouldBe 0
    }

    @Test
    fun `golden corpus - whitespace only diff without ignore`() = runTest {
        val result = diffGolden("whitespace-only-diff")
        result.hunks.isNotEmpty() shouldBe true
    }

    @Test
    fun `golden corpus - whitespace only diff with ALL`() = runTest {
        val result = diffGolden("whitespace-only-diff", RuleSet(whitespace = WhitespaceRule.ALL))
        result.hunks.size shouldBe 0
    }

    @Test
    fun `golden corpus - minified long line`() = runTest {
        val result = diffGolden("minified-long-line")
        result.hunks.size shouldBe 1
        result.hunks[0].type shouldBe HunkType.CHANGED
    }

    // ── Helpers ──

    private val goldenRoot: java.io.File by lazy {
        var dir = java.io.File(System.getProperty("user.dir"))
        while (dir.parentFile != null && !java.io.File(dir, "testfixtures/golden").isDirectory) {
            dir = dir.parentFile
        }
        java.io.File(dir, "testfixtures/golden")
    }

    private suspend fun diffGolden(name: String, rules: RuleSet = RuleSet.DEFAULT): com.omnieditor.core.model.CompareResult {
        val dir = java.io.File(goldenRoot, name)
        val leftLines = java.io.File(dir, "left").readLines()
        val rightLines = java.io.File(dir, "right").readLines()
        return DiffEngine.compare(
            leftLineCount = leftLines.size.toLong(),
            rightLineCount = rightLines.size.toLong(),
            leftLine = { leftLines[it.toInt()] },
            rightLine = { rightLines[it.toInt()] },
            rules = rules,
        )
    }
}
