package com.omnieditor.feature.compare

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnieditor.design.LocalCompareColors

/**
 * Split (side-by-side) diff view — default at ≥600dp or landscape (OE-TXT-1).
 *
 * Two synchronised panes with relational connector lines between matched
 * blocks. Scrolling is synchronised by default (toggleable via OE-TXT-6).
 *
 * Both panes render from the same List<AlignedRow> (R-25). Sync holds by construction:
 * both lists have identical length; spacer rows (null on one side) keep matched context
 * lines on the same visual row after any insertion or deletion.
 */
@Composable
fun SplitDiffView(
    state: CompareState,
    syncScroll: Boolean = true,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val alignedRows = remember(state.result) { state.buildAlignedRows() }
    val leftListState = rememberLazyListState()
    val rightListState = rememberLazyListState()
    val colors = LocalCompareColors.current

    // Synchronise scroll between panes
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

    Row(modifier = modifier.fillMaxSize()) {
        // Left pane — renders left side of each AlignedRow (null → spacer)
        SplitPane(
            alignedRows = alignedRows,
            lines = state.leftLines,
            side = Side.LEFT,
            currentHunkIndex = state.currentDiffIndex,
            listState = leftListState,
            colors = colors,
            contentPadding = contentPadding,
            modifier = Modifier.weight(1f),
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
            side = Side.RIGHT,
            currentHunkIndex = state.currentDiffIndex,
            listState = rightListState,
            colors = colors,
            contentPadding = contentPadding,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SplitPane(
    alignedRows: List<AlignedRow>,
    lines: List<String>,
    side: Side,
    currentHunkIndex: Int,
    listState: LazyListState,
    colors: com.omnieditor.design.CompareColors,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        itemsIndexed(alignedRows, key = { idx, row -> "${side}_${row.left}_${row.right}_$idx" }) { _, row ->
            val lineIdx = if (side == Side.LEFT) row.left else row.right
            val isSpacer = lineIdx == null
            val text = if (lineIdx != null) lines.getOrElse(lineIdx.toInt()) { "" } else ""

            val bgColor = if (isSpacer) {
                colors.gutter.copy(alpha = 0.08f)
            } else {
                when (row.type) {
                    RowType.ADDED -> colors.addedBg
                    RowType.REMOVED -> colors.removedBg
                    RowType.CHANGED_OLD, RowType.CHANGED_NEW -> colors.changedBg
                    RowType.CONTEXT -> Color.Transparent
                }
            }
            val fgColor = when {
                isSpacer -> Color.Transparent
                row.type == RowType.ADDED -> colors.addedFg
                row.type == RowType.REMOVED -> colors.removedFg
                row.type == RowType.CHANGED_OLD || row.type == RowType.CHANGED_NEW -> colors.changedFg
                else -> MaterialTheme.colorScheme.onSurface
            }
            val glyph = when {
                isSpacer -> " "
                row.type == RowType.ADDED -> "+"
                row.type == RowType.REMOVED -> "−"
                row.type == RowType.CHANGED_OLD || row.type == RowType.CHANGED_NEW -> "~"
                else -> " "
            }
            val isCurrentHunk = row.hunkIndex != null && row.hunkIndex == currentHunkIndex
            val sideLabel = if (side == Side.LEFT) "left" else "right"
            val displayLine = lineIdx ?: 0L
            val a11y = when {
                isSpacer -> "Spacer on $sideLabel"
                row.type == RowType.CONTEXT -> "Unchanged on $sideLabel, line ${displayLine + 1}"
                else -> "${row.type.name} on $sideLabel, line ${displayLine + 1}: $text"
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isCurrentHunk && !isSpacer) bgColor.copy(alpha = 0.7f) else bgColor)
                    .height(22.dp)
                    .semantics { contentDescription = a11y },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Glyph
                Text(
                    text = glyph,
                    color = fgColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.width(16.dp).padding(start = 2.dp),
                )
                // Line number
                Text(
                    text = if (lineIdx != null) "${lineIdx + 1}" else "",
                    color = colors.gutter,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.width(36.dp),
                    maxLines = 1,
                )
                // Content
                Text(
                    text = text,
                    color = fgColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Relational connector lines between hunk boundaries in split view.
 *
 * Because both panes share the same AlignedRow list (R-25), hunk boundaries fall on
 * identical row indices in both panes. The connector simply draws horizontal markers at
 * the row where each hunk starts and ends — no offset arithmetic required.
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
        val firstVisible = leftListState.firstVisibleItemIndex
        val lineHeightPx = lineHeightDp * density

        for (bounds in hunkBounds.values) {
            val (startRow, endRow) = bounds
            val topY = (startRow - firstVisible) * lineHeightPx
            val bottomY = (endRow - firstVisible + 1) * lineHeightPx

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
 * Synchronise scroll between two LazyListStates.
 */
@Composable
private fun SyncScroll(
    primary: LazyListState,
    secondary: LazyListState,
) {
    LaunchedEffect(Unit) {
        snapshotFlow { primary.firstVisibleItemIndex to primary.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                secondary.scrollToItem(index, offset)
            }
    }
}
