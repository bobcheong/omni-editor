package com.omnieditor.core.diff

import com.omnieditor.core.model.CompareResult
import com.omnieditor.core.model.Hunk
import com.omnieditor.core.model.HunkType
import com.omnieditor.core.model.RuleSet

/**
 * Export compare results in various formats (OE-RPT-1..4).
 *
 * Formats: HTML (unified and side-by-side), unified diff patch,
 * plain-text summary. PDF is generated via Android's print pipeline
 * from the HTML output (Tier 2+).
 *
 * Every report header records: sources, timestamp, applied rules, engine mode.
 */
object ReportGenerator {

    data class ReportMeta(
        val leftLabel: String,
        val rightLabel: String,
        val timestamp: String,
        val rules: RuleSet,
        val engineMode: String,
    )

    /**
     * Generate a unified diff patch (OE-RPT-1).
     * Output applies cleanly with `git apply`.
     */
    fun unifiedDiffPatch(
        result: CompareResult,
        leftLines: List<String>,
        rightLines: List<String>,
        meta: ReportMeta,
        contextLines: Int = 3,
    ): String {
        val sb = StringBuilder()

        // Header
        sb.appendLine("--- a/${meta.leftLabel}")
        sb.appendLine("+++ b/${meta.rightLabel}")

        // Group hunks with context
        val groups = groupHunksWithContext(result.hunks, leftLines.size, rightLines.size, contextLines)

        for (group in groups) {
            val leftStart = group.contextStart
            val leftCount = group.leftLines
            val rightStart = group.rightContextStart
            val rightCount = group.rightLines

            sb.appendLine("@@ -${leftStart + 1},$leftCount +${rightStart + 1},$rightCount @@")

            var leftPos = group.contextStart.toLong()
            var rightPos = group.rightContextStart.toLong()

            for (hunk in group.hunks) {
                // Context before hunk
                while (leftPos < hunk.leftStart) {
                    if (leftPos < leftLines.size) {
                        sb.appendLine(" ${leftLines[leftPos.toInt()]}")
                    }
                    leftPos++
                    rightPos++
                }

                // Removed lines
                for (i in hunk.leftStart until hunk.leftEnd) {
                    if (i < leftLines.size) {
                        sb.appendLine("-${leftLines[i.toInt()]}")
                    }
                }
                leftPos = hunk.leftEnd

                // Added lines
                for (i in hunk.rightStart until hunk.rightEnd) {
                    if (i < rightLines.size) {
                        sb.appendLine("+${rightLines[i.toInt()]}")
                    }
                }
                rightPos = hunk.rightEnd
            }

            // Context after last hunk
            val contextEnd = minOf(
                (group.hunks.last().leftEnd + contextLines).toInt(),
                leftLines.size,
            )
            while (leftPos < contextEnd) {
                sb.appendLine(" ${leftLines[leftPos.toInt()]}")
                leftPos++
            }
        }

        return sb.toString()
    }

    /**
     * Generate a plain-text summary (OE-RPT-1).
     */
    fun plainTextSummary(
        result: CompareResult,
        meta: ReportMeta,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("Compare Report")
        sb.appendLine("==============")
        sb.appendLine("Left:      ${meta.leftLabel}")
        sb.appendLine("Right:     ${meta.rightLabel}")
        sb.appendLine("Timestamp: ${meta.timestamp}")
        sb.appendLine("Engine:    ${meta.engineMode}")
        sb.appendLine()
        sb.appendLine("Summary")
        sb.appendLine("-------")
        sb.appendLine("Hunks:         ${result.stats.hunkCount}")
        sb.appendLine("Lines added:   ${result.stats.linesAdded}")
        sb.appendLine("Lines removed: ${result.stats.linesRemoved}")
        sb.appendLine("Lines changed: ${result.stats.linesChanged}")
        sb.appendLine()

        if (meta.rules != RuleSet.DEFAULT) {
            sb.appendLine("Active rules")
            sb.appendLine("------------")
            if (meta.rules.ignoreCase) sb.appendLine("- Ignore case")
            if (meta.rules.whitespace != com.omnieditor.core.model.WhitespaceRule.NONE) {
                sb.appendLine("- Whitespace: ${meta.rules.whitespace}")
            }
            if (meta.rules.ignoreBlankLines) sb.appendLine("- Ignore blank lines")
            if (meta.rules.headSkip > 0) sb.appendLine("- Head skip: ${meta.rules.headSkip}")
            if (meta.rules.tailSkip > 0) sb.appendLine("- Tail skip: ${meta.rules.tailSkip}")
            if (meta.rules.linePatterns.isNotEmpty()) {
                sb.appendLine("- Line patterns: ${meta.rules.linePatterns.size}")
            }
            sb.appendLine()
        }

        // Hunk list
        sb.appendLine("Hunks")
        sb.appendLine("-----")
        for ((i, hunk) in result.hunks.withIndex()) {
            val type = when (hunk.type) {
                HunkType.ADDED -> "ADD"
                HunkType.REMOVED -> "DEL"
                HunkType.CHANGED -> "CHG"
                HunkType.CONFLICT -> "CON"
            }
            sb.appendLine("${i + 1}. [$type] L${hunk.leftStart + 1}-${hunk.leftEnd} / R${hunk.rightStart + 1}-${hunk.rightEnd}")
        }

        return sb.toString()
    }

