package com.omnieditor.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Status strip at the bottom of the editor (S-04):
 * Line, column, selection length, encoding, line ending, read-only state.
 */
@Composable
fun StatusStrip(
    state: EditorState,
    encoding: String,
    lineEnding: String,
    modifier: Modifier = Modifier,
) {
    val selectionInfo = if (state.hasSelection) {
        val selected = state.selectedText()
        val lines = selected.count { it == '\n' } + 1
        val chars = selected.length
        " · Sel $lines×$chars"
    } else ""

    val readOnlyBadge = if (state.readOnly) " · READ-ONLY" else ""

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Ln ${state.caretLine + 1}, Col ${state.caretColumn + 1}$selectionInfo",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "$encoding · $lineEnding$readOnlyBadge",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
