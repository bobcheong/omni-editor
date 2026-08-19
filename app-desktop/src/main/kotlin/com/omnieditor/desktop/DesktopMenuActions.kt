package com.omnieditor.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import com.omnieditor.core.model.KeyBinding
import com.omnieditor.core.model.KeyboardShortcuts
import com.omnieditor.core.model.ShortcutAction

/**
 * Mutable action holder for the desktop menu bar.
 *
 * Active screens register their callbacks on composition. The menu bar reads
 * these to wire items and determine enabled state. When no screen sets an
 * action, the menu item is disabled (never a silent no-op).
 */
class DesktopMenuActions {
    var onSave: (() -> Unit)? by mutableStateOf(null)
    var onUndo: (() -> Unit)? by mutableStateOf(null)
    var onRedo: (() -> Unit)? by mutableStateOf(null)
    var onCut: (() -> Unit)? by mutableStateOf(null)
    var onCopy: (() -> Unit)? by mutableStateOf(null)
    var onPaste: (() -> Unit)? by mutableStateOf(null)
    var onFind: (() -> Unit)? by mutableStateOf(null)
    var isDirty: Boolean by mutableStateOf(false)

    /** Clear all actions — called when navigating away from a screen. */
    fun clear() {
        onSave = null; onUndo = null; onRedo = null
        onCut = null; onCopy = null; onPaste = null
        onFind = null; isDirty = false
    }
}

/**
 * Convert a [KeyBinding] from the [KeyboardShortcuts] model to a Compose
 * Desktop [KeyShortcut]. Single-source: the model defines every binding;
 * the menu never hardcodes a key.
 */
fun KeyBinding.toKeyShortcut(): KeyShortcut {
    val key = when (keyCode) {
        in 65..90 -> Key(keyCode.toLong() + (97 - 65)) // A-Z → Key.A etc. (lowercase)
        in 48..57 -> Key(keyCode.toLong()) // 0-9
        9 -> Key.Tab
        10 -> Key.Enter
        37 -> Key.DirectionLeft
        38 -> Key.DirectionUp
        39 -> Key.DirectionRight
        40 -> Key.DirectionDown
        47 -> Key.Slash
        70 -> Key.F // explicit for readability
        83 -> Key.S
        90 -> Key.Z
        else -> Key(keyCode.toLong())
    }
    return KeyShortcut(key, ctrl = ctrl, shift = shift, alt = alt)
}

/** Get the [KeyShortcut] for a [ShortcutAction] from the default bindings. */
fun shortcutFor(action: ShortcutAction): KeyShortcut? {
    return KeyboardShortcuts.DEFAULT.bindingFor(action)?.toKeyShortcut()
}
