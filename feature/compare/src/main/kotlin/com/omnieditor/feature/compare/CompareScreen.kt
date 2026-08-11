package com.omnieditor.feature.compare

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.omnieditor.core.diff.MergeEngine
import com.omnieditor.core.model.RuleSet
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(
    state: CompareState?,
    ruleSet: RuleSet = RuleSet.DEFAULT,
    onRuleSetChanged: (RuleSet) -> Unit = {},
    leftLabel: String = "Left",
    rightLabel: String = "Right",
    onNavigateBack: () -> Unit = {},
    onMerge: () -> Unit = {},
    onFind: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showActiveLineSheet by remember { mutableStateOf(false) }
    var showRuleSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CompareTopBar(
                leftLabel = leftLabel,
                rightLabel = rightLabel,
                diffCount = state?.diffCount ?: 0,
                currentDiff = state?.currentDiffIndex ?: 0,
                activeRuleCount = countActiveRules(ruleSet),
                onNavigateBack = onNavigateBack,
                onRulesClick = { showRuleSheet = true },
            )
        },
        bottomBar = {
            if (state != null) {
                DiffNavigationBar(
                    currentDiff = state.currentDiffIndex,
                    totalDiffs = state.diffCount,
                    onPrevious = { state.prevDiff() },
                    onNext = { state.nextDiff() },
                    onMerge = {
                        // Merge current hunk left → right
                        if (state.currentHunk != null) {
                            val action = MergeEngine.mergeHunk(
                                state.currentDiffIndex,
                                state.result,
                                state.leftLines,
                                state.rightLines,
                                MergeEngine.Direction.LEFT_TO_RIGHT,
                            )
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "Merged hunk ${state.currentDiffIndex + 1}: " +
                                        "${action.replacementLines.size} lines copied"
                                )
                            }
                        }
                    },
                    onFind = onFind,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = modifier.fillMaxSize().padding(padding)) {
            if (state != null) {
                // Filter bar
                FilterBar(
                    currentFilter = state.filterMode,
                    onFilterChanged = { state.filterMode = it },
                )

                // Content + minimap
                Row(modifier = Modifier.weight(1f)) {
                    // Diff view
                    AdaptiveDiffView(
                        state = state,
                        modifier = Modifier.weight(1f),
                    )

                    // Minimap rail
                    DiffMinimap(
                        hunks = state.result.hunks,
                        totalLines = maxOf(
                            state.leftLines.size.toLong(),
                            state.rightLines.size.toLong(),
                        ),
                        visibleStart = state.firstVisibleLine,
                        visibleEnd = state.firstVisibleLine + 30,
                        onSeek = { fraction ->
                            val totalLines = maxOf(state.leftLines.size, state.rightLines.size)
                            val targetLine = (fraction * totalLines).toLong()
                            state.firstVisibleLine = targetLine.coerceIn(0, totalLines.toLong() - 1)
                        },
                        modifier = Modifier.fillMaxHeight(),
                    )
                }

                // Status bar
                CompareStatusBar(state = state)

                // Active line sheet
                if (showActiveLineSheet) {
                    ActiveLineSheet(
                        visible = true,
                        hunk = state.currentHunk,
                        leftLines = state.leftLines,
                        rightLines = state.rightLines,
                        onDismiss = { showActiveLineSheet = false },
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Comparing files…")
                }
            }
        }

        // Rule set bottom sheet
        if (showRuleSheet) {
            RuleSetSheet(
                ruleSet = ruleSet,
                onRuleSetChanged = onRuleSetChanged,
                onDismiss = { showRuleSheet = false },
            )
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
    activeRuleCount: Int,
    onNavigateBack: () -> Unit,
    onRulesClick: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text("$leftLabel ⇄ $rightLabel", maxLines = 1) },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
        actions = {
            // Active rules chip
            AssistChip(
                onClick = onRulesClick,
                label = {
                    Text(if (activeRuleCount > 0) "$activeRuleCount rules" else "Rules")
                },
            )
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
