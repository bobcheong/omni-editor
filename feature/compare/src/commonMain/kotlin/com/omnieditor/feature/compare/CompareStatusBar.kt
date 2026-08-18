package com.omnieditor.feature.compare

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
import com.omnieditor.core.model.EngineMode

/**
 * Status readout for the compare view (OE-TXT-9):
 * hunk count, lines added/removed/changed per side, encoding, line ending, engine mode.
 */
@Composable
fun CompareStatusBar(
    state: CompareState,
    modifier: Modifier = Modifier,
) {
    val stats = state.result.stats
    val modeLabel = when (state.result.engineMode) {
        EngineMode.FULL_INDEX -> "Full"
        EngineMode.BLOCK_MATCH -> "Block"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(22.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "+${stats.linesAdded} −${stats.linesRemoved} ~${stats.linesChanged}",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "${stats.hunkCount} hunks · $modeLabel",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
