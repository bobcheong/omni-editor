package com.omnieditor.feature.compare

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnieditor.core.model.Hunk
import com.omnieditor.core.model.HunkType
import com.omnieditor.design.LocalCompareColors

/**
 * Split (side-by-side) diff view — default at ≥600dp or landscape (OE-TXT-1).
 *
 * Two synchronised panes with relational connector lines between matched
 * blocks. Scrolling is synchronised by default (toggleable via OE-TXT-6).
 */
@Composable
fun SplitDiffView(
    state: CompareState,
    syncScroll: Boolean = true,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val leftListState = rememberLazyListState()
    val rightListState = rememberLazyListState()
    val colors = LocalCompareColors.current

    // Synchronise scroll between panes
    if (syncScroll) {
        SyncScroll(leftListState, rightListState)
    }

    // Scroll to current difference
    LaunchedEffect(state.currentDiffIndex) {
        val hunk = state.currentHunk ?: return@LaunchedEffect
        leftListState.animateScrollToItem(hunk.leftStart.toInt().coerceAtLeast(0))
        if (!syncScroll) {
            rightListState.animateScrollToItem(hunk.rightStart.toInt().coerceAtLeast(0))
        }
    }

    Row(modifier = modifier.fillMaxSize()) {
        // Left pane
        SplitPane(
            lines = state.leftLines,
            hunks = state.result.hunks,
            side = Side.LEFT,
            currentHunkIndex = state.currentDiffIndex,
            listState = leftListState,
            colors = colors,
            contentPadding = contentPadding,
            modifier = Modifier.weight(1f),
        )

        // Connector column
        ConnectorColumn(
            hunks = state.result.hunks,
            leftListState = leftListState,
            rightListState = rightListState,
            colors = colors,
            modifier = Modifier.width(24.dp).fillMaxHeight(),
        )

        // Right pane
        SplitPane(
            lines = state.rightLines,
            hunks = state.result.hunks,
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
    lines: List<String>,
    hunks: List<Hunk>,
    side: Side,
    currentHunkIndex: Int,
    listState: LazyListState,
    colors: com.omnieditor.design.CompareColors,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    // Build a set of changed line indices for this side
    val changedLines = remember(hunks, side) {
        val map = HashMap<Long, Pair<HunkType, Int>>()
        hunks.forEachIndexed { idx, hunk ->
            val range = if (side == Side.LEFT) hunk.leftStart until hunk.leftEnd
            else hunk.rightStart until hunk.rightEnd
            for (line in range) {
                map[line] = Pair(hunk.type, idx)
            }
        }
        map
    }

    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        items(lines.size, key = { it }) { index ->
            val change = changedLines[index.toLong()]
            val bgColor = when (change?.first) {
                HunkType.ADDED -> colors.addedBg
                HunkType.REMOVED -> colors.removedBg
                HunkType.CHANGED -> colors.changedBg
                HunkType.CONFLICT -> colors.conflictBg
                null -> Color.Transparent
            }
            val fgColor = when (change?.first) {
                HunkType.ADDED -> colors.addedFg
                HunkType.REMOVED -> colors.removedFg
                HunkType.CHANGED -> colors.changedFg
                HunkType.CONFLICT -> colors.conflictFg
                null -> MaterialTheme.colorScheme.onSurface
            }
            val glyph = when (change?.first) {
                HunkType.ADDED -> "+"
                HunkType.REMOVED -> "−"
                HunkType.CHANGED -> "~"
                HunkType.CONFLICT -> "!"
                null -> " "
            }
            val isCurrentHunk = change?.second == currentHunkIndex && change != null
            val sideLabel = if (side == Side.LEFT) "left" else "right"
            val a11y = if (change != null) {
                "${change.first.name} on $sideLabel, line ${index + 1}: ${lines[index]}"
            } else {
                "Unchanged on $sideLabel, line ${index + 1}"
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isCurrentHunk) bgColor.copy(alpha = 0.7f) else bgColor)
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
                    text = "${index + 1}",
                    color = colors.gutter,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.width(36.dp),
                    maxLines = 1,
                )
                // Content
                Text(
                    text = lines[index],
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
 * Relational connector lines between matched blocks in split view.
 */
@Composable
private fun ConnectorColumn(
    hunks: List<Hunk>,
    leftListState: LazyListState,
    rightListState: LazyListState,
    colors: com.omnieditor.design.CompareColors,
    modifier: Modifier = Modifier,
) {
    val lineHeight = 22f // dp, matches row height

    Canvas(modifier = modifier) {
        val midX = size.width / 2
        val leftFirst = leftListState.firstVisibleItemIndex
        val rightFirst = rightListState.firstVisibleItemIndex

        for (hunk in hunks) {
            val leftY = (hunk.leftStart.toInt() - leftFirst) * lineHeight
            val leftEndY = (hunk.leftEnd.toInt() - leftFirst) * lineHeight
            val rightY = (hunk.rightStart.toInt() - rightFirst) * lineHeight
            val rightEndY = (hunk.rightEnd.toInt() - rightFirst) * lineHeight

            val color = when (hunk.type) {
                HunkType.ADDED -> colors.addedFg
                HunkType.REMOVED -> colors.removedFg
                HunkType.CHANGED -> colors.changedFg
                HunkType.CONFLICT -> colors.conflictFg
            }

            // Draw connector lines
            drawLine(color, Offset(0f, leftY), Offset(size.width, rightY), strokeWidth = 1.5f)
            drawLine(color, Offset(0f, leftEndY), Offset(size.width, rightEndY), strokeWidth = 1.5f)
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
