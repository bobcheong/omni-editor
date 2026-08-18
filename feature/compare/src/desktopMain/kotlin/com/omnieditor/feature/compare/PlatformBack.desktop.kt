package com.omnieditor.feature.compare

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Desktop: no system back gesture. See editor's PlatformBack.desktop.kt
    // for rationale. No-op stub until Window-level Escape handling lands.
}
