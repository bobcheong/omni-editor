package com.omnieditor.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0B5B58),
    secondary = Color(0xFF3F5F5D),
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF6FD3CD),
    secondary = Color(0xFF9BC0BD),
)

@Composable
actual fun platformColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme {
    // Desktop has no dynamic colour; always use static scheme.
    return if (darkTheme) DarkScheme else LightScheme
}

@Composable
actual fun platformAnimationsDisabled(): Boolean = false
