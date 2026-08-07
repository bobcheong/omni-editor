package com.omnieditor.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Programmer key row above the IME keyboard (OE-EDT-6).
 *
 * Two scrollable rows:
 * - Top: tab, brackets, quotes, pipe, dollar, slash, common operators
 * - Bottom: arrow keys, undo/redo, keyboard dismiss
 *
 * This is where the mobile editing effort goes — typing code on a phone
 * is painful without easy access to these characters.
 */
@Composable
fun ProgrammerKeyRow(
    onKey: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        // Top row: special characters
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val topKeys = listOf(
                "⇥" to "\t",
                "{" to "{", "}" to "}",
                "(" to "(", ")" to ")",
                "[" to "[", "]" to "]",
                "\"" to "\"", "'" to "'",
                "|" to "|", "\$" to "\$",
                "/" to "/", "\\" to "\\",
                "=" to "=", ":" to ":",
                ";" to ";", "," to ",",
                "<" to "<", ">" to ">",
                "&" to "&", "!" to "!",
                "#" to "#", "_" to "_",
            )
            for ((label, value) in topKeys) {
                KeyButton(label = label, onClick = { onKey(value) })
            }
        }

        // Bottom row: navigation + undo/redo
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            KeyButton(label = "←", onClick = { onKey("LEFT") })
            KeyButton(label = "→", onClick = { onKey("RIGHT") })
            KeyButton(label = "↑", onClick = { onKey("UP") })
            KeyButton(label = "↓", onClick = { onKey("DOWN") })
            KeyButton(label = "Home", onClick = { onKey("HOME") })
            KeyButton(label = "End", onClick = { onKey("END") })
            KeyButton(label = "↶", onClick = onUndo)
            KeyButton(label = "↷", onClick = onRedo)
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .widthIn(min = 36.dp)
            .height(36.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                MaterialTheme.shapes.small,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}
