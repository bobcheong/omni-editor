package com.omnieditor.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    fileName: String = "",
    tabs: List<TabInfo> = emptyList(),
    selectedTabId: String? = null,
    onTabSelected: (String) -> Unit = {},
    onTabClosed: (String) -> Unit = {},
    onNewTab: () -> Unit = {},
    onNavigateBack: () -> Unit = {},
    onCompareWith: () -> Unit = {},
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showFind by remember { mutableStateOf(false) }
    var lastSearchQuery by remember { mutableStateOf("") }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    val isDirty = (uiState as? EditorUiState.Loaded)?.editorState?.document?.dirty == true

    // Intercept the system back gesture/button when there are unsaved changes.
    BackHandler(enabled = isDirty) {
        showUnsavedDialog = true
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("Unsaved changes") },
            text = { Text("Do you want to save your changes before leaving?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.save()
                    showUnsavedDialog = false
                    onNavigateBack()
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        onNavigateBack()
                    }) { Text("Discard") }
                    TextButton(onClick = {
                        showUnsavedDialog = false
                    }) { Text("Cancel") }
                }
            },
        )
    }

    Scaffold(
        topBar = {
            EditorTopBar(
                title = when (val state = uiState) {
                    is EditorUiState.Loaded -> {
                        val dirty = if (state.editorState.document.dirty) " ●" else ""
                        (fileName.ifBlank { "Editor" }) + dirty
                    }
                    is EditorUiState.Saving -> (fileName.ifBlank { "Editor" }) + " — saving…"
                    else -> "Editor"
                },
                onNavigateBack = onNavigateBack,
                onFind = { showFind = !showFind },
                onCompareWith = onCompareWith,
                onSave = { viewModel.save() },
                onUndo = { viewModel.undo() },
                onRedo = { viewModel.redo() },
                onSortLines = { viewModel.sortLines() },
                onDeduplicate = { viewModel.deduplicateLines() },
                onTrimTrailing = { viewModel.trimTrailing() },
                onUpperCase = { viewModel.toUpperCase() },
                onLowerCase = { viewModel.toLowerCase() },
                onReverseLines = { viewModel.reverseLines() },
                onRemoveBlankLines = { viewModel.removeBlankLines() },
                onTabsToSpaces = { viewModel.tabsToSpaces() },
                onSpacesToTabs = { viewModel.spacesToTabs() },
            )
        },
        bottomBar = {
            val state = uiState
            if (state is EditorUiState.Loaded) {
                Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
                    StatusStrip(
                        state = state.editorState,
                        encoding = "UTF-8",
                        lineEnding = "LF",
                    )
                    ProgrammerKeyRow(
                        onKey = { key ->
                            when (key) {
                                "LEFT", "RIGHT", "UP", "DOWN", "HOME", "END" ->
                                    handleNavKey(state.editorState, key)
                                else -> state.editorState.insertAtCaret(key)
                            }
                        },
                        onUndo = { viewModel.undo() },
                        onRedo = { viewModel.redo() },
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (tabs.isNotEmpty()) {
                TabStrip(tabs, selectedTabId, onTabSelected, onTabClosed, onNewTab)
            }

            // Find/Replace bar — connected to ViewModel
            if (showFind) {
                FindReplaceBar(
                    visible = true,
                    matchCount = viewModel.findMatches.size,
                    currentMatch = viewModel.currentMatchIndex,
                    onSearch = { query ->
                        lastSearchQuery = query
                        viewModel.search(query)
                    },
                    onReplace = { replacement -> viewModel.replaceOne(replacement) },
                    onReplaceAll = { replacement -> viewModel.replaceAll(lastSearchQuery, replacement) },
                    onPrevious = { viewModel.findPrev() },
                    onNext = { viewModel.findNext() },
                    onClose = { showFind = false },
                    onOptionsChanged = { cs, ww, rx -> viewModel.updateFindOptions(cs, ww, rx) },
                )
            }

            when (val state = uiState) {
                is EditorUiState.Empty -> {}
                is EditorUiState.Saving -> {
                    Text(text = "Saving…", modifier = Modifier.fillMaxSize().padding(16.dp))
                }
                is EditorUiState.Error -> {
                    Text(text = state.message, modifier = Modifier.fillMaxSize())
                }
                is EditorUiState.Loaded -> {
                    EditorContent(
                        state = state.editorState,
                        modifier = Modifier.weight(1f),
                        fileName = fileName,
                    )
                }
                is EditorUiState.OverThreshold -> {
                    OverThresholdScreen(
                        fileName = state.fileName,
                        fileBytes = state.fileBytes,
                        limitBytes = state.limitBytes,
                        onNavigateBack = onNavigateBack,
                    )
                }
                // RecoveryAvailable: detection + recovery UI deferred to Journal integration.
                // The state variant exists so the sealed interface is complete (R-21 stub).
                is EditorUiState.RecoveryAvailable -> {}
                // R-22: file changed on disk since it was opened.
                is EditorUiState.ExternallyChanged -> {
                    ExternallyChangedBanner(
                        onReload = { viewModel.reloadFromDisk() },
                        onKeepMine = { viewModel.dismissExternalChange() },
                    )
                    // Show whatever editor content is currently loaded beneath the banner
                    val editorContent = viewModel.lastLoadedState
                    if (editorContent != null) {
                        EditorContent(
                            state = editorContent,
                            modifier = Modifier.weight(1f),
                            fileName = fileName,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(
    title: String,
    onNavigateBack: () -> Unit,
    onFind: () -> Unit,
    onCompareWith: () -> Unit,
    onSave: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSortLines: () -> Unit,
    onDeduplicate: () -> Unit,
    onTrimTrailing: () -> Unit,
    onUpperCase: () -> Unit,
    onLowerCase: () -> Unit,
    onReverseLines: () -> Unit,
    onRemoveBlankLines: () -> Unit,
    onTabsToSpaces: () -> Unit,
    onSpacesToTabs: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var textToolsExpanded by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
        actions = {
            IconButton(onClick = onFind) {
                Icon(Icons.Default.Search, "Find & Replace")
            }
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, "More")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text("Save") }, onClick = { menuExpanded = false; onSave() })
                DropdownMenuItem(text = { Text("Undo") }, onClick = { menuExpanded = false; onUndo() })
                DropdownMenuItem(text = { Text("Redo") }, onClick = { menuExpanded = false; onRedo() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Text tools ▸") }, onClick = {
                    menuExpanded = false; textToolsExpanded = true
                })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Compare with…") }, onClick = { menuExpanded = false; onCompareWith() })
            }
            DropdownMenu(expanded = textToolsExpanded, onDismissRequest = { textToolsExpanded = false }) {
                DropdownMenuItem(text = { Text("Sort lines") }, onClick = { textToolsExpanded = false; onSortLines() })
                DropdownMenuItem(text = { Text("Remove duplicates") }, onClick = { textToolsExpanded = false; onDeduplicate() })
                DropdownMenuItem(text = { Text("Trim trailing spaces") }, onClick = { textToolsExpanded = false; onTrimTrailing() })
                DropdownMenuItem(text = { Text("UPPERCASE") }, onClick = { textToolsExpanded = false; onUpperCase() })
                DropdownMenuItem(text = { Text("lowercase") }, onClick = { textToolsExpanded = false; onLowerCase() })
                DropdownMenuItem(text = { Text("Reverse lines") }, onClick = { textToolsExpanded = false; onReverseLines() })
                DropdownMenuItem(text = { Text("Remove blank lines") }, onClick = { textToolsExpanded = false; onRemoveBlankLines() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Tabs → Spaces") }, onClick = { textToolsExpanded = false; onTabsToSpaces() })
                DropdownMenuItem(text = { Text("Spaces → Tabs") }, onClick = { textToolsExpanded = false; onSpacesToTabs() })
            }
        },
    )
}

@Composable
private fun OverThresholdScreen(
    fileName: String,
    fileBytes: Long,
    limitBytes: Long,
    onNavigateBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "File too large",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "$fileName is ${formatBytes(fileBytes)}, which exceeds the ${formatBytes(limitBytes)} limit.",
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onNavigateBack) {
            Text("Go back")
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) "%.1f MB".format(mb) else "%.0f KB".format(bytes / 1024.0)
}

@Composable
private fun ExternallyChangedBanner(
    onReload: () -> Unit,
    onKeepMine: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "File changed on disk",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onReload) {
                Text("Reload")
            }
            TextButton(onClick = onKeepMine) {
                Text("Keep mine")
            }
        }
    }
}

private fun handleNavKey(state: EditorState, key: String) {
    when (key) {
        "LEFT" -> {
            if (state.caretColumn > 0) state.moveCaret(state.caretLine, state.caretColumn - 1)
            else if (state.caretLine > 0) {
                val prev = state.document.line(state.caretLine - 1)
                state.moveCaret(state.caretLine - 1, prev.length)
            }
        }
        "RIGHT" -> {
            val cur = state.document.line(state.caretLine)
            if (state.caretColumn < cur.length) state.moveCaret(state.caretLine, state.caretColumn + 1)
            else if (state.caretLine < state.lineCount - 1) state.moveCaret(state.caretLine + 1, 0)
        }
        "UP" -> if (state.caretLine > 0) state.moveCaret(state.caretLine - 1, state.caretColumn)
        "DOWN" -> if (state.caretLine < state.lineCount - 1) state.moveCaret(state.caretLine + 1, state.caretColumn)
        "HOME" -> state.moveCaret(state.caretLine, 0)
        "END" -> state.moveCaret(state.caretLine, state.document.line(state.caretLine).length)
    }
}
