package com.omnieditor.feature.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

    Scaffold(
        topBar = {
            EditorTopBar(
                title = when (val state = uiState) {
                    is EditorUiState.Loaded -> {
                        val dirty = if (state.editorState.document.dirty) " ●" else ""
                        (fileName.ifBlank { "Editor" }) + dirty
                    }
                    else -> "Editor"
                },
                onNavigateBack = onNavigateBack,
                onFind = { showFind = !showFind },
                onCompareWith = onCompareWith,
                onSave = { viewModel.save { } },
                onUndo = { viewModel.undo() },
                onRedo = { viewModel.redo() },
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

            // Find/Replace bar
            if (showFind) {
                val state = uiState
                if (state is EditorUiState.Loaded) {
                    FindReplaceBar(
                        visible = true,
                        matchCount = 0,
                        currentMatch = 0,
                        onSearch = { },
                        onReplace = { },
                        onReplaceAll = { },
                        onPrevious = { },
                        onNext = { },
                        onClose = { showFind = false },
                        onOptionsChanged = { _, _, _ -> },
                    )
                }
            }

            when (val state = uiState) {
                is EditorUiState.Empty -> {}
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
) {
    var menuExpanded by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            IconButton(onClick = onFind) {
                Icon(Icons.Default.Search, contentDescription = "Find")
            }
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More")
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(text = { Text("Save") }, onClick = { menuExpanded = false; onSave() })
                DropdownMenuItem(text = { Text("Undo") }, onClick = { menuExpanded = false; onUndo() })
                DropdownMenuItem(text = { Text("Redo") }, onClick = { menuExpanded = false; onRedo() })
                DropdownMenuItem(text = { Text("Compare with…") }, onClick = { menuExpanded = false; onCompareWith() })
            }
        },
    )
}

private fun handleNavKey(state: EditorState, key: String) {
    when (key) {
        "LEFT" -> {
            if (state.caretColumn > 0) {
                state.moveCaret(state.caretLine, state.caretColumn - 1)
            } else if (state.caretLine > 0) {
                val prevLine = state.document.line(state.caretLine - 1)
                state.moveCaret(state.caretLine - 1, prevLine.length)
            }
        }
        "RIGHT" -> {
            val currentLine = state.document.line(state.caretLine)
            if (state.caretColumn < currentLine.length) {
                state.moveCaret(state.caretLine, state.caretColumn + 1)
            } else if (state.caretLine < state.lineCount - 1) {
                state.moveCaret(state.caretLine + 1, 0)
            }
        }
        "UP" -> if (state.caretLine > 0) state.moveCaret(state.caretLine - 1, state.caretColumn)
        "DOWN" -> if (state.caretLine < state.lineCount - 1) state.moveCaret(state.caretLine + 1, state.caretColumn)
        "HOME" -> state.moveCaret(state.caretLine, 0)
        "END" -> {
            val line = state.document.line(state.caretLine)
            state.moveCaret(state.caretLine, line.length)
        }
    }
}
