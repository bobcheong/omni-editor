package com.omnieditor.core.diff

/**
 * Myers' O(ND) diff algorithm producing edit operations.
 *
 * Used as the fallback for small regions and when histogram diff
 * cannot find good anchors.
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

        // Find the LCS using Myers, then derive edits from the matched pairs
        val lcs = myersLcs(a, aStart, aEnd, b, bStart, bEnd)
        return lcsToEdits(lcs, aStart, aEnd, bStart, bEnd)
    }

    /**
     * Standard Myers shortest-edit-script algorithm.
     * Returns a list of matched (aIndex, bIndex) pairs (the LCS).
     */
    private fun myersLcs(
        a: LongArray, aStart: Int, aEnd: Int,
        b: LongArray, bStart: Int, bEnd: Int,
    ): List<Pair<Int, Int>> {
        val n = aEnd - aStart
        val m = bEnd - bStart
        val max = n + m

        if (max > 200_000) {
            // Too large for O((N+M)^2) — use greedy LCS
            return greedyLcs(a, aStart, aEnd, b, bStart, bEnd)
        }

        val off = max + 1
        val v = IntArray(2 * off)
        val trace = mutableListOf<IntArray>()

        v[off + 1] = 0

        for (d in 0..max) {
            trace.add(v.copyOf())
            for (k in -d..d step 2) {
                var x = if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) {
                    v[off + k + 1]
                } else {
                    v[off + k - 1] + 1
                }
                var y = x - k
                while (x < n && y < m && a[aStart + x] == b[bStart + y]) {
                    x++; y++
                }
                v[off + k] = x
                if (x >= n && y >= m) {
                    return backtrack(trace, off, n, m, a, aStart, b, bStart)
                }
            }
        }
        return emptyList()
    }

    private fun backtrack(
        trace: List<IntArray>, off: Int,
        n: Int, m: Int,
        a: LongArray, aStart: Int,
        b: LongArray, bStart: Int,
    ): List<Pair<Int, Int>> {
        var x = n
        var y = m
        val matches = mutableListOf<Pair<Int, Int>>()

        for (d in trace.size - 1 downTo 1) {
            val v = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) {
                k + 1
            } else {
                k - 1
            }
            val prevX = v[off + prevK]
            val prevY = prevX - prevK

            // Diagonal moves = matches
            while (x > prevX && y > prevY) {
                x--; y--
                matches.add(Pair(aStart + x, bStart + y))
            }
            x = prevX
            y = prevY
        }

        matches.reverse()
        return matches
    }

    private fun greedyLcs(
        a: LongArray, aStart: Int, aEnd: Int,
        b: LongArray, bStart: Int, bEnd: Int,
    ): List<Pair<Int, Int>> {
        val bMap = HashMap<Long, MutableList<Int>>()
        for (i in bStart until bEnd) {
            bMap.getOrPut(b[i]) { mutableListOf() }.add(i)
        }
        val matches = mutableListOf<Pair<Int, Int>>()
        var lastB = bStart - 1
        for (i in aStart until aEnd) {
            val positions = bMap[a[i]] ?: continue
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
