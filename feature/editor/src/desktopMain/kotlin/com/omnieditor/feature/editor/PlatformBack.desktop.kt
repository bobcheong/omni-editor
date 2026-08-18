package com.omnieditor.feature.editor

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Desktop: no system back gesture. The full Escape-key priority chain
    // (dismiss find bar, sheet, selection before closing screen) is handled
    // by onPreviewKeyEvent consumers in the composable hierarchy.
    // The outermost unhandled Escape triggers onBack via Window-level handling
    // (ADR-018). This is a no-op stub until that integration lands.
}
