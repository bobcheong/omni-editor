package com.omnieditor.core.model

import kotlinx.serialization.Serializable

/**
 * Remappable keyboard shortcuts (OE-INP-1).
 *
 * Hardware keyboard shortcuts for next/previous difference, merge,
 * save, find, toggle layout — all remappable. Mirrors the desktop's
 * configurable key mapping concept.
 */
@Serializable
data class KeyboardShortcuts(
    val bindings: Map<ShortcutAction, KeyBinding> = DEFAULT_BINDINGS,
) {
    fun bindingFor(action: ShortcutAction): KeyBinding? = bindings[action]

    fun findAction(keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean): ShortcutAction? {
        return bindings.entries.firstOrNull { (_, binding) ->
            binding.keyCode == keyCode &&
                binding.ctrl == ctrl &&
                binding.shift == shift &&
                binding.alt == alt
        }?.key
    }

    fun withBinding(action: ShortcutAction, binding: KeyBinding): KeyboardShortcuts {
        return copy(bindings = bindings + (action to binding))
    }

    companion object {
        val DEFAULT_BINDINGS = mapOf(
            // File operations
            ShortcutAction.SAVE to KeyBinding(83, ctrl = true),           // Ctrl+S
            ShortcutAction.FIND to KeyBinding(70, ctrl = true),           // Ctrl+F
            ShortcutAction.FIND_REPLACE to KeyBinding(72, ctrl = true),   // Ctrl+H
            ShortcutAction.UNDO to KeyBinding(90, ctrl = true),           // Ctrl+Z
            ShortcutAction.REDO to KeyBinding(90, ctrl = true, shift = true), // Ctrl+Shift+Z

            // Navigation
            ShortcutAction.NEXT_DIFF to KeyBinding(78, alt = true),       // Alt+N (next)
            ShortcutAction.PREV_DIFF to KeyBinding(80, alt = true),       // Alt+P (prev)
            ShortcutAction.GO_TO_LINE to KeyBinding(71, ctrl = true),     // Ctrl+G

            // Compare
            ShortcutAction.MERGE_LEFT to KeyBinding(37, alt = true),      // Alt+Left
            ShortcutAction.MERGE_RIGHT to KeyBinding(39, alt = true),     // Alt+Right
            ShortcutAction.TOGGLE_LAYOUT to KeyBinding(76, ctrl = true, shift = true), // Ctrl+Shift+L

            // Editor
            ShortcutAction.SELECT_ALL to KeyBinding(65, ctrl = true),     // Ctrl+A
            ShortcutAction.CUT to KeyBinding(88, ctrl = true),            // Ctrl+X
            ShortcutAction.COPY to KeyBinding(67, ctrl = true),           // Ctrl+C
            ShortcutAction.PASTE to KeyBinding(86, ctrl = true),          // Ctrl+V
            ShortcutAction.DUPLICATE_LINE to KeyBinding(68, ctrl = true, shift = true), // Ctrl+Shift+D
        )

        val DEFAULT = KeyboardShortcuts()
    }
}

enum class ShortcutAction {
    SAVE, FIND, FIND_REPLACE, UNDO, REDO,
    NEXT_DIFF, PREV_DIFF, GO_TO_LINE,
    MERGE_LEFT, MERGE_RIGHT, TOGGLE_LAYOUT,
    SELECT_ALL, CUT, COPY, PASTE, DUPLICATE_LINE,
}

@Serializable
data class KeyBinding(
    val keyCode: Int,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
) {
    fun label(): String {
        val parts = mutableListOf<String>()
        if (ctrl) parts.add("Ctrl")
        if (alt) parts.add("Alt")
        if (shift) parts.add("Shift")
        parts.add(keyCodeToLabel(keyCode))
        return parts.joinToString("+")
    }

    private fun keyCodeToLabel(code: Int): String {
        return when (code) {
            in 65..90 -> ('A' + (code - 65)).toString()
            in 48..57 -> ('0' + (code - 48)).toString()
            37 -> "←"
            38 -> "↑"
            39 -> "→"
            40 -> "↓"
            else -> "Key$code"
        }
    }
}
