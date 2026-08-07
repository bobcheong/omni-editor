package com.omnieditor.core.diff

import com.omnieditor.core.model.EngineMode
import com.omnieditor.core.model.HunkType
import com.omnieditor.core.model.Phase
import com.omnieditor.core.model.Progress
import com.omnieditor.core.model.RuleSet
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

class BlockDiffTest {

    // ── Basic block diff ──

    @Test
    fun `identical large files produce no hunks`() = runTest {
        val size = 5000
        val lines = (0 until size).map { "line %05d: common content".format(it) }
        val result = BlockDiff.compare(
            leftLineCount = size.toLong(),
            rightLineCount = size.toLong(),
            leftLine = { lines[it.toInt()] },
            rightLine = { lines[it.toInt()] },
            blockSize = 100,
        )
        result.hunks.size shouldBe 0
        result.engineMode shouldBe EngineMode.BLOCK_MATCH
    }

    @Test
    fun `single change in large file found by block mode`() = runTest {
        val size = 5000
        val left = (0 until size).map { "line %05d: common content".format(it) }
        val right = left.toMutableList().also { it[2500] = "line 02500: MODIFIED content" }
        val result = BlockDiff.compare(
            leftLineCount = size.toLong(),
            rightLineCount = size.toLong(),
            leftLine = { left[it.toInt()] },
            rightLine = { right[it.toInt()] },
            blockSize = 100,
        )
        result.hunks.size shouldBe 1
        result.hunks[0].type shouldBe HunkType.CHANGED
        result.hunks[0].leftStart shouldBe 2500
        result.engineMode shouldBe EngineMode.BLOCK_MATCH
    }

    @Test
    fun `multiple scattered changes found`() = runTest {
        val size = 10000
        val left = (0 until size).map { "line %05d: common content".format(it) }
        val changePositions = listOf(500, 2000, 5000, 8000)
        val right = left.toMutableList().also { list ->
            for (pos in changePositions) {
                list[pos] = "line %05d: CHANGED".format(pos)
            }
        }
        val result = BlockDiff.compare(
            leftLineCount = size.toLong(),
            rightLineCount = size.toLong(),
            leftLine = { left[it.toInt()] },
            rightLine = { right[it.toInt()] },
            blockSize = 100,
        )
        result.hunks.size shouldBe changePositions.size
        result.hunks.all { it.type == HunkType.CHANGED } shouldBe true
        result.engineMode shouldBe EngineMode.BLOCK_MATCH
    }

    @Test
    fun `reports BLOCK_MATCH engine mode`() = runTest {
        val size = 1000
        val lines = (0 until size).map { "line $it" }
        val result = BlockDiff.compare(
            leftLineCount = size.toLong(),
            rightLineCount = size.toLong(),
            leftLine = { lines[it.toInt()] },
            rightLine = { lines[it.toInt()] },
            blockSize = 100,
        )
        result.engineMode shouldBe EngineMode.BLOCK_MATCH
    }

    @Test
    fun `reports progress through phases`() = runTest {
        val size = 2000
        val left = (0 until size).map { "line $it" }
        val right = left.toMutableList().also { it[1000] = "changed" }
        val phases = mutableSetOf<Phase>()
        BlockDiff.compare(
            leftLineCount = size.toLong(),
            rightLineCount = size.toLong(),
            leftLine = { left[it.toInt()] },
            rightLine = { right[it.toInt()] },
            blockSize = 100,
            progress = { phases.add(it.phase) },
        )
        phases.contains(Phase.INDEXING) shouldBe true
        phases.contains(Phase.DIFFING) shouldBe true
        phases.contains(Phase.DONE) shouldBe true
    }

    @Test
    fun `stats are computed correctly`() = runTest {
        val size = 3000
        val left = (0 until size).map { "line %05d".format(it) }
        val right = left.toMutableList().also {
            it[1000] = "changed line"
            it[2000] = "another change"
        }
        val result = BlockDiff.compare(
            leftLineCount = size.toLong(),
            rightLineCount = size.toLong(),
            leftLine = { left[it.toInt()] },
            rightLine = { right[it.toInt()] },
            blockSize = 100,
        )
        result.stats.hunkCount shouldBe result.hunks.size
        result.stats.linesChanged shouldNotBe 0L
    }

    @Test
    fun `normalisation rules are applied in block mode`() = runTest {
        val size = 2000
        val left = (0 until size).map { "Line %05d".format(it) }
        val right = (0 until size).map { "line %05d".format(it) } // case difference only
        val result = BlockDiff.compare(
            leftLineCount = size.toLong(),
            rightLineCount = size.toLong(),
            leftLine = { left[it.toInt()] },
            rightLine = { right[it.toInt()] },
            rules = RuleSet(ignoreCase = true),
            blockSize = 100,
        )
        result.hunks.size shouldBe 0
    }

    @Test
    fun `different length files handled`() = runTest {
        val left = (0 until 3000).map { "line $it" }
        val right = (0 until 3500).map { "line $it" }
        val result = BlockDiff.compare(
            leftLineCount = left.size.toLong(),
            rightLineCount = right.size.toLong(),
            leftLine = { left[it.toInt()] },
            rightLine = { right[it.toInt()] },
            blockSize = 100,
        )
        result.hunks.isNotEmpty() shouldBe true
        result.stats.linesAdded shouldNotBe 0L
    }

    // ── 300MB fixture (local-only) ──

    @Test
    fun `300MB fixture finds all 12 changes if available`() = runTest {
        val goldenRoot = findGoldenRoot() ?: return@runTest
        val largeDir = File(goldenRoot, "generated/large-log")
        if (!largeDir.exists()) {
            println("SKIPPED: 300MB fixture not generated. Run tools/generate-large-fixture.sh")
            return@runTest
        }

        val leftFile = File(largeDir, "left")
        val rightFile = File(largeDir, "right")
        if (!leftFile.exists() || !rightFile.exists()) return@runTest

        val leftLines = leftFile.readLines()
        val rightLines = rightFile.readLines()

        println("Diffing ${leftLines.size} lines with block mode...")
        val start = System.currentTimeMillis()

        val result = BlockDiff.compare(
            leftLineCount = leftLines.size.toLong(),
            rightLineCount = rightLines.size.toLong(),
            leftLine = { leftLines[it.toInt()] },
            rightLine = { rightLines[it.toInt()] },
            blockSize = BlockDiff.DEFAULT_BLOCK_SIZE,
        )

        val elapsed = System.currentTimeMillis() - start
        println("Block diff completed in ${elapsed}ms, found ${result.hunks.size} hunks")

        result.hunks.size shouldBe 12
        result.hunks.all { it.type == HunkType.CHANGED } shouldBe true
        result.engineMode shouldBe EngineMode.BLOCK_MATCH
    }

    private fun findGoldenRoot(): File? {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null && !File(dir, "testfixtures/golden").isDirectory) {
            dir = dir.parentFile
        }
        val golden = File(dir, "testfixtures/golden")
        return if (golden.isDirectory) golden else null
    }
}
