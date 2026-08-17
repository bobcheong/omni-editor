package com.omnieditor.feature.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omnieditor.core.diff.syntax.SymbolInfo
import com.omnieditor.core.diff.syntax.SymbolKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SymbolOutlineSheet(
    symbols: List<SymbolInfo>,
    onSymbolClick: (SymbolInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Outline",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        LazyColumn {
            items(symbols) { symbol ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSymbolClick(symbol) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = when (symbol.kind) {
                            SymbolKind.FUNCTION -> "f"
                            SymbolKind.CLASS -> "C"
                            SymbolKind.PROPERTY -> "p"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    Text(
                        text = symbol.name,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "  :${symbol.line + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
