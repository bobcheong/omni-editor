package com.omnieditor.design

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ── Syntax token colour schemes (R-19) ──────────────────────────────────────

/**
 * Token colour scheme for syntax highlighting.
 * Three variants are provided: light, dark, and high-contrast.
 * All values are our own (IND-4). Each colour was chosen for readability against
 * its respective surface.
 */
data class SyntaxColorScheme(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val type: Color,
    val function: Color,
    val operator: Color,
    val annotation: Color,
    val tag: Color,
    val attribute: Color,
    val heading: Color,
    val constant: Color,
    val punctuation: Color,
    /** Colour for plain (unclassified) text. */
    val default: Color,
)

private val LightSyntaxColors = SyntaxColorScheme(
    keyword   = Color(0xFF0033B3),
    string    = Color(0xFF067D17),
    comment   = Color(0xFF787878),
    number    = Color(0xFF1750EB),
    type      = Color(0xFF0052A3),
    function  = Color(0xFF00627A),
    operator  = Color(0xFF000000),
    annotation = Color(0xFF9E6D00),
    tag       = Color(0xFF6E2DA0),
    attribute = Color(0xFF174AD4),
    heading   = Color(0xFF0033B3),
    constant  = Color(0xFF6E2DA0),
    punctuation = Color(0xFF000000),
    default   = Color(0xFF1F1F1F),
)

private val DarkSyntaxColors = SyntaxColorScheme(
    keyword   = Color(0xFF6FA0DE),
    string    = Color(0xFF6AAB73),
    comment   = Color(0xFF7A7E85),
    number    = Color(0xFF6897BB),
    type      = Color(0xFF54A5D6),
    function  = Color(0xFF56A8C7),
    operator  = Color(0xFFBABABA),
    annotation = Color(0xFFBBB529),
    tag       = Color(0xFFCC7832),
    attribute = Color(0xFFBABABA),
    heading   = Color(0xFF6FA0DE),
    constant  = Color(0xFF9876AA),
    punctuation = Color(0xFFBABABA),
    default   = Color(0xFFBABABA),
)

private val HighContrastSyntaxColors = SyntaxColorScheme(
    keyword   = Color(0xFFFFFFFF),
    string    = Color(0xFF7EC699),
    comment   = Color(0xFF999999),
    number    = Color(0xFFF08D49),
    type      = Color(0xFF67CDBE),
    function  = Color(0xFFDCDCAA),
    operator  = Color(0xFFFFFFFF),
    annotation = Color(0xFFFFD700),
    tag       = Color(0xFF569CD6),
    attribute = Color(0xFF9CDCFE),
    heading   = Color(0xFFFFFFFF),
    constant  = Color(0xFF569CD6),
    punctuation = Color(0xFFFFFFFF),
    default   = Color(0xFFFFFFFF),
)

/**
 * Provides the correct [SyntaxColorScheme] for the current theme.
 *
 * Call this once per composable scope (it's stable and [remember]-ed internally
 * to avoid re-allocation on every recomposition).
 */
object SyntaxColors {
    @Composable
    fun forTheme(
        isDark: Boolean = isSystemInDarkTheme(),
        isHighContrast: Boolean = false,
    ): SyntaxColorScheme {
        return remember(isDark, isHighContrast) {
            when {
                isHighContrast -> HighContrastSyntaxColors
                isDark -> DarkSyntaxColors
                else -> LightSyntaxColors
            }
        }
    }
}

val LocalSyntaxColors = staticCompositionLocalOf { LightSyntaxColors }

// ── Compare colours ──────────────────────────────────────────────────────────

/**
 * IND-4: this palette is our own. Added/removed green and red are an industry-wide
 * convention and stay, but every value here was chosen for this app.
 *
 * OE-APP-2: colour is never the only signal — the diff view also draws + - ~ glyphs.
 * Every pair below meets 4.5:1 against its own surface (verified in T-26).
 */
data class CompareColors(
    val addedFg: Color, val addedBg: Color,
    val removedFg: Color, val removedBg: Color,
    val changedFg: Color, val changedBg: Color,
    val conflictFg: Color, val conflictBg: Color,
    val gutter: Color,
    /** Character/word-level highlight on the old (left) side of a CHANGED row. */
    val intraLineOldBg: Color,
    /** Character/word-level highlight on the new (right) side of a CHANGED row. */
    val intraLineNewBg: Color,
)

val LightCompareColors = CompareColors(
    addedFg = Color(0xFF14532D), addedBg = Color(0xFFDCF5E6),
    removedFg = Color(0xFF7F1D1D), removedBg = Color(0xFFFCE4E4),
    changedFg = Color(0xFF6B4A00), changedBg = Color(0xFFFBF0D5),
    conflictFg = Color(0xFF5B2C83), conflictBg = Color(0xFFEFE3F7),
    gutter = Color(0xFF5F696B),
    intraLineOldBg = Color(0xFFF5C6C6),   // deeper red tint over removed-bg
    intraLineNewBg = Color(0xFFC4E8CE),   // deeper green tint over added-bg (used for changed-new)
)

val DarkCompareColors = CompareColors(
    addedFg = Color(0xFF8FE3AE), addedBg = Color(0xFF12301F),
    removedFg = Color(0xFFF3A6A6), removedBg = Color(0xFF3A1616),
    changedFg = Color(0xFFEBCB7C), changedBg = Color(0xFF322608),
    conflictFg = Color(0xFFCFA8E8), conflictBg = Color(0xFF2B1A38),
    gutter = Color(0xFF9AA5A7),
    intraLineOldBg = Color(0xFF6B2020),   // deeper red over dark removed-bg
    intraLineNewBg = Color(0xFF1A4D2C),   // deeper green over dark added-bg (used for changed-new)
)

val LocalCompareColors = staticCompositionLocalOf { LightCompareColors }

/**
 * True when the system "remove animations" setting is active
 * (Settings.Global.ANIMATOR_DURATION_SCALE == 0).
 *
 * Consumers: caret blink suppression, sheet transition suppression (R-37, NFR-A1).
 */
val LocalReduceMotion = compositionLocalOf { false }

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0B5B58),
    secondary = Color(0xFF3F5F5D),
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF6FD3CD),
    secondary = Color(0xFF9BC0BD),
)

@Composable
fun OmniTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        // minSdk is 31, so dynamic colour is available on every supported device.
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    // Read system "reduce animations" preference (NFR-A1, R-37).
    // Settings.Global.ANIMATOR_DURATION_SCALE == 0 means animations are disabled.
    val reduceMotion = remember(context) {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        scale == 0f
    }
    CompositionLocalProvider(
        LocalCompareColors provides if (darkTheme) DarkCompareColors else LightCompareColors,
        LocalSyntaxColors provides if (darkTheme) DarkSyntaxColors else LightSyntaxColors,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
