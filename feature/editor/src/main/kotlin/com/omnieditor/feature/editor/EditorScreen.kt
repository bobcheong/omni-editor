package com.omnieditor.feature.editor

import androidx.activity.compose.BackHandler
import com.omnieditor.core.io.PieceTableDocument
import com.omnieditor.core.model.DisplaySettings
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.omnieditor.design.KeyboardShortcutsSheet

/**
 * Current state of editor view-toggle settings, passed down from the NavGraph
 * so the menu can show checkmarks for active settings.
 */
data class EditorSettingsState(
    val wordWrapEnabled: Boolean = false,
    val showLineNumbers: Boolean = true,
    val showWhitespace: Boolean = false,
    val fontSize: Int = 14,
)

/**
 * Grouped callbacks for the editor top-bar menu, keeping [EditorTopBar]'s parameter count
 * within the detekt LongParameterList threshold.
 */
data class EditorMenuCallbacks(
    val onSave: () -> Unit = {},
    val onUndo: () -> Unit = {},
    val onRedo: () -> Unit = {},
    val onGoToLine: () -> Unit = {},
    val onSortLines: () -> Unit = {},
    val onDeduplicate: () -> Unit = {},
    val onTrimTrailing: () -> Unit = {},
    val onReverseLines: () -> Unit = {},
    val onRemoveBlankLines: () -> Unit = {},
    val onTabsToSpaces: () -> Unit = {},
    val onSpacesToTabs: () -> Unit = {},
    val onJoinLines: () -> Unit = {},
    val onToUpperCase: () -> Unit = {},
    val onToLowerCase: () -> Unit = {},
    val onToTitleCase: () -> Unit = {},
    val onToggleBookmark: () -> Unit = {},
    val onNextBookmark: () -> Unit = {},
    val onPrevBookmark: () -> Unit = {},
    val onToggleColumnSelect: () -> Unit = {},
    val onConvertLineEndingCRLF: () -> Unit = {},
    val onConvertLineEndingLF: () -> Unit = {},
    val onConvertLineEndingCR: () -> Unit = {},
    // Edit operations (#12)
    val onDeleteLine: () -> Unit = {},
    val onDuplicateLine: () -> Unit = {},
    val onInsertLineAbove: () -> Unit = {},
    val onInsertLineBelow: () -> Unit = {},
    val onMoveLineUp: () -> Unit = {},
    val onMoveLineDown: () -> Unit = {},
    val onIndent: () -> Unit = {},
    val onOutdent: () -> Unit = {},
    val onToggleComment: () -> Unit = {},
    /** Open the keyboard shortcuts reference sheet (R-37). */
    val onKeyboardShortcuts: () -> Unit = {},
    // View-toggle callbacks
    val onToggleWordWrap: () -> Unit = {},
    val onToggleLineNumbers: () -> Unit = {},
    val onToggleWhitespace: () -> Unit = {},
    val onIncreaseFontSize: () -> Unit = {},
    val onDecreaseFontSize: () -> Unit = {},
    /** Navigate to the Settings screen. */
    val onOpenSettings: () -> Unit = {},
)

