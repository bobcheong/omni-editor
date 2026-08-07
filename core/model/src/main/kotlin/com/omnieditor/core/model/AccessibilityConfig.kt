package com.omnieditor.core.model

import kotlinx.serialization.Serializable

/**
 * Accessibility configuration (NFR-A1, NFR-A2).
 *
 * - Reduced motion: honours system "remove animations" setting
 * - Font scale: app usable at 200% with no clipped text
 * - High contrast: forces high-contrast theme
 *
 * Every function must be reachable without gestures (switch/TalkBack).
 */
@Serializable
data class AccessibilityConfig(
    /** Honour system reduced-motion preference. */
    val reduceMotion: Boolean = true,
    /** Force high-contrast theme regardless of system setting. */
    val forceHighContrast: Boolean = false,
    /** Minimum touch target size in dp (WCAG recommendation: 48dp). */
    val minTouchTarget: Int = 48,
)
