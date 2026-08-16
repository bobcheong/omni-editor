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

    // ── Side-by-side report ──

    /**
     * Scope of the side-by-side report: entire file, a selection, or the visible viewport.
     * ALL: include every line. SELECTION: include only lines within [scopeRange].
     * VISIBLE: treated the same as ALL at report-generation time (viewport is ephemeral).
     */
    enum class ReportScope { ALL, SELECTION, VISIBLE }

    /**
     * Generate a side-by-side HTML report (OE-RPT-2).
     *
     * Each line of the left and right file occupies adjacent table cells.
     * Changed, added, and removed regions are colour-coded.
     * When [scope] is [ReportScope.SELECTION] only lines within [scopeRange] (0-based,
     * inclusive) are emitted.
     */
    fun htmlSideBySide(
        result: CompareResult,
        leftLines: List<String>,
        rightLines: List<String>,
        meta: ReportMeta,
        scope: ReportScope = ReportScope.ALL,
        scopeRange: LongRange? = null,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("<!DOCTYPE html><html><head><meta charset='utf-8'>")
        sb.appendLine("<style>")
        sb.appendLine("body{font-family:monospace;font-size:13px;margin:0;padding:16px}")
        sb.appendLine("table{border-collapse:collapse;width:100%}")
        sb.appendLine("td{padding:2px 8px;vertical-align:top;white-space:pre-wrap;border:1px solid #ddd}")
        sb.appendLine(".ln{color:#999;text-align:right;width:40px;user-select:none}")
        sb.appendLine(".add{background:#e6ffec}.del{background:#ffebe9}.chg{background:#fff3cd}")
        sb.appendLine("h2{font-family:sans-serif;font-size:14px;margin:8px 0}")
        sb.appendLine("</style></head><body>")

        // Header
        sb.appendLine("<h2>${esc(meta.leftLabel)} &#x21D4; ${esc(meta.rightLabel)}</h2>")
        sb.appendLine("<p>Generated: ${esc(meta.timestamp)} &middot; Engine: ${esc(meta.engineMode)} &middot; Rules: ${esc(meta.rules.toString())}</p>")

        sb.appendLine("<table>")
        sb.appendLine("<tr><th></th><th>${esc(meta.leftLabel)}</th><th></th><th>${esc(meta.rightLabel)}</th></tr>")

        val maxLine = maxOf(leftLines.size, rightLines.size)

        // Determine which lines to render
        val lineRange: IntRange = if (scope == ReportScope.SELECTION && scopeRange != null) {
            scopeRange.first.toInt()..minOf(scopeRange.last.toInt(), maxLine - 1)
        } else {
            0 until maxLine
        }

        // Filter hunks that overlap the rendered range
        val hunksInScope: List<Hunk> = if (scope == ReportScope.SELECTION && scopeRange != null) {
            result.hunks.filter {
                it.leftStart < scopeRange.last + 1 && it.leftEnd > scopeRange.first
            }
        } else {
            result.hunks
        }

        var leftIdx = lineRange.first
        var rightIdx = lineRange.first

        for (hunk in hunksInScope) {
            // Context lines before this hunk
            while (leftIdx < hunk.leftStart.toInt() && leftIdx <= lineRange.last) {
                val lText = leftLines.getOrElse(leftIdx) { "" }
                val rText = rightLines.getOrElse(rightIdx) { "" }
                sb.appendLine(
                    "<tr><td class='ln'>${leftIdx + 1}</td><td>${esc(lText)}</td>" +
                        "<td class='ln'>${rightIdx + 1}</td><td>${esc(rText)}</td></tr>"
                )
                leftIdx++
                rightIdx++
            }

            // Hunk rows
            val leftEnd = hunk.leftEnd.toInt()
            val rightEnd = hunk.rightEnd.toInt()
            val hunkLeft = (hunk.leftStart.toInt() until leftEnd).map { leftLines.getOrElse(it) { "" } }
            val hunkRight = (hunk.rightStart.toInt() until rightEnd).map { rightLines.getOrElse(it) { "" } }
            val maxHunk = maxOf(hunkLeft.size, hunkRight.size)
            val css = when (hunk.type) {
                HunkType.ADDED -> "add"
                HunkType.REMOVED -> "del"
                else -> "chg"
            }
            for (i in 0 until maxHunk) {
                val lText = hunkLeft.getOrNull(i)
                val rText = hunkRight.getOrNull(i)
                val lNum = if (lText != null) "${hunk.leftStart.toInt() + i + 1}" else ""
                val rNum = if (rText != null) "${hunk.rightStart.toInt() + i + 1}" else ""
                sb.appendLine(
                    "<tr><td class='ln'>$lNum</td><td class='$css'>${esc(lText ?: "")}</td>" +
                        "<td class='ln'>$rNum</td><td class='$css'>${esc(rText ?: "")}</td></tr>"
                )
            }
            leftIdx = leftEnd
            rightIdx = rightEnd
        }

        // Trailing context after all hunks
        while (leftIdx <= lineRange.last && leftIdx < leftLines.size) {
            val lText = leftLines.getOrElse(leftIdx) { "" }
            val rText = rightLines.getOrElse(rightIdx) { "" }
            sb.appendLine(
                "<tr><td class='ln'>${leftIdx + 1}</td><td>${esc(lText)}</td>" +
                    "<td class='ln'>${rightIdx + 1}</td><td>${esc(rText)}</td></tr>"
            )
            leftIdx++
            rightIdx++
        }

        sb.appendLine("</table>")
        sb.appendLine("<footer><p>Rules: ${esc(meta.rules.toString())} &middot; Engine: ${esc(meta.engineMode)}</p></footer>")
        sb.appendLine("</body></html>")
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
