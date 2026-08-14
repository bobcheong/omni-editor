package com.omnieditor.feature.compare

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.omnieditor.core.model.RuleSet
import com.omnieditor.core.model.WhitespaceRule
import com.omnieditor.design.KeyboardShortcutsSheet
import kotlinx.coroutines.launch

/**
 * Current state of compare view-toggle settings, passed from the NavGraph
 * so the menu can show checkmarks for active settings.
 */
data class CompareSettingsState(
    val layoutMode: String = "unified",
    val syncScroll: Boolean = true,
    val granularity: String = "word",
)

/**
 * Grouped callbacks for compare view-toggle settings.
 */
data class CompareSettingsCallbacks(
    val onSetLayout: (String) -> Unit = {},
    val onToggleSyncScroll: () -> Unit = {},
    val onSetGranularity: (String) -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(
    state: CompareState?,
    ruleSet: RuleSet = RuleSet.DEFAULT,
    onRuleSetChanged: (RuleSet) -> Unit = {},
    leftLabel: String = "Left",
    rightLabel: String = "Right",
    onNavigateBack: () -> Unit = {},
    onSave: (() -> Unit)? = null,
    /** Open the left side in the viewer (read-only editor). */
    onOpenLeft: (() -> Unit)? = null,
    /** Open the right side in the viewer (read-only editor). */
    onOpenRight: (() -> Unit)? = null,
    /** Swap left and right sides and re-run the compare. */
    onFlipSides: (() -> Unit)? = null,
    /** Re-read both sides from disk and re-diff. */
    onRerunCompare: (() -> Unit)? = null,
    /** Generate and share a report of the current compare result. */
    onExportReport: (() -> Unit)? = null,
    /** Determinate progress [0f..1f] while compare is running; null when not loading. */
    compareProgress: Float? = null,
    /** Called when the user taps "Cancel" during compare. */
    onCancelCompare: (() -> Unit)? = null,
    /** Optional tab strip rendered above the compare content (R-34b). */
    tabStripContent: (@Composable () -> Unit)? = null,
    settingsState: CompareSettingsState = CompareSettingsState(),
    settingsCallbacks: CompareSettingsCallbacks = CompareSettingsCallbacks(),
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showActiveLineSheet by remember { mutableStateOf(false) }
    var tappedHunkIndex by remember { mutableStateOf<Int?>(null) }
    var showRuleSheet by remember { mutableStateOf(false) }
    var showAcceptAllConfirm by remember { mutableStateOf<MergeDirection?>(null) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showShortcutsSheet by remember { mutableStateOf(false) }

    // Find-within-compare state (OE-FND-1)
    var showFindBar by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findMatchIndex by remember { mutableIntStateOf(0) }
    // Match indices: rows where either left or right text contains the query (case-insensitive).
    val findMatches by remember(state, findQuery) {
        derivedStateOf {
            if (state == null || findQuery.isBlank()) emptyList()
            else {
                val q = findQuery.lowercase()
                state.buildAlignedRows().mapIndexedNotNull { idx, row ->
                    val leftText = row.left?.let { state.leftLines.getOrElse(it.toInt()) { "" } } ?: ""
                    val rightText = row.right?.let { state.rightLines.getOrElse(it.toInt()) { "" } } ?: ""
                    if (leftText.lowercase().contains(q) || rightText.lowercase().contains(q)) idx else null
                }
            }
        }
    }
    // Clamp findMatchIndex when matches change
    LaunchedEffect(findMatches) {
        if (findMatchIndex >= findMatches.size) findMatchIndex = 0
    }

    // Intercept back gesture when either side has unsaved merge changes.
    val isDirty = state?.leftDirty == true || state?.rightDirty == true
    BackHandler(enabled = isDirty) {
        showUnsavedDialog = true
    }

    if (showUnsavedDialog) {
        UnsavedChangesDialog(
            onSaveAndLeave = { showUnsavedDialog = false; onSave?.invoke(); onNavigateBack() },
            onDiscard = { showUnsavedDialog = false; onNavigateBack() },
            onCancel = { showUnsavedDialog = false },
        )
    }

    // ── Keyboard shortcuts sheet (R-37) ──
    if (showShortcutsSheet) {
        KeyboardShortcutsSheet(onDismiss = { showShortcutsSheet = false })
    }

    Scaffold(
        topBar = {
            CompareTopBar(
                leftLabel = leftLabel,
                rightLabel = rightLabel,
                diffCount = state?.diffCount ?: 0,
                currentDiff = state?.currentDiffIndex ?: 0,
                conflictCount = state?.conflictCount ?: 0,
                activeRuleCount = countActiveRules(ruleSet),
                leftDirty = state?.leftDirty ?: false,
                rightDirty = state?.rightDirty ?: false,
                canUndo = state?.canUndo ?: false,
                onNavigateBack = {
                    if (isDirty) showUnsavedDialog = true else onNavigateBack()
                },
                onRulesClick = { showRuleSheet = true },
                onSave = onSave,
                onRestoreOriginals = {
                    state?.undoAll()
                    scope.launch {
                        snackbarHostState.showSnackbar("Restored to original")
                    }
                },
                onOpenLeft = onOpenLeft,
                onOpenRight = onOpenRight,
                onFlipSides = onFlipSides,
                onRerunCompare = onRerunCompare,
                onExportReport = onExportReport,
                onKeyboardShortcuts = { showShortcutsSheet = true },
                settingsState = settingsState,
                settingsCallbacks = settingsCallbacks,
            )
        },
        bottomBar = {
            if (state != null) {
                DiffNavigationBar(
                    currentDiff = state.currentDiffIndex,
                    totalDiffs = state.diffCount,
                    conflictCount = state.conflictCount,
                    isHunkMerged = state.currentDiffIndex in state.mergedHunks,
                    onPrevious = { state.prevDiff() },
                    onNext = { state.nextDiff() },
                    onPreviousConflict = { state.prevConflict() },
                    onNextConflict = { state.nextConflict() },
                    onMergeLeftToRight = {
                        if (state.currentHunk != null) {
                            val applied = state.mergeHunk(state.currentDiffIndex, MergeDirection.LEFT_TO_RIGHT)
                            scope.launch {
                                snackbarHostState.showSnackbar(mergeSnackbarMessage(applied, state))
                            }
                        }
                    },
                    onMergeRightToLeft = {
                        if (state.currentHunk != null) {
                            val applied = state.mergeHunk(state.currentDiffIndex, MergeDirection.RIGHT_TO_LEFT)
                            scope.launch {
                                snackbarHostState.showSnackbar(mergeSnackbarMessage(applied, state))
                            }
                        }
                    },
                    onAcceptAllLeftToRight = {
                        showAcceptAllConfirm = MergeDirection.LEFT_TO_RIGHT
                    },
                    onAcceptAllRightToLeft = {
                        showAcceptAllConfirm = MergeDirection.RIGHT_TO_LEFT
                    },
                    onFind = { showFindBar = !showFindBar },
                    canMerge = state.canMerge,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = modifier.fillMaxSize().padding(padding)) {
            // R-34b: tab strip slot — rendered when tabs are present.
            tabStripContent?.invoke()

            CompareContent(
                state = state,
                ruleSet = ruleSet,
                compareProgress = compareProgress,
                onCancelCompare = onCancelCompare,
                showFindBar = showFindBar,
                findQuery = findQuery,
                findMatches = findMatches,
                findMatchIndex = findMatchIndex,
                showActiveLineSheet = showActiveLineSheet,
                settingsState = settingsState,
                tappedHunkIndex = tappedHunkIndex,
                onFindQueryChanged = { findQuery = it; findMatchIndex = 0 },
                onFindPrevious = {
                    if (findMatches.isNotEmpty()) {
                        findMatchIndex = (findMatchIndex - 1 + findMatches.size) % findMatches.size
                    }
                },
                onFindNext = {
                    if (findMatches.isNotEmpty()) {
                        findMatchIndex = (findMatchIndex + 1) % findMatches.size
                    }
                },
                onFindClose = { showFindBar = false; findQuery = "" },
                onDiffRowTapped = { hunkIndex -> tappedHunkIndex = hunkIndex; showActiveLineSheet = true },
                onActiveLineSheetDismiss = { showActiveLineSheet = false },
                onMergeHunk = { hunkIndex, direction ->
                    val applied = state?.mergeHunk(hunkIndex, direction)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (applied == true) state?.mergeMessage ?: "Merged" else "Already merged"
                        )
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }

        // Accept-all confirmation dialog
        showAcceptAllConfirm?.let { direction ->
            val dirLabel = when (direction) {
                MergeDirection.LEFT_TO_RIGHT -> "left to right"
                MergeDirection.RIGHT_TO_LEFT -> "right to left"
            }
            val hunkCount = state?.diffCount ?: 0
            AlertDialog(
                onDismissRequest = { showAcceptAllConfirm = null },
                title = { Text("Accept all changes?") },
                text = {
                    Text("Apply $hunkCount changes $dirLabel? This is one undo step.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        showAcceptAllConfirm = null
                        if (state != null) {
                            val count = state.acceptAll(direction)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    state.mergeMessage ?: "Applied $count changes"
                                )
                            }
                        }
                    }) {
                        Text("Apply")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAcceptAllConfirm = null }) {
                        Text("Cancel")
                    }
                },
            )
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

/**
 * Main content area of the compare screen (R-31).
 *
 * Extracted to keep [CompareScreen] within method-length limits.
 * Shows: filter bar, optional find bar, diff view or identical-state banner, status bar,
 * and the active-line sheet. Falls back to a progress/loading view when [state] is null.
 */
@Composable
private fun CompareContent(
    state: CompareState?,
    ruleSet: RuleSet,
    compareProgress: Float?,
    onCancelCompare: (() -> Unit)?,
    showFindBar: Boolean,
    findQuery: String,
    findMatches: List<Int>,
    findMatchIndex: Int,
    showActiveLineSheet: Boolean,
    settingsState: CompareSettingsState = CompareSettingsState(),
    tappedHunkIndex: Int?,
    onFindQueryChanged: (String) -> Unit,
    onFindPrevious: () -> Unit,
    onFindNext: () -> Unit,
    onFindClose: () -> Unit,
    onDiffRowTapped: (hunkIndex: Int) -> Unit,
    onActiveLineSheetDismiss: () -> Unit,
    onMergeHunk: (hunkIndex: Int, direction: MergeDirection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (state != null) {
            FilterBar(
                currentFilter = state.filterMode,
                onFilterChanged = { state.filterMode = it },
            )
            if (showFindBar) {
                CompareFindBar(
                    query = findQuery,
                    onQueryChanged = onFindQueryChanged,
                    matchIndex = findMatchIndex,
                    matchCount = findMatches.size,
                    onPrevious = onFindPrevious,
                    onNext = onFindNext,
                    onClose = onFindClose,
                )
            }
            if (state.diffCount == 0) {
                val ruleDesc = buildRuleDescription(ruleSet)
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (ruleDesc.isEmpty()) "Files are identical"
                               else "Files are identical ($ruleDesc)",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            } else {
                Row(modifier = Modifier.weight(1f)) {
                    AdaptiveDiffView(
                        state = state,
                        modifier = Modifier.weight(1f),
                        onDiffRowTapped = onDiffRowTapped,
                        findMatches = findMatches,
                        findMatchIndex = if (showFindBar && findMatches.isNotEmpty()) findMatchIndex else -1,
                        layoutMode = settingsState.layoutMode,
                    )
                    DiffMinimap(
                        hunks = state.result.hunks,
                        totalLines = maxOf(state.leftLines.size.toLong(), state.rightLines.size.toLong()),
                        visibleStart = state.firstVisibleLine,
                        visibleEnd = state.firstVisibleLine + state.visibleLineCount,
                        onSeek = { fraction ->
                            val totalLines = maxOf(state.leftLines.size, state.rightLines.size)
                            val targetLine = (fraction * totalLines).toLong()
                            state.firstVisibleLine = targetLine.coerceIn(0, totalLines.toLong() - 1)
                        },
                        modifier = Modifier.fillMaxHeight(),
                    )
                }
            }
            CompareStatusBar(state = state)
            if (showActiveLineSheet) {
                val sheetHunk = tappedHunkIndex?.let { state.result.hunks.getOrNull(it) }
                    ?: state.currentHunk
                val sheetHunkIndex = tappedHunkIndex ?: state.currentDiffIndex
                ActiveLineSheet(
                    visible = true,
                    hunk = sheetHunk,
                    leftLines = state.leftLines,
                    rightLines = state.rightLines,
                    onDismiss = onActiveLineSheetDismiss,
                    onMergeLeftToRight = if (state.canMerge) {
                        { onMergeHunk(sheetHunkIndex, MergeDirection.LEFT_TO_RIGHT) }
                    } else null,
                    onMergeRightToLeft = if (state.canMerge) {
                        { onMergeHunk(sheetHunkIndex, MergeDirection.RIGHT_TO_LEFT) }
                    } else null,
                    // Take-base: for CONFLICT hunks, take-left is used (base is not stored in
                    // CompareResult; a future P2 task will thread base lines through). (R-32)
                    onTakeBase = null,
                )
            }
        } else {
            CompareLoadingState(
                progress = compareProgress,
                onCancel = onCancelCompare,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Progress/loading state shown while the compare engine is running (R-31). */
@Composable
private fun CompareLoadingState(
    progress: Float?,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text("Comparing files\u2026", style = MaterialTheme.typography.bodyMedium)
        androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(0.6f).padding(vertical = 8.dp),
            )
            if (onCancel != null) {
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(0.6f).padding(vertical = 8.dp),
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
    conflictCount: Int,
    activeRuleCount: Int,
    leftDirty: Boolean,
    rightDirty: Boolean,
    canUndo: Boolean,
    onNavigateBack: () -> Unit,
    onRulesClick: () -> Unit,
    onSave: (() -> Unit)?,
    onRestoreOriginals: () -> Unit,
    onOpenLeft: (() -> Unit)?,
    onOpenRight: (() -> Unit)?,
    onFlipSides: (() -> Unit)?,
    onRerunCompare: (() -> Unit)?,
    onExportReport: (() -> Unit)?,
    onKeyboardShortcuts: () -> Unit = {},
    settingsState: CompareSettingsState = CompareSettingsState(),
    settingsCallbacks: CompareSettingsCallbacks = CompareSettingsCallbacks(),
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var granularityExpanded by remember { mutableStateOf(false) }

    val checkIcon: @Composable () -> Unit = {
        Icon(Icons.Default.Check, null, modifier = Modifier.padding(0.dp))
    }

    val dirtyIndicator = buildString {
        if (leftDirty || rightDirty) {
            append(" (")
            if (leftDirty) append("L")
            if (leftDirty && rightDirty) append(",")
            if (rightDirty) append("R")
            append(" modified)")
        }
    }

    TopAppBar(
        title = { Text("$leftLabel \u21C4 $rightLabel$dirtyIndicator", maxLines = 1) },
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
            // Conflict count chip — only shown when conflicts are present (R-32)
            if (conflictCount > 0) {
                val compareColors = com.omnieditor.design.LocalCompareColors.current
                AssistChip(
                    onClick = { /* display-only badge — no tap action */ },
                    label = {
                        Text(
                            "$conflictCount conflict${if (conflictCount == 1) "" else "s"}",
                            color = compareColors.conflictFg,
                        )
                    },
                )
            }
            if (diffCount > 0) {
                Text("${currentDiff + 1}/$diffCount")
            }
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, "More")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                // ── View section (quick toggles) ──
                DropdownMenuItem(
                    text = { Text("Layout: Unified") },
                    onClick = { menuExpanded = false; settingsCallbacks.onSetLayout("unified") },
                    leadingIcon = if (settingsState.layoutMode == "unified") { checkIcon } else null,
                )
                DropdownMenuItem(
                    text = { Text("Layout: Split") },
                    onClick = { menuExpanded = false; settingsCallbacks.onSetLayout("split") },
                    leadingIcon = if (settingsState.layoutMode == "split") { checkIcon } else null,
                )
                DropdownMenuItem(
                    text = { Text("Sync scroll") },
                    onClick = { menuExpanded = false; settingsCallbacks.onToggleSyncScroll() },
                    leadingIcon = if (settingsState.syncScroll) { checkIcon } else null,
                )
                DropdownMenuItem(text = { Text("Granularity ▸") }, onClick = {
                    menuExpanded = false; granularityExpanded = true
                })
                HorizontalDivider()
                // ── Actions section ──
                if (onOpenLeft != null) {
                    DropdownMenuItem(
                        text = { Text("Open left in viewer") },
                        onClick = { menuExpanded = false; onOpenLeft() },
                    )
                }
                if (onOpenRight != null) {
                    DropdownMenuItem(
                        text = { Text("Open right in viewer") },
                        onClick = { menuExpanded = false; onOpenRight() },
                    )
                }
                if (onFlipSides != null) {
                    DropdownMenuItem(
                        text = { Text("Flip sides") },
                        onClick = { menuExpanded = false; onFlipSides() },
                    )
                }
                if (onRerunCompare != null) {
                    DropdownMenuItem(
                        text = { Text("Re-run compare") },
                        onClick = { menuExpanded = false; onRerunCompare() },
                    )
                }
                if (onExportReport != null) {
                    DropdownMenuItem(
                        text = { Text("Export report") },
                        onClick = { menuExpanded = false; onExportReport() },
                    )
                }
                if (onSave != null && (leftDirty || rightDirty)) {
                    DropdownMenuItem(
                        text = { Text("Save merged") },
                        onClick = { menuExpanded = false; onSave() },
                    )
                }
                if (canUndo) {
                    DropdownMenuItem(
                        text = { Text("Restore originals") },
                        onClick = { menuExpanded = false; onRestoreOriginals() },
                    )
                }
                HorizontalDivider()
                // ── Navigation section ──
                DropdownMenuItem(
                    text = { Text("Rules") },
                    onClick = { menuExpanded = false; onRulesClick() },
                )
                HorizontalDivider()
                // ── Footer ──
                DropdownMenuItem(
                    text = { Text("Keyboard shortcuts") },
                    onClick = { menuExpanded = false; onKeyboardShortcuts() },
                )
                DropdownMenuItem(
                    text = { Text("Settings") },
                    onClick = { menuExpanded = false; settingsCallbacks.onOpenSettings() },
                )
            }
            // Granularity submenu
            DropdownMenu(expanded = granularityExpanded, onDismissRequest = { granularityExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Line") },
                    onClick = { granularityExpanded = false; settingsCallbacks.onSetGranularity("line") },
                    leadingIcon = if (settingsState.granularity == "line") { checkIcon } else null,
                )
                DropdownMenuItem(
                    text = { Text("Word") },
                    onClick = { granularityExpanded = false; settingsCallbacks.onSetGranularity("word") },
                    leadingIcon = if (settingsState.granularity == "word") { checkIcon } else null,
                )
                DropdownMenuItem(
                    text = { Text("Character") },
                    onClick = { granularityExpanded = false; settingsCallbacks.onSetGranularity("char") },
                    leadingIcon = if (settingsState.granularity == "char") { checkIcon } else null,
                )
            }
        },
    )
}

/**
 * Dialog shown when the user attempts to leave compare with unsaved merge changes (R-28).
 * Offers Save, Discard, and Cancel actions — same pattern as the editor's dirty prompt.
 */
@Composable
private fun UnsavedChangesDialog(
    onSaveAndLeave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Unsaved changes") },
        text = { Text("Save merged changes before leaving?") },
        confirmButton = {
            TextButton(onClick = onSaveAndLeave) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text("Discard") }
                TextButton(onClick = onCancel) { Text("Cancel") }
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

/**
 * Find bar for compare — searches both left and right content (OE-FND-1).
 */
@Composable
private fun CompareFindBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    matchIndex: Int,
    matchCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            placeholder = { Text("Find in compare…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onNext() }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = if (matchCount == 0) "No matches"
                   else "${matchIndex + 1}/$matchCount",
            style = MaterialTheme.typography.bodySmall,
        )
        IconButton(onClick = onPrevious, enabled = matchCount > 0) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous match")
        }
        IconButton(onClick = onNext, enabled = matchCount > 0) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next match")
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Close find bar")
        }
    }
}

/**
 * Build a short human-readable description of active compare rules for the "identical" banner.
 * Returns empty string when no rules are active.
 */
private fun buildRuleDescription(ruleSet: RuleSet): String {
    val parts = mutableListOf<String>()
    if (ruleSet.ignoreCase) parts.add("case")
    if (ruleSet.whitespace != com.omnieditor.core.model.WhitespaceRule.NONE) parts.add("whitespace")
    if (ruleSet.ignoreBlankLines) parts.add("blank lines")
    if (ruleSet.linePatterns.isNotEmpty()) parts.add("line patterns")
    if (ruleSet.betweenMarkers.isNotEmpty()) parts.add("markers")
    if (ruleSet.headSkip > 0 || ruleSet.tailSkip > 0) parts.add("skip")
    return if (parts.isEmpty()) "" else "ignoring ${parts.joinToString(", ")}"
}

/** Returns the appropriate snackbar message after a merge operation. */
private fun mergeSnackbarMessage(applied: Boolean, state: CompareState): String =
    if (applied) state.mergeMessage ?: "Merged" else "Already merged"
