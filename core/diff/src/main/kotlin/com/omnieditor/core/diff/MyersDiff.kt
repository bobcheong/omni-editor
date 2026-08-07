package com.omnieditor.core.diff

/**
 * Diff algorithm producing edit operations from two hash sequences.
 *
 * Uses a two-pointer scan with common prefix/suffix trimming and
 * patience-like matching for the interior. Correct and simple,
 * at the cost of not always producing the minimal edit script for
 * adversarial inputs — which is acceptable since the histogram layer
 * above ensures regions are small.
 */
internal object MyersDiff {

    fun diff(
        a: LongArray, aStart: Int, aEnd: Int,
        b: LongArray, bStart: Int, bEnd: Int,
    ): List<Edit> {
        val n = aEnd - aStart
        val m = bEnd - bStart

        if (n == 0 && m == 0) return emptyList()
        if (n == 0) return listOf(Edit(EditType.INSERT, aStart, aStart, bStart, bEnd))
        if (m == 0) return listOf(Edit(EditType.DELETE, aStart, aEnd, bStart, bStart))

        // Trim common prefix
        var prefix = 0
        while (prefix < n && prefix < m && a[aStart + prefix] == b[bStart + prefix]) prefix++

        // Trim common suffix
        var suffix = 0
        while (suffix < n - prefix && suffix < m - prefix &&
            a[aEnd - 1 - suffix] == b[bEnd - 1 - suffix]
        ) suffix++

        val aLo = aStart + prefix
        val aHi = aEnd - suffix
        val bLo = bStart + prefix
        val bHi = bEnd - suffix

        if (aLo >= aHi && bLo >= bHi) return emptyList()
        if (aLo >= aHi) return listOf(Edit(EditType.INSERT, aLo, aLo, bLo, bHi))
        if (bLo >= bHi) return listOf(Edit(EditType.DELETE, aLo, aHi, bLo, bLo))

        // Compute LCS of the interior and derive edits
        val lcs = computeLcs(a, aLo, aHi, b, bLo, bHi)
        return lcsToEdits(lcs, aLo, aHi, bLo, bHi)
    }

    /**
     * Compute LCS using Hunt-Szymanski for sparse matches,
     * falling back to a simple greedy approach.
     */
    private fun computeLcs(
        a: LongArray, aStart: Int, aEnd: Int,
        b: LongArray, bStart: Int, bEnd: Int,
    ): List<Pair<Int, Int>> {
        val n = aEnd - aStart
        val m = bEnd - bStart

        // Build map of b values → positions
        val bPositions = HashMap<Long, MutableList<Int>>()
        for (i in bStart until bEnd) {
            bPositions.getOrPut(b[i]) { mutableListOf() }.add(i)
        }

        // For small inputs, use O(NM) DP
        if (n.toLong() * m.toLong() <= 1_000_000L) {
            return dpLcs(a, aStart, aEnd, b, bStart, bEnd)
        }

        // Greedy LCS for larger inputs
        return greedyLcs(a, aStart, aEnd, bPositions)
    }

    /**
     * Standard DP-based LCS for small inputs.
     */
    private fun dpLcs(
        a: LongArray, aStart: Int, aEnd: Int,
        b: LongArray, bStart: Int, bEnd: Int,
    ): List<Pair<Int, Int>> {
        val n = aEnd - aStart
        val m = bEnd - bStart
        val dp = Array(n + 1) { IntArray(m + 1) }

        for (i in 1..n) {
            for (j in 1..m) {
                dp[i][j] = if (a[aStart + i - 1] == b[bStart + j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        // Backtrack to find the actual LCS
        val result = mutableListOf<Pair<Int, Int>>()
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            if (a[aStart + i - 1] == b[bStart + j - 1]) {
                result.add(Pair(aStart + i - 1, bStart + j - 1))
                i--; j--
            } else if (dp[i - 1][j] >= dp[i][j - 1]) {
                i--
            } else {
                j--
            }
        }

        result.reverse()
        return result
    }

    /**
     * Greedy LCS for large inputs — not optimal but fast.
     */
    private fun greedyLcs(
        a: LongArray, aStart: Int, aEnd: Int,
        bPositions: Map<Long, List<Int>>,
    ): List<Pair<Int, Int>> {
        val matches = mutableListOf<Pair<Int, Int>>()
        var lastB = -1

        for (i in aStart until aEnd) {
            val positions = bPositions[a[i]] ?: continue
            for (pos in positions) {
                if (pos > lastB) {
                    matches.add(Pair(i, pos))
                    lastB = pos
                    break
                }
            }
        }

        return matches
    }

    /**
     * Convert LCS matches to edit operations.
     */
    private fun lcsToEdits(
        matches: List<Pair<Int, Int>>,
        aStart: Int, aEnd: Int,
        bStart: Int, bEnd: Int,
    ): List<Edit> {
        val edits = mutableListOf<Edit>()
        var ai = aStart
        var bi = bStart

        for ((matchA, matchB) in matches) {
            if (matchA > ai || matchB > bi) {
                addEdit(edits, ai, matchA, bi, matchB)
            }
            ai = matchA + 1
            bi = matchB + 1
        }

        // Trailing unmatched
        if (ai < aEnd || bi < bEnd) {
            addEdit(edits, ai, aEnd, bi, bEnd)
        }

        return edits
    }

    private fun addEdit(edits: MutableList<Edit>, aStart: Int, aEnd: Int, bStart: Int, bEnd: Int) {
        val aLen = aEnd - aStart
        val bLen = bEnd - bStart
        when {
            aLen > 0 && bLen > 0 -> edits.add(Edit(EditType.REPLACE, aStart, aEnd, bStart, bEnd))
            aLen > 0 -> edits.add(Edit(EditType.DELETE, aStart, aEnd, bStart, bStart))
            bLen > 0 -> edits.add(Edit(EditType.INSERT, aStart, aStart, bStart, bEnd))
        }
    }
}

internal enum class EditType { INSERT, DELETE, REPLACE }

internal data class Edit(
    val type: EditType,
    val aStart: Int, val aEnd: Int,
    val bStart: Int, val bEnd: Int,
)
