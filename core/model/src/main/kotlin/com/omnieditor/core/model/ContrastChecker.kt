package com.omnieditor.core.model

import kotlin.math.pow

/**
 * WCAG contrast ratio checker (OE-APP-2).
 *
 * Every diff colour pair must meet 4.5:1 against its background.
 * Colour is never the only signal — glyph markers are also used.
 */
object ContrastChecker {

    /**
     * Compute the WCAG contrast ratio between two colours.
     * Colours are ARGB integers (0xAARRGGBB).
     * Returns a ratio >= 1.0.
     */
    fun contrastRatio(fg: Int, bg: Int): Double {
        val fgLum = relativeLuminance(fg)
        val bgLum = relativeLuminance(bg)
        val lighter = maxOf(fgLum, bgLum)
        val darker = minOf(fgLum, bgLum)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Check if a foreground/background pair meets the WCAG AA threshold (4.5:1).
     */
    fun meetsAA(fg: Int, bg: Int): Boolean {
        return contrastRatio(fg, bg) >= 4.5
    }

    /**
     * Check if a foreground/background pair meets the WCAG AAA threshold (7:1).
     */
    fun meetsAAA(fg: Int, bg: Int): Boolean {
        return contrastRatio(fg, bg) >= 7.0
    }

    /**
     * Validate all colour pairs in a theme definition.
     * Returns a list of failures (empty = all pass).
     */
    fun validateTheme(theme: ThemeDefinition): List<String> {
        val failures = mutableListOf<String>()
        val c = theme.compare

        val pairs = listOf(
            "added" to (c.addedFg to c.addedBg),
            "removed" to (c.removedFg to c.removedBg),
            "changed" to (c.changedFg to c.changedBg),
            "conflict" to (c.conflictFg to c.conflictBg),
        )

        for ((name, pair) in pairs) {
            val ratio = contrastRatio(pair.first, pair.second)
            if (ratio < 4.5) {
                failures.add("$name: ratio %.2f < 4.5:1 (fg=0x%08X, bg=0x%08X)".format(ratio, pair.first, pair.second))
            }
        }

        return failures
    }

    /**
     * Relative luminance per WCAG 2.0 formula.
     * Input is an ARGB integer.
     */
    internal fun relativeLuminance(color: Int): Double {
        val r = linearize((color shr 16 and 0xFF) / 255.0)
        val g = linearize((color shr 8 and 0xFF) / 255.0)
        val b = linearize((color and 0xFF) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun linearize(channel: Double): Double {
        return if (channel <= 0.04045) {
            channel / 12.92
        } else {
            ((channel + 0.055) / 1.055).pow(2.4)
        }
    }
}
