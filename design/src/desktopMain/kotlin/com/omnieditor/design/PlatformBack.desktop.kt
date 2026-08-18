package com.omnieditor.design

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Desktop: Escape key handling deferred to window-level key event processing.
    // See ADR-018 for the focus/priority chain design note.
}
