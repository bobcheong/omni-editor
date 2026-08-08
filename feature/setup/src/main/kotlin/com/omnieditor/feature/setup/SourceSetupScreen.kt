package com.omnieditor.feature.setup

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omnieditor.core.model.CompareMode
import com.omnieditor.core.model.SourceRef

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
        bottomBar = {
            // Compare button pinned to bottom — never obscured
            Column(modifier = Modifier.padding(16.dp)) {
                Button(
                    onClick = onCompare,
                    enabled = leftSource != null && rightSource != null,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text("Compare")
                }
                if (leftSource != null && rightSource == null) {
                    Text(
                        "Choose a source for the right side",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SourceSlot("LEFT", leftSource, onPickLeft)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = onSwapSides) {
                    Icon(Icons.Default.SwapHoriz, "Swap sides")
                }
            }

            SourceSlot("RIGHT", rightSource, onPickRight)

            if (showThirdSlot) {
                SourceSlot("BASE", thirdSource, onPickThird)
            } else {
                OutlinedButton(onClick = { showThirdSlot = true }) {
                    Text("+ Add third file (3-way)")
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(true, onClick = {}, label = { Text("Mode: ${inferredMode.name}") })
                FilterChip(rulesCount > 0, onClick = onEditRules, label = { Text("Rules: $rulesCount") })
            }
        }
    }
}

@Composable
private fun SourceSlot(label: String, source: SourceRef?, onPick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .clickable(onClick = onPick)
            .padding(16.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (source != null) {
            Text(source.label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                source.kind.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("+ Choose a source", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
