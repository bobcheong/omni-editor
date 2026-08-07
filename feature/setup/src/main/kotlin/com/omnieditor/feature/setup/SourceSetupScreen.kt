package com.omnieditor.feature.setup

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omnieditor.core.model.CompareMode
import com.omnieditor.core.model.SourceRef

/**
 * Source setup screen S-02 (OE-SRC-1, OE-SRC-3).
 *
 * Two (or three) slots. Each slot shows a source-type chooser and,
 * once filled, the resolved label, size, modified date and detected type.
 * The compare mode is inferred from the pair and shown as an editable chip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceSetupScreen(
    leftSource: SourceRef? = null,
    rightSource: SourceRef? = null,
    thirdSource: SourceRef? = null,
    inferredMode: CompareMode = CompareMode.TEXT,
    rulesCount: Int = 0,
    onPickLeft: () -> Unit = {},
    onPickRight: () -> Unit = {},
    onPickThird: () -> Unit = {},
    onSwapSides: () -> Unit = {},
    onModeChanged: (CompareMode) -> Unit = {},
    onEditRules: () -> Unit = {},
    onCompare: () -> Unit = {},
    onNavigateBack: () -> Unit = {},
) {
    var showThirdSlot by remember { mutableStateOf(thirdSource != null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New compare") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Left slot
            SourceSlot(
                label = "LEFT",
                source = leftSource,
                onPick = onPickLeft,
            )

            // Swap button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(onClick = onSwapSides) {
                    Icon(Icons.Default.SwapHoriz, "Swap sides")
                }
            }

            // Right slot
            SourceSlot(
                label = "RIGHT",
                source = rightSource,
                onPick = onPickRight,
            )

            // Third slot (optional)
            if (showThirdSlot) {
                SourceSlot(
                    label = "BASE",
                    source = thirdSource,
                    onPick = onPickThird,
                )
            } else {
                OutlinedButton(onClick = { showThirdSlot = true }) {
                    Text("+ Add third file (3-way)")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Mode and rules
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = true,
                    onClick = { /* mode picker */ },
                    label = { Text("Mode: ${inferredMode.name}") },
                )
                FilterChip(
                    selected = rulesCount > 0,
                    onClick = onEditRules,
                    label = { Text("Rules: $rulesCount") },
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Compare button
            Button(
                onClick = onCompare,
                enabled = leftSource != null && rightSource != null,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text("Compare")
            }

            if (leftSource != null && rightSource == null) {
                Text(
                    "Choose a source for the right side to compare",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SourceSlot(
    label: String,
    source: SourceRef?,
    onPick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .clickable(onClick = onPick)
            .padding(16.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (source != null) {
            Text(
                text = source.label,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "${source.kind.name} · ${source.path ?: source.uriGrant ?: "snippet"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "+ Choose a source",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                for (kind in listOf("Device", "URL", "Paste")) {
                    FilterChip(
                        selected = false,
                        onClick = onPick,
                        label = { Text(kind, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
    }
}