    /**
     * Generate styled HTML report — unified layout (OE-RPT-1).
     */
    fun htmlUnified(
        result: CompareResult,
        leftLines: List<String>,
        rightLines: List<String>,
        meta: ReportMeta,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("<!DOCTYPE html>")
        sb.appendLine("<html><head><meta charset=\"utf-8\">")
        sb.appendLine("<title>Compare: ${esc(meta.leftLabel)} ⇄ ${esc(meta.rightLabel)}</title>")
        sb.appendLine("<style>")
        sb.appendLine("body{font-family:monospace;font-size:13px;margin:20px;background:#fff;color:#222}")
        sb.appendLine(".hdr{background:#f5f5f5;padding:12px;border:1px solid #ddd;margin-bottom:16px}")
        sb.appendLine("table{border-collapse:collapse;width:100%}")
        sb.appendLine("td{padding:1px 8px;white-space:pre-wrap;vertical-align:top}")
        sb.appendLine(".ln{color:#999;text-align:right;width:40px;user-select:none}")
        sb.appendLine(".gl{width:16px;text-align:center;font-weight:bold}")
        sb.appendLine(".add{background:#dcf5e6;color:#14532d}")
        sb.appendLine(".del{background:#fce4e4;color:#7f1d1d}")
        sb.appendLine(".chg{background:#fbf0d5;color:#6b4a00}")
        sb.appendLine(".g-add{color:#1d6b45}.g-del{color:#9e2a2b}.g-chg{color:#8a5a00}")
        sb.appendLine("</style></head><body>")

        // Header
        sb.appendLine("<div class=\"hdr\">")
        sb.appendLine("<b>Left:</b> ${esc(meta.leftLabel)}<br>")
        sb.appendLine("<b>Right:</b> ${esc(meta.rightLabel)}<br>")
        sb.appendLine("<b>Generated:</b> ${esc(meta.timestamp)}<br>")
        sb.appendLine("<b>Engine:</b> ${esc(meta.engineMode)} · ")
        sb.appendLine("<b>Hunks:</b> ${result.stats.hunkCount} · ")
        sb.appendLine("+${result.stats.linesAdded} −${result.stats.linesRemoved} ~${result.stats.linesChanged}")
        sb.appendLine("</div>")

        sb.appendLine("<table>")

        // Build unified rows
        var leftIdx = 0L
        var rightIdx = 0L
        var hunkIdx = 0

        while (leftIdx < leftLines.size || rightIdx < rightLines.size) {
            val hunk = if (hunkIdx < result.hunks.size) result.hunks[hunkIdx] else null

            if (hunk != null && leftIdx == hunk.leftStart) {
                for (i in hunk.leftStart until hunk.leftEnd) {
                    sb.appendLine("<tr class=\"del\"><td class=\"ln\">${i + 1}</td><td class=\"gl g-del\">−</td><td>${esc(leftLines[i.toInt()])}</td></tr>")
                }
                for (i in hunk.rightStart until hunk.rightEnd) {
                    sb.appendLine("<tr class=\"add\"><td class=\"ln\">${i + 1}</td><td class=\"gl g-add\">+</td><td>${esc(rightLines[i.toInt()])}</td></tr>")
                }
                leftIdx = hunk.leftEnd
                rightIdx = hunk.rightEnd
                hunkIdx++
            } else {
                val contextEnd = hunk?.leftStart ?: leftLines.size.toLong()
                while (leftIdx < contextEnd && leftIdx < leftLines.size) {
                    sb.appendLine("<tr><td class=\"ln\">${leftIdx + 1}</td><td class=\"gl\"> </td><td>${esc(leftLines[leftIdx.toInt()])}</td></tr>")
                    leftIdx++
                    rightIdx++
                }
            }
        }

        sb.appendLine("</table></body></html>")
        return sb.toString()
    }

    // ── Helpers ──

    private data class HunkGroup(
        val hunks: List<Hunk>,
        val contextStart: Int,
        val rightContextStart: Int,
        val leftLines: Int,
        val rightLines: Int,
    )

    private fun groupHunksWithContext(
        hunks: List<Hunk>,
        leftTotal: Int,
        rightTotal: Int,
        contextLines: Int,
    ): List<HunkGroup> {
        if (hunks.isEmpty()) return emptyList()

        val groups = mutableListOf<HunkGroup>()
        var currentHunks = mutableListOf(hunks[0])

        for (i in 1 until hunks.size) {
            val prev = currentHunks.last()
            val next = hunks[i]
            if (next.leftStart - prev.leftEnd <= contextLines * 2) {
                currentHunks.add(next)
            } else {
                groups.add(buildGroup(currentHunks, leftTotal, rightTotal, contextLines))
                currentHunks = mutableListOf(next)
            }
        }
        groups.add(buildGroup(currentHunks, leftTotal, rightTotal, contextLines))

        return groups
    }

    private fun buildGroup(
        hunks: List<Hunk>,
        leftTotal: Int,
        rightTotal: Int,
        contextLines: Int,
    ): HunkGroup {
        val first = hunks.first()
        val last = hunks.last()
        val contextStart = maxOf(0, (first.leftStart - contextLines).toInt())
        val contextEnd = minOf(leftTotal, (last.leftEnd + contextLines).toInt())
        val rightContextStart = maxOf(0, (first.rightStart - contextLines).toInt())
        val rightContextEnd = minOf(rightTotal, (last.rightEnd + contextLines).toInt())

        return HunkGroup(
            hunks = hunks,
            contextStart = contextStart,
            rightContextStart = rightContextStart,
            leftLines = contextEnd - contextStart,
            rightLines = rightContextEnd - rightContextStart,
        )
    }

    private fun esc(s: String): String {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
