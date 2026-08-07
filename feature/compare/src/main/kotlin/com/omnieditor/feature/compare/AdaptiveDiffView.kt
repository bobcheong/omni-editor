package com.omnieditor.feature.compare

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
) {
    // Manual override: null = auto, true = split, false = unified
    var layoutOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }

    BoxWithConstraints(modifier = modifier) {
        val usesSplit = layoutOverride ?: (maxWidth >= 600.dp)

        if (usesSplit) {
            SplitDiffView(
                state = state,
                contentPadding = contentPadding,
            )
        } else {
            UnifiedDiffView(
                state = state,
                contentPadding = contentPadding,
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
