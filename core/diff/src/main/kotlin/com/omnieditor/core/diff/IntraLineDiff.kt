package com.omnieditor.core.diff

import com.omnieditor.core.model.Granularity
import com.omnieditor.core.model.HunkType
import com.omnieditor.core.model.IntraLineRange
import com.omnieditor.core.model.LinePair

/**
 * Computes intra-line character or word ranges for a changed line pair (OE-ENG-1).
 *
 * Called lazily per visible row — only the lines currently on screen get
 * intra-line computation. This is why it's a separate pass, not part of
 * the main diff.
 */
object IntraLineDiff {

    data class IntraLineResult(
        val leftRanges: List<IntraLineRange>,
        val rightRanges: List<IntraLineRange>,
    )

    /**
     * Compute intra-line diff ranges for a changed line pair.
     *
     * @param pair        the left and right line text
     * @param granularity CHARACTER or WORD level
     * @return ranges marking changed regions on each side
     */
    fun compute(pair: LinePair, granularity: Granularity): IntraLineResult {
        return when (granularity) {
            Granularity.CHARACTER -> computeCharacter(pair)
            Granularity.WORD -> computeWord(pair)
            Granularity.LINE -> IntraLineResult(
                leftRanges = listOf(IntraLineRange(0, pair.leftText.length, HunkType.CHANGED)),
                rightRanges = listOf(IntraLineRange(0, pair.rightText.length, HunkType.CHANGED)),
            )
        }
    }

    private fun computeCharacter(pair: LinePair): IntraLineResult {
        val left = pair.leftText.toString()
        val right = pair.rightText.toString()

        if (left == right) return IntraLineResult(emptyList(), emptyList())

        // Trim common prefix and suffix for efficiency
        var prefixLen = 0
        while (prefixLen < left.length && prefixLen < right.length && left[prefixLen] == right[prefixLen]) {
            prefixLen++
        }
        var suffixLen = 0
        while (suffixLen < left.length - prefixLen && suffixLen < right.length - prefixLen &&
            left[left.length - 1 - suffixLen] == right[right.length - 1 - suffixLen]
        ) {
            suffixLen++
        }

        val aLo = prefixLen
        val aHi = left.length - suffixLen
        val bLo = prefixLen
        val bHi = right.length - suffixLen

        if (aLo >= aHi && bLo >= bHi) return IntraLineResult(emptyList(), emptyList())

        val leftRanges = mutableListOf<IntraLineRange>()
        val rightRanges = mutableListOf<IntraLineRange>()

        if (aLo >= aHi) {
            // Pure insertion
            rightRanges.add(IntraLineRange(bLo, bHi, HunkType.ADDED))
        } else if (bLo >= bHi) {
            // Pure deletion
            leftRanges.add(IntraLineRange(aLo, aHi, HunkType.REMOVED))
        } else {
            // Changed region — use Myers on the inner part
            val aHashes = LongArray(aHi - aLo) { left[aLo + it].code.toLong() }
            val bHashes = LongArray(bHi - bLo) { right[bLo + it].code.toLong() }
            val edits = MyersDiff.diff(aHashes, 0, aHashes.size, bHashes, 0, bHashes.size)

            if (edits.isEmpty()) return IntraLineResult(emptyList(), emptyList())

            for (edit in edits) {
                when (edit.type) {
                    EditType.DELETE -> leftRanges.add(IntraLineRange(aLo + edit.aStart, aLo + edit.aEnd, HunkType.REMOVED))
                    EditType.INSERT -> rightRanges.add(IntraLineRange(bLo + edit.bStart, bLo + edit.bEnd, HunkType.ADDED))
                    EditType.REPLACE -> {
                        leftRanges.add(IntraLineRange(aLo + edit.aStart, aLo + edit.aEnd, HunkType.CHANGED))
                        rightRanges.add(IntraLineRange(bLo + edit.bStart, bLo + edit.bEnd, HunkType.CHANGED))
                    }
                }
            }
        }

        return IntraLineResult(leftRanges, rightRanges)
    }

