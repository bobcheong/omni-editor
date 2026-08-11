package com.omnieditor.feature.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnieditor.core.model.Granularity
import com.omnieditor.design.LocalCompareColors

/**
 * Unified diff view — phone default (OE-TXT-1).
 *
 * Single column, interleaved, colour-coded gutter markers (+ − ~).
 * Colour is never the only signal — glyph markers satisfy WCAG 1.4.1 (OE-APP-2).
 *
 * TalkBack announces side and change type for every row (NFR-A1).
 *
 * Renders from the shared List<AlignedRow> produced by CompareState.buildAlignedRows()
 * so that unified and split views are driven by the same alignment model (R-25).
 */
@Composable
fun UnifiedDiffView(
    state: CompareState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** Called with the hunk index when a diff row (CHANGED/ADDED/REMOVED) is tapped. */
    onDiffRowTapped: ((hunkIndex: Int) -> Unit)? = null,
) {
    val rows = remember(state.result, state.filterMode) {
        state.buildAlignedRows().filter { row ->
            when (state.filterMode) {
                FilterMode.ALL -> true
                FilterMode.DIFFS_ONLY -> row.type != RowType.CONTEXT
                FilterMode.MATCHES_ONLY -> row.type == RowType.CONTEXT
            }
        }
    }
    val listState = rememberLazyListState()
    val colors = LocalCompareColors.current
    val hunks = state.result.hunks
    val cache = state.intraLineCache
    val granularity = state.granularity

    // Scroll to current difference when navigating
    LaunchedEffect(state.currentDiffIndex) {
        @Suppress("UnusedVariable")
        val hunk = state.currentHunk ?: return@LaunchedEffect
        val targetRow = rows.indexOfFirst { it.hunkIndex == state.currentDiffIndex }
        if (targetRow >= 0) {
            listState.animateScrollToItem(targetRow)
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize(),
    ) {
        itemsIndexed(rows, key = { idx, row -> "${row.left}_${row.right}_${row.type}_$idx" }) { _, row ->
            UnifiedAlignedRow(
                row = row,
                leftLines = state.leftLines,
                rightLines = state.rightLines,
                isCurrentHunk = row.hunkIndex != null && row.hunkIndex == state.currentDiffIndex,
                colors = colors,
                hunks = hunks,
                intraLineCache = cache,
                granularity = granularity,
                onTap = if (onDiffRowTapped != null && row.type != RowType.CONTEXT && row.hunkIndex != null) {
                    { onDiffRowTapped(row.hunkIndex) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun UnifiedAlignedRow(
    row: AlignedRow,
    leftLines: List<String>,
    rightLines: List<String>,
    isCurrentHunk: Boolean,
    colors: com.omnieditor.design.CompareColors,
    hunks: List<com.omnieditor.core.model.Hunk>,
    intraLineCache: IntraLineCache,
    granularity: Granularity,
    onTap: (() -> Unit)? = null,
) {
    val (bgColor, fgColor, glyph) = when (row.type) {
        RowType.ADDED -> Triple(colors.addedBg, colors.addedFg, "+")
        RowType.REMOVED -> Triple(colors.removedBg, colors.removedFg, "−")
        RowType.CHANGED_OLD -> Triple(colors.changedBg, colors.changedFg, "~")
        RowType.CHANGED_NEW -> Triple(colors.changedBg, colors.changedFg, "~")
        RowType.CONTEXT -> Triple(Color.Transparent, colors.gutter, " ")
    }

    // For display: prefer left line for context/removed; right line for added/changed-new.
    val displayLineNum = row.left ?: row.right ?: 0L
    val rawText = when {
        row.left != null -> leftLines.getOrElse(row.left.toInt()) { "" }
        row.right != null -> rightLines.getOrElse(row.right.toInt()) { "" }
        else -> ""
    }

    // Compute intra-line highlight for CHANGED rows (R-26).
    val displayContent: AnnotatedString = remember(row, rawText, granularity) {
        when (row.type) {
            RowType.CHANGED_OLD -> {
                val leftIdx = row.left ?: return@remember AnnotatedString(rawText)
                val hunk = row.hunkIndex?.let { hunks.getOrNull(it) }
                    ?: return@remember AnnotatedString(rawText)
                val offset = leftIdx - hunk.leftStart
                val rightIdx = hunk.rightStart + offset
                if (rightIdx >= hunk.rightEnd) return@remember AnnotatedString(rawText)
                val rightText = rightLines.getOrElse(rightIdx.toInt()) { "" }
                val result = intraLineCache.get(rawText, rightText, leftIdx, rightIdx, granularity)
                highlightIntraLine(rawText, result.leftRanges, colors.intraLineOldBg)
            }
            RowType.CHANGED_NEW -> {
                val rightIdx = row.right ?: return@remember AnnotatedString(rawText)
                val hunk = row.hunkIndex?.let { hunks.getOrNull(it) }
                    ?: return@remember AnnotatedString(rawText)
                val offset = rightIdx - hunk.rightStart
                val leftIdx = hunk.leftStart + offset
                if (leftIdx >= hunk.leftEnd) return@remember AnnotatedString(rawText)
                val leftText = leftLines.getOrElse(leftIdx.toInt()) { "" }
                val result = intraLineCache.get(leftText, rawText, leftIdx, rightIdx, granularity)
                highlightIntraLine(rawText, result.rightRanges, colors.intraLineNewBg)
            }
            else -> AnnotatedString(rawText)
        }
    }
    // TalkBack content description (NFR-A1) uses raw text without span markup.
    val a11yDescription = buildA11yDescription(row, displayLineNum, rawText)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isCurrentHunk) bgColor.copy(alpha = 0.7f) else bgColor)
            .height(22.dp)
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .semantics { contentDescription = a11yDescription },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Gutter: glyph marker (OE-APP-2: colour is never the only signal)
        Box(
            modifier = Modifier.width(20.dp).padding(start = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = glyph,
                color = fgColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            )
        }

        // Line number
        Text(
            text = (displayLineNum + 1).toString(),
            color = colors.gutter,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.width(48.dp).padding(end = 4.dp),
            maxLines = 1,
        )

        // Content with horizontal scroll — uses AnnotatedString for intra-line spans (R-26).
        Text(
            text = displayContent,
            color = if (row.type == RowType.CONTEXT) {
                MaterialTheme.colorScheme.onSurface
            } else {
                fgColor
            },
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
        )
    }
}

/**
 * Build TalkBack-accessible description for an aligned diff row.
 * Announces side and change type for every row (NFR-A1).
 */
private fun buildA11yDescription(row: AlignedRow, lineNum: Long, text: String): String {
    val num = lineNum + 1
    return when (row.type) {
        RowType.ADDED -> "Added on right, line $num: $text"
        RowType.REMOVED -> "Removed from left, line $num: $text"
        RowType.CHANGED_OLD -> "Changed on left, line $num: $text"
        RowType.CHANGED_NEW -> "Changed on right, line $num: $text"
        RowType.CONTEXT -> "Unchanged, line $num"
    }
}
