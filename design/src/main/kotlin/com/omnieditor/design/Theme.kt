package com.omnieditor.design

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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
)

val LightCompareColors = CompareColors(
    addedFg = Color(0xFF14532D), addedBg = Color(0xFFDCF5E6),
    removedFg = Color(0xFF7F1D1D), removedBg = Color(0xFFFCE4E4),
    changedFg = Color(0xFF6B4A00), changedBg = Color(0xFFFBF0D5),
    conflictFg = Color(0xFF5B2C83), conflictBg = Color(0xFFEFE3F7),
    gutter = Color(0xFF5F696B),
)

val DarkCompareColors = CompareColors(
    addedFg = Color(0xFF8FE3AE), addedBg = Color(0xFF12301F),
    removedFg = Color(0xFFF3A6A6), removedBg = Color(0xFF3A1616),
    changedFg = Color(0xFFEBCB7C), changedBg = Color(0xFF322608),
    conflictFg = Color(0xFFCFA8E8), conflictBg = Color(0xFF2B1A38),
    gutter = Color(0xFF9AA5A7),
)

val LocalCompareColors = staticCompositionLocalOf { LightCompareColors }

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
    CompositionLocalProvider(
        LocalCompareColors provides if (darkTheme) DarkCompareColors else LightCompareColors
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