    private fun computeWord(pair: LinePair): IntraLineResult {
        val left = pair.leftText.toString()
        val right = pair.rightText.toString()

        if (left == right) return IntraLineResult(emptyList(), emptyList())

        val leftTokens = tokenize(left)
        val rightTokens = tokenize(right)

        // Hash each token
        val aHashes = LongArray(leftTokens.size) { DiffEngine.hashString(leftTokens[it].text) }
        val bHashes = LongArray(rightTokens.size) { DiffEngine.hashString(rightTokens[it].text) }

        val edits = MyersDiff.diff(aHashes, 0, aHashes.size, bHashes, 0, bHashes.size)

        // Map token-level edits back to character ranges
        val leftRanges = mutableListOf<IntraLineRange>()
        val rightRanges = mutableListOf<IntraLineRange>()

        for (edit in edits) {
            when (edit.type) {
                EditType.DELETE -> {
                    val start = leftTokens[edit.aStart].start
                    val end = leftTokens[edit.aEnd - 1].end
                    leftRanges.add(IntraLineRange(start, end, HunkType.REMOVED))
                }
                EditType.INSERT -> {
                    val start = rightTokens[edit.bStart].start
                    val end = rightTokens[edit.bEnd - 1].end
                    rightRanges.add(IntraLineRange(start, end, HunkType.ADDED))
                }
                EditType.REPLACE -> {
                    if (edit.aEnd > edit.aStart) {
                        val start = leftTokens[edit.aStart].start
                        val end = leftTokens[edit.aEnd - 1].end
                        leftRanges.add(IntraLineRange(start, end, HunkType.CHANGED))
                    }
                    if (edit.bEnd > edit.bStart) {
                        val start = rightTokens[edit.bStart].start
                        val end = rightTokens[edit.bEnd - 1].end
                        rightRanges.add(IntraLineRange(start, end, HunkType.CHANGED))
                    }
                }
            }
        }

        return IntraLineResult(leftRanges, rightRanges)
    }

    internal data class Token(val text: String, val start: Int, val end: Int)

    /**
     * Tokenize a line into words and whitespace/punctuation runs.
     * Keeps whitespace and punctuation as separate tokens so they
     * can be diffed independently.
     */
    internal fun tokenize(line: String): List<Token> {
        if (line.isEmpty()) return emptyList()
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < line.length) {
            val start = i
            val ch = line[i]
            when {
                ch.isLetterOrDigit() || ch == '_' -> {
                    while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_')) i++
                }
                ch.isWhitespace() -> {
                    while (i < line.length && line[i].isWhitespace()) i++
                }
                else -> {
                    // Punctuation: each character is its own token
                    i++
                }
            }
            tokens.add(Token(line.substring(start, i), start, i))
        }
        return tokens
    }

    private fun editsToRanges(
        edits: List<Edit>,
        leftLen: Int,
        rightLen: Int,
    ): IntraLineResult {
        val leftRanges = mutableListOf<IntraLineRange>()
        val rightRanges = mutableListOf<IntraLineRange>()

        for (edit in edits) {
            when (edit.type) {
                EditType.DELETE -> {
                    leftRanges.add(IntraLineRange(edit.aStart, edit.aEnd, HunkType.REMOVED))
                }
                EditType.INSERT -> {
                    rightRanges.add(IntraLineRange(edit.bStart, edit.bEnd, HunkType.ADDED))
                }
                EditType.REPLACE -> {
                    leftRanges.add(IntraLineRange(edit.aStart, edit.aEnd, HunkType.CHANGED))
                    rightRanges.add(IntraLineRange(edit.bStart, edit.bEnd, HunkType.CHANGED))
                }
            }
        }

        return IntraLineResult(leftRanges, rightRanges)
    }
}
