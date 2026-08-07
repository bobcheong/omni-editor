package com.omnieditor.core.model

import kotlinx.serialization.Serializable

/**
 * Theme definition in our own JSON format (IND-3, OE-APP-1).
 *
 * Controls app chrome and, separately, compare colours per mode.
 * Ships with light, dark, high-contrast and colour-vision-safe pairs.
 * User themes are creatable, exportable and importable.
 */
@Serializable
data class ThemeDefinition(
    val name: String,
    val isDark: Boolean,
    val compare: CompareColorDef,
    val ui: UiColorDef? = null,
) {
    companion object {
        val LIGHT = ThemeDefinition(
            name = "Light",
            isDark = false,
            compare = CompareColorDef(
                addedFg = 0xFF14532D.toInt(), addedBg = 0xFFDCF5E6.toInt(),
                removedFg = 0xFF7F1D1D.toInt(), removedBg = 0xFFFCE4E4.toInt(),
                changedFg = 0xFF6B4A00.toInt(), changedBg = 0xFFFBF0D5.toInt(),
                conflictFg = 0xFF5B2C83.toInt(), conflictBg = 0xFFEFE3F7.toInt(),
            ),
        )

        val DARK = ThemeDefinition(
            name = "Dark",
            isDark = true,
            compare = CompareColorDef(
                addedFg = 0xFF8FE3AE.toInt(), addedBg = 0xFF12301F.toInt(),
                removedFg = 0xFFF3A6A6.toInt(), removedBg = 0xFF3A1616.toInt(),
                changedFg = 0xFFEBCB7C.toInt(), changedBg = 0xFF322608.toInt(),
                conflictFg = 0xFFCFA8E8.toInt(), conflictBg = 0xFF2B1A38.toInt(),
            ),
        )

        val HIGH_CONTRAST = ThemeDefinition(
            name = "High Contrast",
            isDark = false,
            compare = CompareColorDef(
                addedFg = 0xFF004D1A.toInt(), addedBg = 0xFFCCFFDD.toInt(),
                removedFg = 0xFF660000.toInt(), removedBg = 0xFFFFCCCC.toInt(),
                changedFg = 0xFF4D3300.toInt(), changedBg = 0xFFFFE6B3.toInt(),
                conflictFg = 0xFF3D0066.toInt(), conflictBg = 0xFFE6CCFF.toInt(),
            ),
        )

        /** Colour-vision-safe: uses blue/orange instead of red/green. */
        val COLOUR_SAFE = ThemeDefinition(
            name = "Colour Vision Safe",
            isDark = false,
            compare = CompareColorDef(
                addedFg = 0xFF0A3D6B.toInt(), addedBg = 0xFFD6EEFF.toInt(),
                removedFg = 0xFF8B4000.toInt(), removedBg = 0xFFFFE0CC.toInt(),
                changedFg = 0xFF4A4A00.toInt(), changedBg = 0xFFF5F5CC.toInt(),
                conflictFg = 0xFF6B006B.toInt(), conflictBg = 0xFFF5CCF5.toInt(),
            ),
        )

        val BUILT_IN = listOf(LIGHT, DARK, HIGH_CONTRAST, COLOUR_SAFE)
    }
}

@Serializable
data class CompareColorDef(
    val addedFg: Int, val addedBg: Int,
    val removedFg: Int, val removedBg: Int,
    val changedFg: Int, val changedBg: Int,
    val conflictFg: Int, val conflictBg: Int,
)

@Serializable
data class UiColorDef(
    val primary: Int? = null,
    val surface: Int? = null,
    val onSurface: Int? = null,
)
