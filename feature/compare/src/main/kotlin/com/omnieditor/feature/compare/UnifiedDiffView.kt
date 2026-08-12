package com.omnieditor.feature.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnieditor.core.model.Granularity
import com.omnieditor.design.HorizontalScrollController
import com.omnieditor.design.LocalCompareColors
import com.omnieditor.design.horizontalDocumentScroll
import com.omnieditor.design.rememberHorizontalScrollController

/** Combined width of the glyph gutter (20dp) and line-number column (48dp). */
private val UNIFIED_GUTTER_WIDTH = 68.dp

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
 *
 * Horizontal scrolling (R-44) uses one [HorizontalScrollController] for the
 * whole view: rows translate together as a single document page. Per-row
 * `horizontalScroll(sharedState)` is expressly avoided — a shared ScrollState
 * across many rows has its maxValue clobbered by whichever row measured last,
 * snapping the horizontal position back to the left edge during vertical
 * scrolling and making long lines unreachable.
 */
@Composable
fun UnifiedDiffView(
    state: CompareState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** Called with the hunk index when a diff row (CHANGED/ADDED/REMOVED) is tapped. */
    onDiffRowTapped: ((hunkIndex: Int) -> Unit)? = null,
    /** Row indices (in the AlignedRow list) that match the current find query. */
    findMatches: List<Int> = emptyList(),
    /** Index within [findMatches] that is currently focused; -1 to highlight nothing. */
    findMatchIndex: Int = -1,
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
    val density = LocalDensity.current

    // ── Shared horizontal document scroll (R-44) ─────────────────────────
    val hScroll = rememberHorizontalScrollController()
    val textMeasurer = rememberTextMeasurer()
    val contentStyle = remember { TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
    val charWidthPx = remember(textMeasurer, contentStyle) {
        textMeasurer.measure(AnnotatedString("0"), style = contentStyle).size.width.toFloat()
    }
    // Widest display line across both sides of the visible row set. Tabs are
    // approximated at 4 columns; monospace makes the rest exact.
    val maxDisplayCols = remember(rows, state.leftLines, state.rightLines) {
        var m = 0
        for (row in rows) {
            val text = when {
                row.left != null -> state.leftLines.getOrElse(row.left.toInt()) { "" }
                row.right != null -> state.rightLines.getOrElse(row.right.toInt()) { "" }
                else -> ""
            }
            val len = text.length + text.count { it == '\t' } * 3
            if (len > m) m = len
        }
        m
    }
    var viewportWidthPx by remember { mutableStateOf(0f) }
    LaunchedEffect(maxDisplayCols, viewportWidthPx, charWidthPx) {
        val gutterPx = with(density) { UNIFIED_GUTTER_WIDTH.toPx() }
        hScroll.updateBounds(
            contentWidthPx = maxDisplayCols * charWidthPx + charWidthPx,
            viewportWidthPx = (viewportWidthPx - gutterPx).coerceAtLeast(0f),
        )
    }

    // The focused find match row index (in the filtered rows list).
    val focusedFindRow = remember(findMatches, findMatchIndex) {
        if (findMatchIndex >= 0 && findMatchIndex < findMatches.size) findMatches[findMatchIndex] else -1
    }
    // Set of all find-match rows for highlight.
    val findMatchSet = remember(findMatches) { findMatches.toHashSet() }

    // Scroll to current difference when navigating
    LaunchedEffect(state.currentDiffIndex) {
        @Suppress("UnusedVariable")
        val hunk = state.currentHunk ?: return@LaunchedEffect
        val targetRow = rows.indexOfFirst { it.hunkIndex == state.currentDiffIndex }
        if (targetRow >= 0) {
            listState.animateScrollToItem(targetRow)
        }
    }

    // Scroll to focused find match
    LaunchedEffect(focusedFindRow) {
        if (focusedFindRow >= 0) {
            listState.animateScrollToItem(focusedFindRow)
        }
    }

    // Update first visible line and visible line count in CompareState for minimap (R-31)
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }.collect { info ->
            val first = info.visibleItemsInfo.firstOrNull()?.index?.toLong() ?: 0L
            state.firstVisibleLine = first
            val visible = info.visibleItemsInfo.size
            if (visible > 0) state.visibleLineCount = visible
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { viewportWidthPx = it.size.width.toFloat() }
            // Horizontal panning shares the container with vertical list
            // scrolling; axis disambiguation routes each gesture (R-44).
            .horizontalDocumentScroll(hScroll),
    ) {
        itemsIndexed(rows, key = { idx, row -> "${row.left}_${row.right}_${row.type}_$idx" }) { idx, row ->
            UnifiedAlignedRow(
                row = row,
                leftLines = state.leftLines,
                rightLines = state.rightLines,
                isCurrentHunk = row.hunkIndex != null && row.hunkIndex == state.currentDiffIndex,
                isFindMatch = idx in findMatchSet,
                isFocusedMatch = idx == focusedFindRow,
                colors = colors,
                hunks = hunks,
                intraLineCache = cache,
                granularity = granularity,
                hScroll = hScroll,
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
    isFindMatch: Boolean = false,
    isFocusedMatch: Boolean = false,
    colors: com.omnieditor.design.CompareColors,
    hunks: List<com.omnieditor.core.model.Hunk>,
    intraLineCache: IntraLineCache,
    granularity: Granularity,
    hScroll: HorizontalScrollController,
    onTap: (() -> Unit)? = null,
) {
    val (bgColor, fgColor, glyph) = when (row.type) {
        RowType.ADDED -> Triple(colors.addedBg, colors.addedFg, "+")
        RowType.REMOVED -> Triple(colors.removedBg, colors.removedFg, "−")
        RowType.CHANGED_OLD -> Triple(colors.changedBg, colors.changedFg, "~")
        RowType.CHANGED_NEW -> Triple(colors.changedBg, colors.changedFg, "~")
        RowType.CONFLICT_OLD -> Triple(colors.conflictBg, colors.conflictFg, "!")
        RowType.CONFLICT_NEW -> Triple(colors.conflictBg, colors.conflictFg, "!")
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

    val rowBg = when {
        isFocusedMatch -> Color(0xFFFFD54F).copy(alpha = 0.7f) // amber highlight for focused find match
        isFindMatch -> Color(0xFFFFEE58).copy(alpha = 0.4f)    // lighter for other matches
        isCurrentHunk -> bgColor.copy(alpha = 0.7f)
        else -> bgColor
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .height(22.dp)
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .semantics { contentDescription = a11yDescription },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Gutter: glyph marker (OE-APP-2: colour is never the only signal) — pinned.
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

        // Line number — pinned.
        Text(
            text = (displayLineNum + 1).toString(),
            color = colors.gutter,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.width(48.dp).padding(end = 4.dp),
            maxLines = 1,
        )

        // Content — lays out at full intrinsic width, clipped to the content
        // area, translated by the single shared document offset (R-44).
        Box(
            modifier = Modifier
                .weight(1f)
                .clipToBounds(),
        ) {
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
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .graphicsLayer { translationX = -hScroll.offsetPx },
            )
        }
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
        RowType.CONFLICT_OLD -> "Conflict on left, line $num: $text"
        RowType.CONFLICT_NEW -> "Conflict on right, line $num: $text"
        RowType.CONTEXT -> "Unchanged, line $num"
    }
}
