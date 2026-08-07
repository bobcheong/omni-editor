package com.omnieditor.core.diff

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Histogram diff algorithm (OE-ENG-1).
 *
 * Strategy: find lines that occur infrequently (ideally once) in both files
 * and use them as anchors to divide the problem into smaller regions.
 * Each sub-region is then diffed recursively, falling back to Myers for
 * small enough regions.
 *
 * This produces better results than plain Myers on code because it anchors
 * on unique lines (function signatures, class declarations) rather than
 * getting distracted by common lines (blank lines, braces).
 */
internal object HistogramDiff {

    /** Below this size, use Myers directly. */
    private const val MYERS_THRESHOLD = 100

    /** Maximum recursion depth before falling back to Myers. */
    private const val MAX_DEPTH = 64

    /**
     * Compute edits between two hash sequences using the histogram algorithm.
     */
    suspend fun diff(
        aHashes: LongArray, aStart: Int, aEnd: Int,
        bHashes: LongArray, bStart: Int, bEnd: Int,
        depth: Int = 0,
    ): List<Edit> {
        val n = aEnd - aStart
        val m = bEnd - bStart

        if (n == 0 && m == 0) return emptyList()
        if (n == 0) return listOf(Edit(EditType.INSERT, aStart, aStart, bStart, bEnd))
        if (m == 0) return listOf(Edit(EditType.DELETE, aStart, aEnd, bStart, bStart))

        // Trim common prefix
        var prefixLen = 0
        while (prefixLen < n && prefixLen < m &&
            aHashes[aStart + prefixLen] == bHashes[bStart + prefixLen]
        ) {
            prefixLen++
        }

        // Trim common suffix
        var suffixLen = 0
        while (suffixLen < n - prefixLen && suffixLen < m - prefixLen &&
            aHashes[aEnd - 1 - suffixLen] == bHashes[bEnd - 1 - suffixLen]
        ) {
            suffixLen++
        }

        val aLo = aStart + prefixLen
        val aHi = aEnd - suffixLen
        val bLo = bStart + prefixLen
        val bHi = bEnd - suffixLen

        if (aLo >= aHi && bLo >= bHi) return emptyList()
        if (aLo >= aHi) return listOf(Edit(EditType.INSERT, aLo, aLo, bLo, bHi))
        if (bLo >= bHi) return listOf(Edit(EditType.DELETE, aLo, aHi, bLo, bLo))

        // Fall back to Myers for small regions or deep recursion
        if ((aHi - aLo) + (bHi - bLo) <= MYERS_THRESHOLD || depth >= MAX_DEPTH) {
            return MyersDiff.diff(aHashes, aLo, aHi, bHashes, bLo, bHi)
        }

        // Cancellation check
        if ((aHi - aLo) > 4096) {
            coroutineContext.ensureActive()
        }

        // Build histogram of B's lines
        val histogram = buildHistogram(bHashes, bLo, bHi)

        // Find the best anchor: a line in A that occurs with lowest frequency in B (>0)
        var bestLine = -1
        var bestFreq = Int.MAX_VALUE
        var bestAIdx = -1
        var bestBIdx = -1

        for (i in aLo until aHi) {
            val hash = aHashes[i]
            val freq = histogram[hash] ?: continue
            if (freq.count < bestFreq) {
                bestFreq = freq.count
                bestLine = i
                bestAIdx = i
                bestBIdx = freq.firstIndex
                if (bestFreq == 1) break // Can't do better than unique
            }
        }

        // No common lines → whole region is a replacement
        if (bestLine == -1) {
            return listOf(Edit(EditType.REPLACE, aLo, aHi, bLo, bHi))
        }

        // Split at the anchor and recurse
        val edits = mutableListOf<Edit>()

        // Left half: before the anchor
        edits.addAll(diff(aHashes, aLo, bestAIdx, bHashes, bLo, bestBIdx, depth + 1))

        // Right half: after the anchor (skip the matching line)
        edits.addAll(diff(aHashes, bestAIdx + 1, aHi, bHashes, bestBIdx + 1, bHi, depth + 1))

        return edits
    }

    private data class HistEntry(val count: Int, val firstIndex: Int)

    private fun buildHistogram(hashes: LongArray, start: Int, end: Int): HashMap<Long, HistEntry> {
        val hist = HashMap<Long, HistEntry>(((end - start) * 1.5).toInt())
        for (i in start until end) {
            val hash = hashes[i]
            val existing = hist[hash]
            if (existing == null) {
                hist[hash] = HistEntry(1, i)
            } else {
                hist[hash] = HistEntry(existing.count + 1, existing.firstIndex)
            }
        }
        return hist
    }
}
