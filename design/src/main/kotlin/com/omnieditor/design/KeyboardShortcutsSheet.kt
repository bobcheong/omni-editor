package com.omnieditor.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.omnieditor.core.model.KeyboardShortcuts
import com.omnieditor.core.model.ShortcutAction

/**
 * Bottom sheet that lists all keyboard shortcuts (OE-INP-1, R-37).
 *
 * Shown when the user selects "Keyboard shortcuts" from any screen menu.
 * Groups actions into logical sections. Lives in the design module so both
 * editor and compare screens can share it without a cyclic dependency.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardShortcutsSheet(
    shortcuts: KeyboardShortcuts = KeyboardShortcuts.DEFAULT,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Keyboard shortcuts",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            HorizontalDivider()

            ShortcutSection(
                title = "File",
                entries = listOf(
                    ShortcutAction.SAVE,
                    ShortcutAction.UNDO,
                    ShortcutAction.REDO,
                    ShortcutAction.FIND,
                    ShortcutAction.FIND_REPLACE,
                ),
                shortcuts = shortcuts,
            )

            ShortcutSection(
                title = "Navigation",
                entries = listOf(
                    ShortcutAction.GO_TO_LINE,
                    ShortcutAction.NEXT_DIFF,
                    ShortcutAction.PREV_DIFF,
                ),
                shortcuts = shortcuts,
            )

            ShortcutSection(
                title = "Compare",
                entries = listOf(
                    ShortcutAction.MERGE_LEFT,
                    ShortcutAction.MERGE_RIGHT,
                    ShortcutAction.TOGGLE_LAYOUT,
                ),
                shortcuts = shortcuts,
            )

            ShortcutSection(
                title = "Edit",
                entries = listOf(
                    ShortcutAction.SELECT_ALL,
                    ShortcutAction.CUT,
                    ShortcutAction.COPY,
                    ShortcutAction.PASTE,
                    ShortcutAction.DUPLICATE_LINE,
                ),
                shortcuts = shortcuts,
            )
        }
    }
}

@Composable
private fun ShortcutSection(
    title: String,
    entries: List<ShortcutAction>,
    shortcuts: KeyboardShortcuts,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp),
    )
    for (action in entries) {
        val binding = shortcuts.bindingFor(action)
        ShortcutRow(
            action = shortcutActionLabel(action),
            keyLabel = binding?.label() ?: "\u2014",
        )
    }
    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ShortcutRow(action: String, keyLabel: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = action,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = keyLabel,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Human-readable label for a [ShortcutAction]. */
internal fun shortcutActionLabel(action: ShortcutAction): String = when (action) {
    ShortcutAction.SAVE -> "Save"
    ShortcutAction.FIND -> "Find"
    ShortcutAction.FIND_REPLACE -> "Find and replace"
    ShortcutAction.UNDO -> "Undo"
    ShortcutAction.REDO -> "Redo"
    ShortcutAction.NEXT_DIFF -> "Next difference"
    ShortcutAction.PREV_DIFF -> "Previous difference"
    ShortcutAction.GO_TO_LINE -> "Go to line"
    ShortcutAction.MERGE_LEFT -> "Merge left"
    ShortcutAction.MERGE_RIGHT -> "Merge right"
    ShortcutAction.TOGGLE_LAYOUT -> "Toggle layout"
    ShortcutAction.SELECT_ALL -> "Select all"
    ShortcutAction.CUT -> "Cut"
    ShortcutAction.COPY -> "Copy"
    ShortcutAction.PASTE -> "Paste"
    ShortcutAction.DUPLICATE_LINE -> "Duplicate line"
}
