package com.omnieditor.core.diff

import com.omnieditor.core.model.CompareResult
import com.omnieditor.core.model.CompareStats
import com.omnieditor.core.model.EngineMode
import com.omnieditor.core.model.Hunk
import com.omnieditor.core.model.HunkType
import com.omnieditor.core.model.Phase
import com.omnieditor.core.model.Progress
import com.omnieditor.core.model.RuleSet
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Block-mode diff for files above the threshold (OE-ENG-4).
 *
 * Strategy:
 * 1. Divide both files into fixed-size blocks (default 1024 lines)
 * 2. Compute a rolling hash per block
 * 3. Compare block hashes to find changed regions
 * 4. Run full line-level diff only within changed regions (plus context)
 * 5. Report EngineMode.BLOCK_MATCH so the UI can disclose it
 *
 * This keeps memory proportional to (file_size / block_size) + changed_region_size
 * rather than file_size, allowing 300MB+ files to be diffed within heap budget.
 */
object BlockDiff {

    /** Default block size in lines. */
    const val DEFAULT_BLOCK_SIZE = 1024

    /** Default threshold in lines above which block mode activates. */
    const val DEFAULT_LINE_THRESHOLD = 250_000L // ~32MB at ~128 bytes/line

    /** Context lines to include around each changed block for accurate line-level diff. */
    private const val CONTEXT_LINES = 128

