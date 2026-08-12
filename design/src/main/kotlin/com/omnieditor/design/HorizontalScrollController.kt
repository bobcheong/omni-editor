package com.omnieditor.design

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Single-source-of-truth horizontal scroll offset for line-based document views (R-43).
 *
 * Why this exists: `ScrollState` is designed for exactly one attached
 * `horizontalScroll` consumer. Sharing one `ScrollState` across many per-row
 * `horizontalScroll` modifiers inside a `LazyColumn` causes every row to
 * overwrite `maxValue` with its own overflow width as rows are composed and
 * recycled — the shared position gets clamped by whichever (possibly short)
 * row measured last, so the viewport snaps back to the left edge during
 * vertical scrolling and long lines become unreachable.
 *
 * This controller instead holds one offset clamped against an externally
 * computed document content width (for monospace text: max display columns ×
 * glyph width, which is exact). Rows render with
 * `graphicsLayer { translationX = -offsetPx }` inside a clipped container, so
 * the whole document pans as a single page while gutters stay pinned.
 */
@Stable
class HorizontalScrollController {

    /** Current scroll offset in px. 0 = left edge; positive = scrolled right. */
    var offsetPx by mutableFloatStateOf(0f)
        private set

    /** Maximum scroll offset in px, derived from content and viewport width. */
    var maxOffsetPx by mutableFloatStateOf(0f)
        private set

    /**
     * Recompute the clamp bounds. Call whenever the document's max line width
     * or the viewport width changes (including rotation). The current offset
     * is re-clamped so shrinking content never leaves the view stranded.
     */
    fun updateBounds(contentWidthPx: Float, viewportWidthPx: Float) {
        maxOffsetPx = (contentWidthPx - viewportWidthPx).coerceAtLeast(0f)
        if (offsetPx > maxOffsetPx) offsetPx = maxOffsetPx
    }

    /**
     * Consume a pointer/fling delta (positive = finger moving right, matching
     * [androidx.compose.foundation.gestures.scrollable] conventions).
     * Returns the amount actually consumed so nested-scroll accounting and
     * fling termination behave correctly at the edges.
     */
    fun dispatchDelta(delta: Float): Float {
        val previous = offsetPx
        offsetPx = (previous - delta).coerceIn(0f, maxOffsetPx)
        return previous - offsetPx
    }

    /** Jump to an absolute offset, clamped to the current bounds. */
    fun scrollTo(offset: Float) {
        offsetPx = offset.coerceIn(0f, maxOffsetPx)
    }

    /**
     * Ensure the content x-coordinate [xPx] is visible within a viewport of
     * [viewportWidthPx], keeping [marginPx] of context. Used to follow the
     * caret while typing past the right edge.
     */
    fun ensureVisible(xPx: Float, viewportWidthPx: Float, marginPx: Float = 48f) {
        if (viewportWidthPx <= 0f) return
        when {
            xPx < offsetPx + marginPx -> scrollTo(xPx - marginPx)
            xPx > offsetPx + viewportWidthPx - marginPx ->
                scrollTo(xPx - viewportWidthPx + marginPx)
        }
    }
}

/** Remember a [HorizontalScrollController] scoped to the current composition. */
@Composable
fun rememberHorizontalScrollController(): HorizontalScrollController =
    remember { HorizontalScrollController() }

/**
 * Attach horizontal document scrolling to a container (typically the
 * `LazyColumn` that owns vertical scrolling). Compose's scrollable axis
 * disambiguation applies: whichever axis crosses touch slop first wins the
 * gesture, so vertical list scrolling and horizontal panning coexist without
 * a custom nested-scroll connection. Fling is handled by the default
 * [androidx.compose.foundation.gestures.ScrollableDefaults] behaviour.
 */
@Composable
fun Modifier.horizontalDocumentScroll(
    controller: HorizontalScrollController,
    enabled: Boolean = true,
): Modifier {
    val scrollableState = rememberScrollableState { delta -> controller.dispatchDelta(delta) }
    return this.scrollable(
        state = scrollableState,
        orientation = Orientation.Horizontal,
        enabled = enabled,
    )
}