@Suppress("LongMethod")
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
    settingsState: EditorSettingsState = EditorSettingsState(),
    onToggleWordWrap: () -> Unit = {},
    onToggleLineNumbers: () -> Unit = {},
    onToggleWhitespace: () -> Unit = {},
    onIncreaseFontSize: () -> Unit = {},
    onDecreaseFontSize: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val pendingTool by viewModel.pendingTool.collectAsState()
    var showFind by remember { mutableStateOf(false) }
    var lastSearchQuery by remember { mutableStateOf("") }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showGoToLineDialog by remember { mutableStateOf(false) }
    var showShortcutsSheet by remember { mutableStateOf(false) }

    val isDirty = viewModel.isDirty

    // Intercept the system back gesture/button when there are unsaved changes.
    BackHandler(enabled = isDirty) {
        showUnsavedDialog = true
    }

    // ── Unsaved-changes dialog ──
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

    // ── Go-to-line dialog ──
    if (showGoToLineDialog) {
        GoToLineDialog(
            onDismiss = { showGoToLineDialog = false },
            onConfirm = { line ->
                viewModel.goToLine(line - 1L)   // UI is 1-based; ViewModel is 0-based
                showGoToLineDialog = false
            },
        )
    }

    // ── Keyboard shortcuts sheet (R-37) ──
    if (showShortcutsSheet) {
        KeyboardShortcutsSheet(onDismiss = { showShortcutsSheet = false })
    }

    // ── Destructive-tool confirmation dialog (spec §2.3) ──
    pendingTool?.let { tool ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelPendingTool() },
            title = { Text(tool.label) },
            text = {
                Text("This will change ${tool.changedLines} of ${tool.totalLines} lines. Continue?")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmPendingTool() }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelPendingTool() }) { Text("Cancel") }
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
                settingsState = settingsState,
                callbacks = EditorMenuCallbacks(
                    onSave = { viewModel.save() },
                    onUndo = { viewModel.undo() },
                    onRedo = { viewModel.redo() },
                    onGoToLine = { showGoToLineDialog = true },
                    onSortLines = { viewModel.sortLines() },
                    onDeduplicate = { viewModel.deduplicateLines() },
                    onTrimTrailing = { viewModel.trimTrailing() },
                    onReverseLines = { viewModel.reverseLines() },
                    onRemoveBlankLines = { viewModel.removeBlankLines() },
                    onTabsToSpaces = { viewModel.tabsToSpaces() },
                    onSpacesToTabs = { viewModel.spacesToTabs() },
                    onJoinLines = { viewModel.joinLines() },
                    onToUpperCase = { viewModel.toUpperCase() },
                    onToLowerCase = { viewModel.toLowerCase() },
                    onToTitleCase = { viewModel.toTitleCase() },
                    onToggleBookmark = { viewModel.toggleBookmark() },
                    onNextBookmark = { viewModel.nextBookmark() },
                    onPrevBookmark = { viewModel.prevBookmark() },
                    onToggleColumnSelect = { viewModel.toggleColumnSelectMode() },
                    onConvertLineEndingCRLF = { viewModel.convertLineEnding("\r\n") },
                    onConvertLineEndingLF = { viewModel.convertLineEnding("\n") },
                    onConvertLineEndingCR = { viewModel.convertLineEnding("\r") },
                    onDeleteLine = { viewModel.lastLoadedState?.deleteLine() },
                    onDuplicateLine = { viewModel.lastLoadedState?.duplicateLine() },
                    onInsertLineAbove = { viewModel.lastLoadedState?.insertLineAbove() },
                    onInsertLineBelow = { viewModel.lastLoadedState?.insertLineBelow() },
                    onMoveLineUp = { viewModel.lastLoadedState?.moveLineUp() },
                    onMoveLineDown = { viewModel.lastLoadedState?.moveLineDown() },
                    onIndent = { viewModel.lastLoadedState?.indent() },
                    onOutdent = { viewModel.lastLoadedState?.outdent() },
                    onToggleComment = {
                        val ext = fileName.substringAfterLast('.', "")
                        viewModel.lastLoadedState?.toggleComment(commentPrefixForExtension(ext))
                    },
                    onKeyboardShortcuts = { showShortcutsSheet = true },
                    onToggleWordWrap = onToggleWordWrap,
                    onToggleLineNumbers = onToggleLineNumbers,
                    onToggleWhitespace = onToggleWhitespace,
                    onIncreaseFontSize = onIncreaseFontSize,
                    onDecreaseFontSize = onDecreaseFontSize,
                    onOpenSettings = onOpenSettings,
                ),
            )
        },
        bottomBar = {
            val state = uiState
            if (state is EditorUiState.Loaded) {
                Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
                    EditorTouchBar(
                        canUndo = (state.editorState.document as? PieceTableDocument)?.undoCount?.let { it > 0 } ?: false,
                        canRedo = (state.editorState.document as? PieceTableDocument)?.redoCount?.let { it > 0 } ?: false,
                        hasSelection = state.editorState.hasSelection,
                        onCut = {
                            if (state.editorState.hasSelection) {
                                clipboardManager.setText(
                                    androidx.compose.ui.text.AnnotatedString(state.editorState.selectedText())
                                )
                                state.editorState.deleteSelection()
                            }
                        },
                        onCopy = {
                            if (state.editorState.hasSelection) {
                                clipboardManager.setText(
                                    androidx.compose.ui.text.AnnotatedString(state.editorState.selectedText())
                                )
                            }
                        },
                        onPaste = {
                            val clip = clipboardManager.getText()?.text
                            if (!clip.isNullOrEmpty()) {
                                state.editorState.insertAtCaret(clip)
                            }
                        },
                        onSelectAll = {
                            val lastLine = state.editorState.lineCount - 1
                            val lastLineText = state.editorState.document.line(lastLine).toString()
                            state.editorState.setSelection(0L, 0, lastLine, lastLineText.length)
                        },
                        onUndo = { viewModel.undo() },
                        onRedo = { viewModel.redo() },
                        onDeleteLine = { state.editorState.deleteLine() },
                        onDuplicateLine = { state.editorState.duplicateLine() },
                        onInsertLineAbove = { state.editorState.insertLineAbove() },
                        onInsertLineBelow = { state.editorState.insertLineBelow() },
                        onMoveLineUp = { state.editorState.moveLineUp() },
                        onMoveLineDown = { state.editorState.moveLineDown() },
                        onIndent = { state.editorState.indent() },
                        onOutdent = { state.editorState.outdent() },
                        onToggleComment = {
                            val ext = fileName.substringAfterLast('.', "")
                            state.editorState.toggleComment(commentPrefixForExtension(ext))
                        },
                        onFind = { showFind = !showFind },
                    )
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
                    val focusRequester = remember { FocusRequester() }
                    val keyboard = LocalSoftwareKeyboardController.current
                    ImeHandler(
                        state = state.editorState,
                        focusRequester = focusRequester,
                        onSave = { viewModel.save() },
                        onUndo = { viewModel.undo() },
                        onRedo = { viewModel.redo() },
                        modifier = Modifier.weight(1f),
                    ) {
                        EditorContent(
                            state = state.editorState,
                            modifier = Modifier.fillMaxSize(),
                            fileName = fileName,
                            displaySettings = DisplaySettings(
                                wordWrap = settingsState.wordWrapEnabled,
                                showLineNumbers = settingsState.showLineNumbers,
                                showWhitespace = settingsState.showWhitespace,
                                fontSize = settingsState.fontSize,
                            ),
                            // R-41: taps on the editing surface (which owns all
                            // gestures) re-focus the IME bridge and re-summon
                            // the keyboard after the user dismisses it.
                            onRequestIme = {
                                focusRequester.requestFocus()
                                keyboard?.show()
                            },
                        )
                    }
                    // Auto-focus so the soft keyboard can be opened on tap
                    LaunchedEffect(state) {
                        focusRequester.requestFocus()
                    }
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
                        val focusRequester = remember { FocusRequester() }
                        val keyboard = LocalSoftwareKeyboardController.current
                        ImeHandler(
                            state = editorContent,
                            focusRequester = focusRequester,
                            onSave = { viewModel.save() },
                            onUndo = { viewModel.undo() },
                            onRedo = { viewModel.redo() },
                            modifier = Modifier.weight(1f),
                        ) {
                            EditorContent(
                                state = editorContent,
                                modifier = Modifier.fillMaxSize(),
                                fileName = fileName,
                                displaySettings = DisplaySettings(
                                    wordWrap = settingsState.wordWrapEnabled,
                                    showLineNumbers = settingsState.showLineNumbers,
                                    showWhitespace = settingsState.showWhitespace,
                                ),
                                onRequestIme = {
                                    focusRequester.requestFocus()
                                    keyboard?.show()
                                },
                            )
                        }
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
    settingsState: EditorSettingsState,
    callbacks: EditorMenuCallbacks,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var editOpsExpanded by remember { mutableStateOf(false) }
    var textToolsExpanded by remember { mutableStateOf(false) }
    var caseExpanded by remember { mutableStateOf(false) }
    var bookmarkExpanded by remember { mutableStateOf(false) }
    var lineEndingExpanded by remember { mutableStateOf(false) }

    val checkIcon: @Composable () -> Unit = {
        Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
    }

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
                // ── View section (quick toggles) ──
                DropdownMenuItem(
                    text = { Text("Word wrap") },
                    onClick = { menuExpanded = false; callbacks.onToggleWordWrap() },
                    leadingIcon = if (settingsState.wordWrapEnabled) { checkIcon } else null,
                )
                DropdownMenuItem(
                    text = { Text("Line numbers") },
                    onClick = { menuExpanded = false; callbacks.onToggleLineNumbers() },
                    leadingIcon = if (settingsState.showLineNumbers) { checkIcon } else null,
                )
                DropdownMenuItem(
                    text = { Text("Show whitespace") },
                    onClick = { menuExpanded = false; callbacks.onToggleWhitespace() },
                    leadingIcon = if (settingsState.showWhitespace) { checkIcon } else null,
                )
                DropdownMenuItem(
                    text = { Text("Increase font") },
                    onClick = { menuExpanded = false; callbacks.onIncreaseFontSize() },
                )
                DropdownMenuItem(
                    text = { Text("Decrease font") },
                    onClick = { menuExpanded = false; callbacks.onDecreaseFontSize() },
                )
                HorizontalDivider()
                // ── Edit section ──
                DropdownMenuItem(
                    text = { Text("Find and replace") },
                    onClick = { menuExpanded = false; onFind() },
                )
                DropdownMenuItem(
                    text = { Text("Go to line…") },
                    onClick = { menuExpanded = false; callbacks.onGoToLine() },
                )
                DropdownMenuItem(text = { Text("Text tools ▸") }, onClick = {
                    menuExpanded = false; textToolsExpanded = true
                })
                DropdownMenuItem(text = { Text("Edit operations ▸") }, onClick = {
                    menuExpanded = false; editOpsExpanded = true
                })
                DropdownMenuItem(text = { Text("Case conversion ▸") }, onClick = {
                    menuExpanded = false; caseExpanded = true
                })
                HorizontalDivider()
                // ── Document section ──
                DropdownMenuItem(text = { Text("Save") }, onClick = { menuExpanded = false; callbacks.onSave() })
                DropdownMenuItem(text = { Text("Undo") }, onClick = { menuExpanded = false; callbacks.onUndo() })
                DropdownMenuItem(text = { Text("Redo") }, onClick = { menuExpanded = false; callbacks.onRedo() })
                DropdownMenuItem(text = { Text("Bookmarks ▸") }, onClick = {
                    menuExpanded = false; bookmarkExpanded = true
                })
                DropdownMenuItem(text = { Text("Column select") }, onClick = {
                    menuExpanded = false; callbacks.onToggleColumnSelect()
                })
                DropdownMenuItem(text = { Text("Line ending ▸") }, onClick = {
                    menuExpanded = false; lineEndingExpanded = true
                })
                DropdownMenuItem(text = { Text("Compare with…") }, onClick = { menuExpanded = false; onCompareWith() })
                HorizontalDivider()
                // ── Footer ──
                DropdownMenuItem(text = { Text("Keyboard shortcuts") }, onClick = {
                    menuExpanded = false; callbacks.onKeyboardShortcuts()
                })
                DropdownMenuItem(text = { Text("Settings") }, onClick = {
                    menuExpanded = false; callbacks.onOpenSettings()
                })
            }
            // Text tools submenu
            DropdownMenu(expanded = textToolsExpanded, onDismissRequest = { textToolsExpanded = false }) {
                DropdownMenuItem(text = { Text("Sort lines") }, onClick = { textToolsExpanded = false; callbacks.onSortLines() })
                DropdownMenuItem(text = { Text("Remove duplicates") }, onClick = { textToolsExpanded = false; callbacks.onDeduplicate() })
                DropdownMenuItem(text = { Text("Trim trailing spaces") }, onClick = { textToolsExpanded = false; callbacks.onTrimTrailing() })
                DropdownMenuItem(text = { Text("Reverse lines") }, onClick = { textToolsExpanded = false; callbacks.onReverseLines() })
                DropdownMenuItem(text = { Text("Remove blank lines") }, onClick = { textToolsExpanded = false; callbacks.onRemoveBlankLines() })
                DropdownMenuItem(text = { Text("Join lines") }, onClick = { textToolsExpanded = false; callbacks.onJoinLines() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Tabs → Spaces") }, onClick = { textToolsExpanded = false; callbacks.onTabsToSpaces() })
                DropdownMenuItem(text = { Text("Spaces → Tabs") }, onClick = { textToolsExpanded = false; callbacks.onSpacesToTabs() })
            }
            // Case conversion submenu
            DropdownMenu(expanded = caseExpanded, onDismissRequest = { caseExpanded = false }) {
                DropdownMenuItem(text = { Text("To UPPERCASE") }, onClick = { caseExpanded = false; callbacks.onToUpperCase() })
                DropdownMenuItem(text = { Text("To lowercase") }, onClick = { caseExpanded = false; callbacks.onToLowerCase() })
                DropdownMenuItem(text = { Text("To Title Case") }, onClick = { caseExpanded = false; callbacks.onToTitleCase() })
            }
            // Bookmarks submenu
            DropdownMenu(expanded = bookmarkExpanded, onDismissRequest = { bookmarkExpanded = false }) {
                DropdownMenuItem(text = { Text("Toggle bookmark") }, onClick = { bookmarkExpanded = false; callbacks.onToggleBookmark() })
                DropdownMenuItem(text = { Text("Next bookmark") }, onClick = { bookmarkExpanded = false; callbacks.onNextBookmark() })
                DropdownMenuItem(text = { Text("Previous bookmark") }, onClick = { bookmarkExpanded = false; callbacks.onPrevBookmark() })
            }
            // Edit operations submenu (#12)
            DropdownMenu(expanded = editOpsExpanded, onDismissRequest = { editOpsExpanded = false }) {
                DropdownMenuItem(text = { Text("Delete Line          Ctrl+Shift+K") }, onClick = { editOpsExpanded = false; callbacks.onDeleteLine() })
                DropdownMenuItem(text = { Text("Duplicate Line       Ctrl+Shift+D") }, onClick = { editOpsExpanded = false; callbacks.onDuplicateLine() })
                DropdownMenuItem(text = { Text("Insert Line Above    Ctrl+Shift+Enter") }, onClick = { editOpsExpanded = false; callbacks.onInsertLineAbove() })
                DropdownMenuItem(text = { Text("Insert Line Below    Ctrl+Enter") }, onClick = { editOpsExpanded = false; callbacks.onInsertLineBelow() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Move Line Up         Alt+\u2191") }, onClick = { editOpsExpanded = false; callbacks.onMoveLineUp() })
                DropdownMenuItem(text = { Text("Move Line Down       Alt+\u2193") }, onClick = { editOpsExpanded = false; callbacks.onMoveLineDown() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Indent               Tab") }, onClick = { editOpsExpanded = false; callbacks.onIndent() })
                DropdownMenuItem(text = { Text("Outdent              Shift+Tab") }, onClick = { editOpsExpanded = false; callbacks.onOutdent() })
                DropdownMenuItem(text = { Text("Comment/Uncomment    Ctrl+/") }, onClick = { editOpsExpanded = false; callbacks.onToggleComment() })
            }
            // Line-ending conversion submenu
            DropdownMenu(expanded = lineEndingExpanded, onDismissRequest = { lineEndingExpanded = false }) {
                DropdownMenuItem(text = { Text("Convert to CRLF (Windows)") }, onClick = { lineEndingExpanded = false; callbacks.onConvertLineEndingCRLF() })
                DropdownMenuItem(text = { Text("Convert to LF (Unix)") }, onClick = { lineEndingExpanded = false; callbacks.onConvertLineEndingLF() })
                DropdownMenuItem(text = { Text("Convert to CR (Classic Mac)") }, onClick = { lineEndingExpanded = false; callbacks.onConvertLineEndingCR() })
            }
        },
    )
}

/** Simple dialog that accepts a 1-based line number and calls [onConfirm]. */
@Composable
private fun GoToLineDialog(
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val lineNumber = text.toLongOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to line") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() } },
                label = { Text("Line number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (lineNumber != null && lineNumber > 0) onConfirm(lineNumber) },
                enabled = lineNumber != null && lineNumber > 0,
            ) { Text("Go") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
        "UP" -> {
            val lineText = state.document.line(state.caretLine)
            val newCol = if (state.wordWrap) {
                state.wrappedRowCache.moveUpVisualRow(
                    state.caretLine, state.caretColumn, lineText.length,
                )
            } else null
            if (newCol != null) {
                state.moveCaret(state.caretLine, newCol)
            } else if (state.caretLine > 0) {
                state.moveCaret(state.caretLine - 1, state.caretColumn)
            }
        }
        "DOWN" -> {
            val lineText = state.document.line(state.caretLine)
            val newCol = if (state.wordWrap) {
                state.wrappedRowCache.moveDownVisualRow(
                    state.caretLine, state.caretColumn, lineText.length,
                )
            } else null
            if (newCol != null) {
                state.moveCaret(state.caretLine, newCol)
            } else if (state.caretLine < state.lineCount - 1) {
                state.moveCaret(state.caretLine + 1, state.caretColumn)
            }
        }
        "HOME" -> {
            val visualRowStart = if (state.wordWrap) {
                val vRow = state.wrappedRowCache.visualRowOf(state.caretLine, state.caretColumn)
                state.wrappedRowCache.rowStart(state.caretLine, vRow)
            } else 0
            // Smart home: first press → visual row start; second press → column 0.
            val target = if (state.caretColumn == visualRowStart && visualRowStart != 0) 0 else visualRowStart
            state.moveCaret(state.caretLine, target)
        }
        "END" -> {
            val lineText = state.document.line(state.caretLine)
            val visualRowEnd = if (state.wordWrap) {
                val vRow = state.wrappedRowCache.visualRowOf(state.caretLine, state.caretColumn)
                state.wrappedRowCache.rowEnd(state.caretLine, vRow, lineText.length)
            } else lineText.length
            state.moveCaret(state.caretLine, visualRowEnd)
        }
    }
}