    /**
     * Compare two large files using block-mode.
     */
    suspend fun compare(
        leftLineCount: Long,
        rightLineCount: Long,
        leftLine: (Long) -> CharSequence,
        rightLine: (Long) -> CharSequence,
        rules: RuleSet = RuleSet.DEFAULT,
        blockSize: Int = DEFAULT_BLOCK_SIZE,
        progress: ((Progress) -> Unit)? = null,
    ): CompareResult {
        val total = leftLineCount + rightLineCount
        progress?.invoke(Progress(0, total, Phase.INDEXING))

        // Phase 1: Compute block hashes
        val leftBlocks = computeBlockHashes(leftLineCount, leftLine, rules, blockSize)
        coroutineContext.ensureActive()
        progress?.invoke(Progress(leftLineCount, total, Phase.INDEXING))

        val rightBlocks = computeBlockHashes(rightLineCount, rightLine, rules, blockSize)
        coroutineContext.ensureActive()
        progress?.invoke(Progress(total, total, Phase.INDEXING))

        // Phase 2: Find changed block regions
        val changedRegions = findChangedRegions(
            leftBlocks, rightBlocks,
            leftLineCount, rightLineCount,
            blockSize,
        )

        progress?.invoke(Progress(0, changedRegions.size.toLong(), Phase.DIFFING))

        // Phase 3: Full diff within each changed region
        val allHunks = mutableListOf<Hunk>()
        for ((idx, region) in changedRegions.withIndex()) {
            coroutineContext.ensureActive()

            val regionResult = DiffEngine.compare(
                leftLineCount = region.leftEnd - region.leftStart,
                rightLineCount = region.rightEnd - region.rightStart,
                leftLine = { leftLine(region.leftStart + it) },
                rightLine = { rightLine(region.rightStart + it) },
                rules = rules,
            )

            // Offset hunks to global line numbers
            for (hunk in regionResult.hunks) {
                allHunks.add(
                    Hunk(
                        leftStart = hunk.leftStart + region.leftStart,
                        leftEnd = hunk.leftEnd + region.leftStart,
                        rightStart = hunk.rightStart + region.rightStart,
                        rightEnd = hunk.rightEnd + region.rightStart,
                        type = hunk.type,
                    )
                )
            }

            progress?.invoke(Progress((idx + 1).toLong(), changedRegions.size.toLong(), Phase.DIFFING))
        }

        progress?.invoke(Progress(total, total, Phase.DONE))

        val stats = computeStats(allHunks)
        return CompareResult(
            hunks = allHunks,
            stats = stats,
            engineMode = EngineMode.BLOCK_MATCH,
            generatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Compute a hash for each block of [blockSize] lines.
     */
    private suspend fun computeBlockHashes(
        lineCount: Long,
        lineReader: (Long) -> CharSequence,
        rules: RuleSet,
        blockSize: Int,
    ): List<Long> {
        val blockCount = ((lineCount + blockSize - 1) / blockSize).toInt()
        val hashes = ArrayList<Long>(blockCount)

        for (block in 0 until blockCount) {
            val start = block.toLong() * blockSize
            val end = minOf(start + blockSize, lineCount)

            var blockHash = -3750763034362895579L // FNV offset basis
            for (line in start until end) {
                val text = lineReader(line).toString()
                val norm = Normaliser.normaliseLine(text, line, lineCount, rules)
                val lineHash = DiffEngine.hashString(norm.text)
                // Combine line hash into block hash
                blockHash = blockHash xor lineHash
                blockHash *= 1099511628211L
            }
            hashes.add(blockHash)

            if (block % 64 == 0) {
                coroutineContext.ensureActive()
            }
        }

        return hashes
    }

    /**
     * A region of the file that needs full line-level diff.
     */
    data class ChangedRegion(
        val leftStart: Long,
        val leftEnd: Long,
        val rightStart: Long,
        val rightEnd: Long,
    )

    /**
     * Compare block hashes to find which regions changed.
     * Returns regions with context lines included.
     */
    private fun findChangedRegions(
        leftBlocks: List<Long>,
        rightBlocks: List<Long>,
        leftLineCount: Long,
        rightLineCount: Long,
        blockSize: Int,
    ): List<ChangedRegion> {
        val minBlocks = minOf(leftBlocks.size, rightBlocks.size)
        val maxBlocks = maxOf(leftBlocks.size, rightBlocks.size)

        // Find changed block indices
        val changedBlocks = mutableListOf<Int>()
        for (i in 0 until minBlocks) {
            if (leftBlocks[i] != rightBlocks[i]) {
                changedBlocks.add(i)
            }
        }
        // Blocks only in one side are also changed
        for (i in minBlocks until maxBlocks) {
            changedBlocks.add(i)
        }

        if (changedBlocks.isEmpty()) return emptyList()

        // Merge adjacent changed blocks with context into regions
        val regions = mutableListOf<ChangedRegion>()
        var regionStart = changedBlocks[0]
        var regionEnd = changedBlocks[0]

        for (i in 1 until changedBlocks.size) {
            val block = changedBlocks[i]
            // Merge if blocks are close enough (within context gap)
            if (block <= regionEnd + 2) { // 2-block gap tolerance
                regionEnd = block
            } else {
                regions.add(blockRangeToRegion(regionStart, regionEnd, blockSize, leftLineCount, rightLineCount))
                regionStart = block
                regionEnd = block
            }
        }
        regions.add(blockRangeToRegion(regionStart, regionEnd, blockSize, leftLineCount, rightLineCount))

        return regions
    }

    private fun blockRangeToRegion(
        startBlock: Int,
        endBlock: Int,
        blockSize: Int,
        leftLineCount: Long,
        rightLineCount: Long,
    ): ChangedRegion {
        val leftStart = maxOf(0L, startBlock.toLong() * blockSize - CONTEXT_LINES)
        val leftEnd = minOf(leftLineCount, (endBlock + 1).toLong() * blockSize + CONTEXT_LINES)
        val rightStart = maxOf(0L, startBlock.toLong() * blockSize - CONTEXT_LINES)
        val rightEnd = minOf(rightLineCount, (endBlock + 1).toLong() * blockSize + CONTEXT_LINES)
        return ChangedRegion(leftStart, leftEnd, rightStart, rightEnd)
    }

    private fun computeStats(hunks: List<Hunk>): CompareStats {
        var added = 0L
        var removed = 0L
        var changed = 0L
        for (h in hunks) {
            when (h.type) {
                HunkType.ADDED -> added += h.rightEnd - h.rightStart
                HunkType.REMOVED -> removed += h.leftEnd - h.leftStart
                HunkType.CHANGED -> changed += maxOf(h.leftEnd - h.leftStart, h.rightEnd - h.rightStart)
                HunkType.CONFLICT -> changed += maxOf(h.leftEnd - h.leftStart, h.rightEnd - h.rightStart)
            }
        }
        return CompareStats(
            linesAdded = added,
            linesRemoved = removed,
            linesChanged = changed,
            hunkCount = hunks.size,
        )
    }
}
