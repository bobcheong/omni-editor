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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.coroutineContext

/**
 * The diff engine (OE-ENG-1, OE-ENG-7).
 *
 * Compares two files by their line hashes, applying normalisation rules,
 * and produces a stream of hunks. Results are emitted as found (first hunk
 * before completion) for responsive UI.
 *
 * Pure Kotlin, no Android imports. Tested on the JVM against the golden corpus.
 */
object DiffEngine {

    /**
     * Compare two files represented as line readers and produce a [CompareResult].
     *
     * @param leftLineCount   number of lines in the left file
     * @param rightLineCount  number of lines in the right file
     * @param leftLine        reader for left file lines (0-based index)
     * @param rightLine       reader for right file lines (0-based index)
     * @param leftHash        raw hash for left file line (pre-normalisation, from LineIndex)
     * @param rightHash       raw hash for right file line
     * @param rules           normalisation rules to apply before comparison
     * @param progress        callback for progress reporting
     */
    suspend fun compare(
        leftLineCount: Long,
        rightLineCount: Long,
        leftLine: (Long) -> CharSequence,
        rightLine: (Long) -> CharSequence,
        leftHash: ((Long) -> Long)? = null,
        rightHash: ((Long) -> Long)? = null,
        rules: RuleSet = RuleSet.DEFAULT,
        progress: ((Progress) -> Unit)? = null,
    ): CompareResult {
        val total = leftLineCount + rightLineCount
        progress?.invoke(Progress(0, total, Phase.NORMALISING))

        // Use supplied hashes when available and rules don't change text content.
        // When rules normalise text (case folding, whitespace, patterns, markers,
        // column ranges, head/tail skip), we must hash the normalised text, so
        // pre-computed hashes are inapplicable. When rules are DEFAULT (no text
        // transformation), the caller's pre-computed hashes are correct and avoid
        // per-line string allocation and hash-space mixing between UTF-8 re-encode
        // hashes and LineIndex byte-level hashes.
        val rulesChangeText = rules != RuleSet.DEFAULT

        val useSuppliedHashes = !rulesChangeText && leftHash != null && rightHash != null
        val leftNorm = if (useSuppliedHashes) {
            buildNormalisedFile(leftLineCount, leftLine, leftHash!!)
        } else {
            Normaliser.normaliseHashes(leftLineCount, leftLine, ::hashString, rules)
        }
        val rightNorm = if (useSuppliedHashes) {
            buildNormalisedFile(rightLineCount, rightLine, rightHash!!)
        } else {
            Normaliser.normaliseHashes(rightLineCount, rightLine, ::hashString, rules)
        }

        progress?.invoke(Progress(total / 3, total, Phase.DIFFING))
        coroutineContext.ensureActive()

        // Run histogram diff
        val edits = HistogramDiff.diff(
            leftNorm.hashes, 0, leftNorm.hashes.size,
            rightNorm.hashes, 0, rightNorm.hashes.size,
        )

        progress?.invoke(Progress(total * 2 / 3, total, Phase.DIFFING))

        // Convert edits to hunks, merging adjacent edits
        val hunks = editsToHunks(edits, leftNorm, rightNorm, rules)

        progress?.invoke(Progress(total, total, Phase.DONE))

        val stats = computeStats(hunks)

        return CompareResult(
            hunks = hunks,
            stats = stats,
            engineMode = EngineMode.FULL_INDEX,
            generatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Streaming version: emits hunks as they are found.
     * The first hunk is emitted before the compare finishes.
     */
    fun compareStreaming(
        leftLineCount: Long,
        rightLineCount: Long,
        leftLine: (Long) -> CharSequence,
        rightLine: (Long) -> CharSequence,
        rules: RuleSet = RuleSet.DEFAULT,
        progress: ((Progress) -> Unit)? = null,
    ): Flow<Hunk> = flow {
        val total = leftLineCount + rightLineCount
        progress?.invoke(Progress(0, total, Phase.NORMALISING))

        val leftNorm = Normaliser.normaliseHashes(
            leftLineCount, leftLine, ::hashString, rules,
        )
        val rightNorm = Normaliser.normaliseHashes(
            rightLineCount, rightLine, ::hashString, rules,
        )

        progress?.invoke(Progress(total / 3, total, Phase.DIFFING))
        coroutineContext.ensureActive()

        val edits = HistogramDiff.diff(
            leftNorm.hashes, 0, leftNorm.hashes.size,
            rightNorm.hashes, 0, rightNorm.hashes.size,
        )

        // Emit hunks as they are produced
        val hunks = editsToHunks(edits, leftNorm, rightNorm, rules)
        for (hunk in hunks) {
            coroutineContext.ensureActive()
            emit(hunk)
        }

        progress?.invoke(Progress(total, total, Phase.DONE))
    }

    private fun editsToHunks(
        edits: List<Edit>,
        leftNorm: NormalisedFile,
        rightNorm: NormalisedFile,
        rules: RuleSet,
    ): List<Hunk> {
        if (edits.isEmpty()) return emptyList()

        val hunks = mutableListOf<Hunk>()

        for (edit in edits) {
            // Skip edits where both sides are blank and ignoreBlankLines is on
            if (rules.ignoreBlankLines) {
                val leftAllBlank = (edit.aStart until edit.aEnd).all { it < leftNorm.isBlank.size && leftNorm.isBlank[it] }
                val rightAllBlank = (edit.bStart until edit.bEnd).all { it < rightNorm.isBlank.size && rightNorm.isBlank[it] }
                if (leftAllBlank && rightAllBlank) continue
            }

            val type = when (edit.type) {
                EditType.INSERT -> HunkType.ADDED
                EditType.DELETE -> HunkType.REMOVED
                EditType.REPLACE -> {
                    if (edit.aEnd - edit.aStart == 0) HunkType.ADDED
                    else if (edit.bEnd - edit.bStart == 0) HunkType.REMOVED
                    else HunkType.CHANGED
                }
            }

            hunks.add(
                Hunk(
                    leftStart = edit.aStart.toLong(),
                    leftEnd = edit.aEnd.toLong(),
                    rightStart = edit.bStart.toLong(),
                    rightEnd = edit.bEnd.toLong(),
                    type = type,
                )
            )
        }

        return mergeAdjacentHunks(hunks)
    }

    /**
     * Merge adjacent hunks of the same type into single hunks.
     */
    private fun mergeAdjacentHunks(hunks: List<Hunk>): List<Hunk> {
        if (hunks.size <= 1) return hunks
        val merged = mutableListOf<Hunk>()
        var current = hunks[0]

        for (i in 1 until hunks.size) {
            val next = hunks[i]
            if (current.leftEnd == next.leftStart && current.rightEnd == next.rightStart) {
                // Adjacent — merge
                current = Hunk(
                    leftStart = current.leftStart,
                    leftEnd = next.leftEnd,
                    rightStart = current.rightStart,
                    rightEnd = next.rightEnd,
                    type = if (current.type == next.type) current.type else HunkType.CHANGED,
                )
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
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

    /**
     * Build a [NormalisedFile] directly from pre-computed per-line hashes.
     *
     * Used when normalisation rules don't change text content (i.e. rules are
     * DEFAULT). The caller's hash function already incorporates line-terminator
     * stripping, so we only need to check whether the raw line is blank.
     *
     * [R-08]
     */
    private fun buildNormalisedFile(
        lineCount: Long,
        lineReader: (Long) -> CharSequence,
        lineHashFn: (Long) -> Long,
    ): NormalisedFile {
        val hashes = LongArray(lineCount.toInt())
        val isBlank = BooleanArray(lineCount.toInt())
        for (i in 0 until lineCount.toInt()) {
            hashes[i] = lineHashFn(i.toLong())
            // A line is blank when its content (minus line terminators) is empty.
            isBlank[i] = lineReader(i.toLong()).trimEnd('\r', '\n').isEmpty()
        }
        return NormalisedFile(hashes, isBlank)
    }

    /**
     * FNV-1a hash for normalised line text, matching the LineIndex hash function.
     */
    internal fun hashString(s: String): Long {
        var hash = -3750763034362895579L // FNV offset basis
        for (b in s.toByteArray()) {
            hash = hash xor (b.toLong() and 0xFF)
            hash *= 1099511628211L
        }
        return hash
    }
}
