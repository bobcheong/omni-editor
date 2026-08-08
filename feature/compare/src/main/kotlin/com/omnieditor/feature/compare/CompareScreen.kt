package com.omnieditor.feature.compare

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Text compare screen (S-03) — unified layout.
 *
 * App bar shows both source labels, difference counter, layout toggle.
 * Content is the unified diff view from [UnifiedDiffView].
 * Bottom bar has prev/next/merge/find navigation.
 *
 * States (from spec S-03):
 * - Comparing (progress, cancel)
 * - Identical (explicit success state naming rules)
 * - Differences only in ignored content
 * - One side empty
 * - Binary detected
 * - Encoding mismatch
 * - Block-mode notice
 * - Stale result
 * - Unsaved merge edits
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(
    state: CompareState?,
    leftLabel: String = "Left",
    rightLabel: String = "Right",
    onNavigateBack: () -> Unit = {},
    onMerge: () -> Unit = {},
    onFind: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            CompareTopBar(
                leftLabel = leftLabel,
                rightLabel = rightLabel,
                diffCount = state?.diffCount ?: 0,
                currentDiff = state?.currentDiffIndex ?: 0,
                onNavigateBack = onNavigateBack,
            )
        },
        bottomBar = {
            if (state != null) {
                DiffNavigationBar(
                    currentDiff = state.currentDiffIndex,
                    totalDiffs = state.diffCount,
                    onPrevious = { state.prevDiff() },
                    onNext = { state.nextDiff() },
                    onMerge = onMerge,
                    onFind = onFind,
                )
            }
        },
    ) { padding ->
        Column(modifier = modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            if (state != null) {
                // Filter segmented control
                FilterBar(
                    currentFilter = state.filterMode,
                    onFilterChanged = { state.filterMode = it },
                )

                // Adaptive diff view: unified on phone, split on tablet/landscape
                AdaptiveDiffView(
                    state = state,
                    modifier = Modifier.weight(1f),
                    contentPadding = padding,
                )

                // Status readout
                CompareStatusBar(state = state)
            } else {
                // Empty / loading state
                Text("No compare result", modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompareTopBar(
    leftLabel: String,
    rightLabel: String,
    diffCount: Int,
    currentDiff: Int,
    onNavigateBack: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Text("$leftLabel ⇄ $rightLabel")
        },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
        actions = {
            if (diffCount > 0) {
                Text("${currentDiff + 1}/$diffCount")
            }
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, "More")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text("Open in editor") }, onClick = { menuExpanded = false })
                DropdownMenuItem(text = { Text("Flip sides") }, onClick = { menuExpanded = false })
                DropdownMenuItem(text = { Text("Re-run compare") }, onClick = { menuExpanded = false })
                DropdownMenuItem(text = { Text("Export report") }, onClick = { menuExpanded = false })
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(
    currentFilter: FilterMode,
    onFilterChanged: (FilterMode) -> Unit,
) {
    SingleChoiceSegmentedButtonRow {
        FilterMode.entries.forEachIndexed { index, filter ->
            SegmentedButton(
                selected = filter == currentFilter,
                onClick = { onFilterChanged(filter) },
                shape = SegmentedButtonDefaults.itemShape(index, FilterMode.entries.size),
            ) {
                Text(
                    when (filter) {
                        FilterMode.ALL -> "All"
                        FilterMode.DIFFS_ONLY -> "Diffs"
                        FilterMode.MATCHES_ONLY -> "Matches"
                    }
                )
            }
        }
    }
}
