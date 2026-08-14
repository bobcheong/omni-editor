package com.omnieditor.feature.compare

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Adaptive diff view that switches between unified and split layouts (OE-TXT-1).
 *
 * Width-driven at 600dp — not device-class-driven, so foldables and DeX
 * get the wide layout automatically. A manual toggle overrides at any width.
 *
 * Rotation and foldable fold/unfold preserve scroll position because the
 * [CompareState] survives recomposition and the LazyListState is remembered.
 */
@Composable
fun AdaptiveDiffView(
    state: CompareState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** Called with the hunk index when a diff row (CHANGED/ADDED/REMOVED) is tapped. */
    onDiffRowTapped: ((hunkIndex: Int) -> Unit)? = null,
    /** Row indices (in the AlignedRow list) that match the current find query. */
    findMatches: List<Int> = emptyList(),
    /** Index within [findMatches] that is currently focused; -1 to highlight nothing. */
    findMatchIndex: Int = -1,
    /** Layout override from settings: "unified", "split", or "auto" (width-driven). */
    layoutMode: String = "auto",
    /** Whether split view panes scroll together. */
    syncScroll: Boolean = true,
) {
    BoxWithConstraints(modifier = modifier) {
        val usesSplit = when (layoutMode) {
            "split" -> true
            "unified" -> false
            else -> maxWidth >= 600.dp  // auto
        }

        if (usesSplit) {
            SplitDiffView(
                state = state,
                contentPadding = contentPadding,
                onDiffRowTapped = onDiffRowTapped,
                findMatches = findMatches,
                findMatchIndex = findMatchIndex,
                syncScroll = syncScroll,
            )
        } else {
            UnifiedDiffView(
                state = state,
                contentPadding = contentPadding,
                onDiffRowTapped = onDiffRowTapped,
                findMatches = findMatches,
                findMatchIndex = findMatchIndex,
            )
        }
    }
}

/**
 * Layout mode for the diff view.
 */
enum class DiffLayout {
    /** Automatic: unified < 600dp, split >= 600dp. */
    AUTO,
    /** Force unified layout. */
    UNIFIED,
    /** Force split layout. */
    SPLIT,
}
