package com.omnieditor.feature.compare

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.geometry.Offset
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
import kotlinx.coroutines.launch

/** Combined width of the pane gutter: glyph column (16dp) + line numbers (36dp). */
private val SPLIT_GUTTER_WIDTH = 52.dp

/**
 * Split (side-by-side) diff view — default at ≥600dp or landscape (OE-TXT-1).
 *
 * Two synchronised panes with relational connector lines between matched
 * blocks. Scrolling is synchronised by default (toggleable via OE-TXT-6).
 *
 * Both panes render from the same List<AlignedRow> (R-25). Sync holds by construction:
 * both lists have identical length; spacer rows (null on one side) keep matched context
 * lines on the same visual row after any insertion or deletion.
 *
 * Horizontal scrolling (R-45): each pane shows only a narrow strip of text on
 * a phone, so lines pan horizontally via one shared
 * [HorizontalScrollController] — both panes translate together, and content is
 * never silently truncated (the previous implementation ellipsized at the
 * pane edge with no way to see the rest). Bounds cover the widest line on
 * either side. Vertical sync is bidirectional (R-46): whichever pane the user
 * scrolls drives the other.
 */
@Composable
fun SplitDiffView(
    state: CompareState,
    syncScroll: Boolean = true,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** Called with the hunk index when a diff row (CHANGED/ADDED/REMOVED) is tapped. */
    onDiffRowTapped: ((hunkIndex: Int) -> Unit)? = null,
    /** Row indices (in the AlignedRow list) that match the current find query. */
    findMatches: List<Int> = emptyList(),
    /** Index within [findMatches] that is currently focused; -1 to highlight nothing. */
    findMatchIndex: Int = -1,
) {
    val alignedRows = remember(state.result) { state.buildAlignedRows() }
    val leftListState = rememberLazyListState()
    val rightListState = rememberLazyListState()
    val colors = LocalCompareColors.current
    val hunks = state.result.hunks
    val cache = state.intraLineCache
    val granularity = state.granularity
    val density = LocalDensity.current

    // ── Shared horizontal document scroll (R-45) ─────────────────────────
    val hScroll = rememberHorizontalScrollController()
    val textMeasurer = rememberTextMeasurer()
    val contentStyle = remember { TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    val charWidthPx = remember(textMeasurer, contentStyle) {
        textMeasurer.measure(AnnotatedString("0"), style = contentStyle).size.width.toFloat()
    }
    val maxDisplayCols = remember(state.leftLines, state.rightLines) {
        var m = 0
        for (line in state.leftLines) {
            val len = line.length + line.count { it == '\t' } * 3
            if (len > m) m = len
        }
        for (line in state.rightLines) {
            val len = line.length + line.count { it == '\t' } * 3
            if (len > m) m = len
        }
        m
    }
    // Panes are equal-width (weight 1f each), so one pane's measured width
    // defines the shared viewport for clamping.
    var paneWidthPx by remember { mutableStateOf(0f) }
    LaunchedEffect(maxDisplayCols, paneWidthPx, charWidthPx) {
        val gutterPx = with(density) { SPLIT_GUTTER_WIDTH.toPx() }
        hScroll.updateBounds(
            contentWidthPx = maxDisplayCols * charWidthPx + charWidthPx,
            viewportWidthPx = (paneWidthPx - gutterPx).coerceAtLeast(0f),
        )
    }

    val focusedFindRow = remember(findMatches, findMatchIndex) {
        if (findMatchIndex >= 0 && findMatchIndex < findMatches.size) findMatches[findMatchIndex] else -1
    }
    val findMatchSet = remember(findMatches) { findMatches.toHashSet() }

    // Synchronise scroll between panes (bidirectional, R-46)
    if (syncScroll) {
        SyncScroll(leftListState, rightListState)
    }

    // Scroll to current difference — find the first aligned row belonging to this hunk
    LaunchedEffect(state.currentDiffIndex) {
        @Suppress("UnusedVariable")
        val hunk = state.currentHunk ?: return@LaunchedEffect
        val targetRow = alignedRows.indexOfFirst { it.hunkIndex == state.currentDiffIndex }
        if (targetRow >= 0) {
            leftListState.animateScrollToItem(targetRow)
            if (!syncScroll) {
                rightListState.animateScrollToItem(targetRow)
            }
        }
    }

    // Scroll to focused find match
    LaunchedEffect(focusedFindRow) {
        if (focusedFindRow >= 0) {
            leftListState.animateScrollToItem(focusedFindRow)
        }
    }

    // Update first visible line and visible line count in CompareState for minimap (R-31)
    LaunchedEffect(leftListState) {
        snapshotFlow { leftListState.layoutInfo }.collect { info ->
            val first = info.visibleItemsInfo.firstOrNull()?.index?.toLong() ?: 0L
            state.firstVisibleLine = first
            val visible = info.visibleItemsInfo.size
            if (visible > 0) state.visibleLineCount = visible
        }
    }

    // Compute bookmarked line sets per side from state (W-02).
    val leftBookmarkedLines = remember(state.compareBookmarks.toList()) {
        state.compareBookmarks
            .filter { it.side == com.omnieditor.core.model.CompareSide.LEFT }
            .map { it.lineIndex }
            .toHashSet()
    }
    val rightBookmarkedLines = remember(state.compareBookmarks.toList()) {
        state.compareBookmarks
            .filter { it.side == com.omnieditor.core.model.CompareSide.RIGHT }
            .map { it.lineIndex }
            .toHashSet()
    }

    Row(modifier = modifier.fillMaxSize()) {
        // Left pane — renders left side of each AlignedRow (null → spacer)
        SplitPane(
            alignedRows = alignedRows,
            lines = state.leftLines,
            pairedLines = state.rightLines,
            side = Side.LEFT,
            currentHunkIndex = state.currentDiffIndex,
            listState = leftListState,
            colors = colors,
            hunks = hunks,
            intraLineCache = cache,
            granularity = granularity,
            contentPadding = contentPadding,
            onDiffRowTapped = onDiffRowTapped,
            onBookmarkToggle = { lineIndex, side -> state.toggleBookmark(lineIndex, side) },
            bookmarkedLines = leftBookmarkedLines,
            findMatchSet = findMatchSet,
            focusedFindRow = focusedFindRow,
            hScroll = hScroll,
            modifier = Modifier
                .weight(1f)
                .onGloballyPositioned { paneWidthPx = it.size.width.toFloat() },
        )

        // Connector column — same row index on both sides, so coordinates are now correct
        ConnectorColumn(
            alignedRows = alignedRows,
            leftListState = leftListState,
            colors = colors,
            modifier = Modifier.width(24.dp).fillMaxHeight(),
        )

        // Right pane — renders right side of each AlignedRow (null → spacer)
        SplitPane(
            alignedRows = alignedRows,
            lines = state.rightLines,
            pairedLines = state.leftLines,
            side = Side.RIGHT,
            currentHunkIndex = state.currentDiffIndex,
            listState = rightListState,
            colors = colors,
            hunks = hunks,
            intraLineCache = cache,
            granularity = granularity,
            contentPadding = contentPadding,
            onDiffRowTapped = onDiffRowTapped,
            onBookmarkToggle = { lineIndex, side -> state.toggleBookmark(lineIndex, side) },
            bookmarkedLines = rightBookmarkedLines,
            findMatchSet = findMatchSet,
            focusedFindRow = focusedFindRow,
            hScroll = hScroll,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SplitPane(
    alignedRows: List<AlignedRow>,
    lines: List<String>,
    /** The opposite side's lines — used to compute intra-line diff for CHANGED rows (R-26). */
    pairedLines: List<String>,
    side: Side,
    currentHunkIndex: Int,
    listState: LazyListState,
    colors: com.omnieditor.design.CompareColors,
    hunks: List<com.omnieditor.core.model.Hunk>,
    intraLineCache: IntraLineCache,
    granularity: Granularity,
    contentPadding: PaddingValues,
    hScroll: HorizontalScrollController,
    onDiffRowTapped: ((hunkIndex: Int) -> Unit)? = null,
    onBookmarkToggle: ((lineIndex: Long, side: com.omnieditor.core.model.CompareSide) -> Unit)? = null,
    bookmarkedLines: Set<Long> = emptySet(),
    findMatchSet: Set<Int> = emptySet(),
    focusedFindRow: Int = -1,
    modifier: Modifier = Modifier,
) {
    val compareSide = if (side == Side.LEFT) {
        com.omnieditor.core.model.CompareSide.LEFT
    } else {
        com.omnieditor.core.model.CompareSide.RIGHT
    }
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier
            // Horizontal pans on either pane feed the shared controller, so
            // both panes translate together (R-45).
            .horizontalDocumentScroll(hScroll)
            // Fling-at-bound swipe navigation (F-09, W-03, ADR-013).
            .then(
                with(SwipeDiffDetector) {
                    Modifier.detectSwipeDiff(
                        isAtLeftBound = hScroll.offsetPx <= 0f,
                        isAtRightBound = hScroll.offsetPx >= hScroll.maxOffsetPx,
                        onPrevDiff = { /* driven from left pane only to avoid double-fire */ },
                        onNextDiff = { /* driven from left pane only to avoid double-fire */ },
                    )
                }
            ),
    ) {
        itemsIndexed(alignedRows, key = { idx, row -> "${side}_${row.left}_${row.right}_$idx" }) { idx, row ->
            val lineIdx = if (side == Side.LEFT) row.left else row.right
            val isBookmarked = lineIdx != null && lineIdx in bookmarkedLines
            SplitPaneRow(
                row = row,
                idx = idx,
                side = side,
                lines = lines,
                pairedLines = pairedLines,
                currentHunkIndex = currentHunkIndex,
                findMatchSet = findMatchSet,
                focusedFindRow = focusedFindRow,
                isBookmarked = isBookmarked,
                colors = colors,
                hunks = hunks,
                intraLineCache = intraLineCache,
                granularity = granularity,
                hScroll = hScroll,
                onDiffRowTapped = onDiffRowTapped,
                onLongPress = if (lineIdx != null && onBookmarkToggle != null) {
                    { onBookmarkToggle(lineIdx, compareSide) }
                } else null,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SplitPaneRow(
    row: AlignedRow,
    idx: Int,
    side: Side,
    lines: List<String>,
    pairedLines: List<String>,
    currentHunkIndex: Int,
    findMatchSet: Set<Int>,
    focusedFindRow: Int,
    isBookmarked: Boolean = false,
    colors: com.omnieditor.design.CompareColors,
    hunks: List<com.omnieditor.core.model.Hunk>,
    intraLineCache: IntraLineCache,
    granularity: Granularity,
    hScroll: HorizontalScrollController,
    onDiffRowTapped: ((hunkIndex: Int) -> Unit)?,
    onLongPress: (() -> Unit)? = null,
) {
    val lineIdx = if (side == Side.LEFT) row.left else row.right
    val isSpacer = lineIdx == null
    val rawText = if (lineIdx != null) lines.getOrElse(lineIdx.toInt()) { "" } else ""

    val bgColor = splitPaneBg(isSpacer, row.type, colors)
    val fgColor = splitPaneFg(isSpacer, row.type, colors)
    val glyph = splitPaneGlyph(isSpacer, row.type)
    val isCurrentHunk = row.hunkIndex != null && row.hunkIndex == currentHunkIndex
    val isFindMatch = idx in findMatchSet
    val isFocusedMatch = idx == focusedFindRow
    val sideLabel = if (side == Side.LEFT) "left" else "right"
    val displayLine = lineIdx ?: 0L
    val a11y = when {
        isSpacer -> "Spacer on $sideLabel"
        row.type == RowType.CONTEXT -> "Unchanged on $sideLabel, line ${displayLine + 1}"
        else -> "${row.type.name} on $sideLabel, line ${displayLine + 1}: $rawText"
    }

    // Intra-line highlighting for CHANGED rows (R-26).
    val displayContent: AnnotatedString = remember(row, rawText, granularity) {
        splitPaneIntraLine(
            row = row,
            side = side,
            lineIdx = lineIdx,
            isSpacer = isSpacer,
            rawText = rawText,
            pairedLines = pairedLines,
            hunks = hunks,
            intraLineCache = intraLineCache,
            granularity = granularity,
            colors = colors,
        )
    }

    val onTap: (() -> Unit)? =
        if (onDiffRowTapped != null && !isSpacer && row.type != RowType.CONTEXT && row.hunkIndex != null) {
            { onDiffRowTapped(row.hunkIndex) }
        } else {
            null
        }

    val rowBg = when {
        isFocusedMatch && !isSpacer -> Color(0xFFFFD54F).copy(alpha = 0.7f)
        isFindMatch && !isSpacer -> Color(0xFFFFEE58).copy(alpha = 0.4f)
        isCurrentHunk && !isSpacer -> bgColor.copy(alpha = 0.7f)
        else -> bgColor
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .height(22.dp)
            .combinedClickable(
                onClick = { onTap?.invoke() },
                onLongClick = { onLongPress?.invoke() },
            )
            .semantics { contentDescription = a11y },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Glyph — pinned. Bookmark indicator overlays when the row is bookmarked (W-02).
        if (isBookmarked && !isSpacer) {
            Text(
                text = "\u25CF",  // bookmark indicator: filled circle
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                modifier = Modifier.width(16.dp).padding(start = 2.dp),
            )
        } else {
            Text(
                text = glyph,
                color = fgColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.width(16.dp).padding(start = 2.dp),
            )
        }
        // Line number — pinned.
        Text(
            text = if (lineIdx != null) "${lineIdx + 1}" else "",
            color = colors.gutter,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.width(36.dp),
            maxLines = 1,
        )
        // Content — full intrinsic width, clipped, translated by the shared
        // offset (R-45). No ellipsis: text past the pane edge is reachable by
        // panning instead of being silently dropped.
        Box(
            modifier = Modifier
                .weight(1f)
                .clipToBounds(),
        ) {
            Text(
                text = displayContent,
                color = fgColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
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
 * Relational connector lines between hunk boundaries in split view.
 *
 * Because both panes share the same AlignedRow list (R-25), hunk boundaries fall on
 * identical row indices in both panes. The connector draws at sub-row precision:
 * both the first visible item index AND the intra-item scroll offset are read,
 * so markers track partial scrolls instead of jumping in whole-row steps (R-46).
 */
@Composable
private fun ConnectorColumn(
    alignedRows: List<AlignedRow>,
    leftListState: LazyListState,
    colors: com.omnieditor.design.CompareColors,
    modifier: Modifier = Modifier,
) {
    val lineHeightDp = 22f // dp, matches row height

    // Collect the first and last row index of each hunk (by hunkIndex tag).
    val hunkBounds = remember(alignedRows) {
        val bounds = mutableMapOf<Int, Pair<Int, Int>>() // hunkIndex → (firstRow, lastRow)
        alignedRows.forEachIndexed { rowIdx, row ->
            val h = row.hunkIndex ?: return@forEachIndexed
            val existing = bounds[h]
            if (existing == null) {
                bounds[h] = Pair(rowIdx, rowIdx)
            } else {
                bounds[h] = Pair(existing.first, rowIdx)
            }
        }
        bounds
    }

    Canvas(modifier = modifier) {
        // Both reads happen inside the draw scope, so the canvas invalidates
        // on every scroll frame, including intra-item offset changes.
        val firstVisible = leftListState.firstVisibleItemIndex
        val intraItemOffsetPx = leftListState.firstVisibleItemScrollOffset.toFloat()
        val lineHeightPx = lineHeightDp * density

        for (bounds in hunkBounds.values) {
            val (startRow, endRow) = bounds
            val topY = (startRow - firstVisible) * lineHeightPx - intraItemOffsetPx
            val bottomY = (endRow - firstVisible + 1) * lineHeightPx - intraItemOffsetPx

            // Draw a simple vertical bar in the middle of the connector column
            drawLine(
                color = colors.changedFg,
                start = Offset(size.width / 2f, topY),
                end = Offset(size.width / 2f, bottomY),
                strokeWidth = 2f,
            )
        }
    }
}

/**
 * Bidirectional scroll synchronisation between two panes (R-46).
 *
 * The previous implementation mirrored only left → right: scrolling the right
 * pane moved nothing, and the next touch on the left pane snapped the right
 * pane back. Here, whichever pane is being scrolled ([LazyListState.isScrollInProgress])
 * drives the other. Echoes are suppressed twice over: a `syncing` guard covers
 * the mirroring call itself, and by the time the mirrored pane's own snapshot
 * emission is processed its scroll has already ended, so its
 * `isScrollInProgress` check fails and no mirror-back occurs.
 */
@Composable
private fun SyncScroll(
    primary: LazyListState,
    secondary: LazyListState,
) {
    LaunchedEffect(primary, secondary) {
        var syncing = false

        // Align panes once when sync is (re-)enabled.
        secondary.scrollToItem(
            primary.firstVisibleItemIndex,
            primary.firstVisibleItemScrollOffset,
        )

        launch {
            snapshotFlow { primary.firstVisibleItemIndex to primary.firstVisibleItemScrollOffset }
                .collect { (index, offset) ->
                    if (!syncing && primary.isScrollInProgress) {
                        syncing = true
                        try {
                            secondary.scrollToItem(index, offset)
                        } finally {
                            syncing = false
                        }
                    }
                }
        }
        launch {
            snapshotFlow { secondary.firstVisibleItemIndex to secondary.firstVisibleItemScrollOffset }
                .collect { (index, offset) ->
                    if (!syncing && secondary.isScrollInProgress) {
                        syncing = true
                        try {
                            primary.scrollToItem(index, offset)
                        } finally {
                            syncing = false
                        }
                    }
                }
        }
    }
}

/** Background colour for a split-pane row, extracted to reduce [SplitPaneRow] complexity. */
private fun splitPaneBg(
    isSpacer: Boolean,
    type: RowType,
    colors: com.omnieditor.design.CompareColors,
): Color = if (isSpacer) {
    colors.gutter.copy(alpha = 0.08f)
} else {
    when (type) {
        RowType.ADDED -> colors.addedBg
        RowType.REMOVED -> colors.removedBg
        RowType.CHANGED_OLD, RowType.CHANGED_NEW -> colors.changedBg
        RowType.CONFLICT_OLD, RowType.CONFLICT_NEW -> colors.conflictBg
        RowType.CONTEXT -> Color.Transparent
    }
}

/** Foreground colour for a split-pane row, extracted to reduce [SplitPaneRow] complexity. */
private fun splitPaneFg(
    isSpacer: Boolean,
    type: RowType,
    colors: com.omnieditor.design.CompareColors,
): Color = when {
    isSpacer -> Color.Transparent
    type == RowType.ADDED -> colors.addedFg
    type == RowType.REMOVED -> colors.removedFg
    type == RowType.CHANGED_OLD || type == RowType.CHANGED_NEW -> colors.changedFg
    type == RowType.CONFLICT_OLD || type == RowType.CONFLICT_NEW -> colors.conflictFg
    else -> Color.Unspecified
}

/** Gutter glyph for a split-pane row, extracted to reduce [SplitPaneRow] complexity. */
private fun splitPaneGlyph(isSpacer: Boolean, type: RowType): String = when {
    isSpacer -> " "
    type == RowType.ADDED -> "+"
    type == RowType.REMOVED -> "−"
    type == RowType.CHANGED_OLD || type == RowType.CHANGED_NEW -> "~"
    type == RowType.CONFLICT_OLD || type == RowType.CONFLICT_NEW -> "!"
    else -> " "
}

/**
 * Compute intra-line highlighted [AnnotatedString] for a split-pane row (R-26).
 *
 * Extracted from [SplitPaneRow] to keep cyclomatic complexity within budget.
 */
private fun splitPaneIntraLine(
    row: AlignedRow,
    side: Side,
    lineIdx: Long?,
    isSpacer: Boolean,
    rawText: String,
    pairedLines: List<String>,
    hunks: List<com.omnieditor.core.model.Hunk>,
    intraLineCache: IntraLineCache,
    granularity: Granularity,
    colors: com.omnieditor.design.CompareColors,
): AnnotatedString {
    if (isSpacer) return AnnotatedString(rawText)
    if (side == Side.LEFT && (row.type == RowType.CHANGED_OLD || row.type == RowType.CONFLICT_OLD)) {
        val leftIdx = lineIdx ?: return AnnotatedString(rawText)
        val hunk = row.hunkIndex?.let { hunks.getOrNull(it) } ?: return AnnotatedString(rawText)
        val rightIdx = hunk.rightStart + (leftIdx - hunk.leftStart)
        if (rightIdx >= hunk.rightEnd) return AnnotatedString(rawText)
        val pairedText = pairedLines.getOrElse(rightIdx.toInt()) { "" }
        val result = intraLineCache.get(rawText, pairedText, leftIdx, rightIdx, granularity)
        return highlightIntraLine(rawText, result.leftRanges, colors.intraLineOldBg)
    }
    if (side == Side.RIGHT && (row.type == RowType.CHANGED_NEW || row.type == RowType.CONFLICT_NEW)) {
        val rightIdx = lineIdx ?: return AnnotatedString(rawText)
        val hunk = row.hunkIndex?.let { hunks.getOrNull(it) } ?: return AnnotatedString(rawText)
        val leftIdx = hunk.leftStart + (rightIdx - hunk.rightStart)
        if (leftIdx >= hunk.leftEnd) return AnnotatedString(rawText)
        val pairedText = pairedLines.getOrElse(leftIdx.toInt()) { "" }
        val result = intraLineCache.get(pairedText, rawText, leftIdx, rightIdx, granularity)
        return highlightIntraLine(rawText, result.rightRanges, colors.intraLineNewBg)
    }
    return AnnotatedString(rawText)
}
