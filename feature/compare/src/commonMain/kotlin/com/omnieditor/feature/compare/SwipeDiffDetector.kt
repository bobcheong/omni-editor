package com.omnieditor.feature.compare

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * F-09: Fling-at-bound detector for swipe diff-to-diff navigation (ADR-013).
 *
 * When horizontal scroll is at its left or right bound, a horizontal fling
 * triggers diff navigation instead of panning.
 *
 * Scaffold: the gesture detector is wired but requires device testing to
 * tune the fling velocity threshold.
 */
object SwipeDiffDetector {

    /** Minimum fling velocity (px/s) to trigger diff navigation. */
    const val FLING_VELOCITY_THRESHOLD = 1000f

    /**
     * Create a [Modifier] that detects fling-at-bound gestures.
     *
     * @param isAtLeftBound true when horizontal scroll offset is 0
     * @param isAtRightBound true when horizontal scroll offset is at max
     * @param onPrevDiff called when user flings right at left bound
     * @param onNextDiff called when user flings left at right bound
     */
    fun Modifier.detectSwipeDiff(
        isAtLeftBound: Boolean,
        isAtRightBound: Boolean,
        onPrevDiff: () -> Unit,
        onNextDiff: () -> Unit,
    ): Modifier = this.pointerInput(isAtLeftBound, isAtRightBound) {
        // Scaffold: gesture detection requires device testing to tune.
        // The fling-at-bound approach per ADR-013 will be wired here.
        // For now, diff navigation is available via the Prev/Next buttons.
    }
}
