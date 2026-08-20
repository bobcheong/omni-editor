package com.omnieditor.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.omnieditor.core.diff.DiffEngine
import com.omnieditor.core.io.PieceTableDocument
import com.omnieditor.core.io.SaveOrchestrator
import com.omnieditor.core.model.RuleSet
import com.omnieditor.core.model.SourceKind
import com.omnieditor.core.model.SourceRef
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import com.omnieditor.design.OmniTheme
import com.omnieditor.feature.compare.CompareScreen
import com.omnieditor.feature.compare.CompareState
import com.omnieditor.feature.editor.EditorScreen
import com.omnieditor.feature.editor.EditorViewModel
import com.omnieditor.feature.setup.SourceSetupScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DesktopApp(
    initialAction: StartAction = StartAction.None,
    navigator: DesktopNavigator = remember { DesktopNavigator() },
    settings: DesktopSettings = remember { DesktopSettings.load() },
    onSettingsChanged: (DesktopSettings) -> Unit = {},
    menuActions: DesktopMenuActions = remember { DesktopMenuActions() },
) {

    // Route initial action once
    LaunchedEffect(initialAction) {
        when (initialAction) {
            is StartAction.OpenFile -> navigator.navigate(Screen.Editor(initialAction.path))
            is StartAction.Compare -> navigator.navigate(
                Screen.Compare(initialAction.left, initialAction.right),
            )
            StartAction.None -> {}
        }
    }

    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (settings.darkTheme) {
        "light" -> false
        "dark" -> true
        else -> systemDark // "system" follows OS preference
    }

    OmniTheme(darkTheme = darkTheme, dynamicColor = false) {
        // Surface ensures the entire window background follows the theme
        Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
        when (val screen = navigator.currentScreen) {
            is Screen.Home -> {
                LaunchedEffect(Unit) { menuActions.clear() }
                DesktopHomeScreen(
                    onOpenFile = { path -> navigator.navigate(Screen.Editor(path)) },
                    onCompare = { navigator.navigate(Screen.Setup()) },
                )
            }
            is Screen.Editor -> {
                DesktopEditorScreen(
                    filePath = screen.filePath,
                    navigator = navigator,
                    settings = settings,
                    onSettingsChanged = onSettingsChanged,
                    menuActions = menuActions,
                )
            }
            is Screen.Compare -> {
                DesktopCompareScreen(
                    leftPath = screen.leftPath,
                    rightPath = screen.rightPath,
                    navigator = navigator,
                    settings = settings,
                    onSettingsChanged = onSettingsChanged,
                    menuActions = menuActions,
                )
            }
            is Screen.Setup -> {
                DesktopSetupScreen(
                    prefillLeft = screen.prefillLeft,
                    navigator = navigator,
                )
            }
            is Screen.Settings -> {
                DesktopSettingsScreen(
                    settings = settings,
                    onSettingsChanged = onSettingsChanged,
                    onNavigateBack = { navigator.back() },
                )
            }
        }
        } // Surface
    }
}

@Composable
private fun DesktopEditorScreen(
    filePath: String?,
    navigator: DesktopNavigator,
    settings: DesktopSettings,
    onSettingsChanged: (DesktopSettings) -> Unit,
    menuActions: DesktopMenuActions,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember { EditorViewModel() }

    // Save As action — shared between menu and screen
    val saveAsAction: () -> Unit = {
        scope.launch {
            val target = DesktopFileDialogs.showSaveDialog(
                suggestedName = filePath?.let { File(it).name } ?: "document.txt",
            )
            if (target != null) {
                withContext(Dispatchers.IO) {
                    val content = viewModel.getContent()
                    target.writeBytes(content.toByteArray())
                }
            }
        }
    }

    // Register menu actions for this screen
    LaunchedEffect(viewModel) {
        menuActions.onUndo = { viewModel.undo() }
        menuActions.onRedo = { viewModel.redo() }
        menuActions.onFind = { /* Find is handled internally by EditorScreen via its own state */ }
        menuActions.onSave = { viewModel.save() }
        menuActions.onSaveAs = saveAsAction
    }
    // Track dirty state for File → Save enabled
    menuActions.isDirty = viewModel.isDirty
    // Clear on dispose
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { menuActions.clear() }
    }

    val editorSettingsState = remember(settings) {
        com.omnieditor.feature.editor.EditorSettingsState(
            wordWrapEnabled = settings.wordWrap,
            showLineNumbers = settings.showLineNumbers,
            showWhitespace = settings.showWhitespace,
            fontSize = settings.fontSize,
        )
    }

    // Load file content on first composition
    LaunchedEffect(filePath) {
        if (filePath != null) {
            val content = withContext(Dispatchers.IO) {
                File(filePath).readText()
            }
            viewModel.openDocument(content, fileName = File(filePath).name)

            // Wire save function
            viewModel.setSaveFunction { bytes ->
                withContext(Dispatchers.IO) {
                    File(filePath).writeBytes(bytes)
                }
            }
        }
    }

    EditorScreen(
        fileName = filePath?.let { File(it).name } ?: "Untitled",
        onNavigateBack = { navigator.back() },
        onCompareWith = {
            filePath?.let { navigator.navigate(Screen.Setup(prefillLeft = it)) }
        },
        onSaveAs = saveAsAction,
        settingsState = editorSettingsState,
        onToggleWordWrap = { onSettingsChanged(settings.copy(wordWrap = !settings.wordWrap)) },
        onToggleLineNumbers = { onSettingsChanged(settings.copy(showLineNumbers = !settings.showLineNumbers)) },
        onToggleWhitespace = { onSettingsChanged(settings.copy(showWhitespace = !settings.showWhitespace)) },
        onIncreaseFontSize = { onSettingsChanged(settings.copy(fontSize = (settings.fontSize + 2).coerceAtMost(48))) },
        onDecreaseFontSize = { onSettingsChanged(settings.copy(fontSize = (settings.fontSize - 2).coerceAtLeast(8))) },
        onOpenSettings = { navigator.navigate(Screen.Settings) },
        viewModel = viewModel,
    )
}

@Composable
private fun DesktopCompareScreen(
    leftPath: String,
    rightPath: String,
    navigator: DesktopNavigator,
    settings: DesktopSettings,
    onSettingsChanged: (DesktopSettings) -> Unit,
    menuActions: DesktopMenuActions,
) {
    val scope = rememberCoroutineScope()
    var compareState by remember { mutableStateOf<CompareState?>(null) }
    var ruleSet by remember { mutableStateOf(RuleSet.DEFAULT) }

    // Register menu actions for compare screen
    LaunchedEffect(Unit) {
        menuActions.onFind = { /* Find handled internally by CompareScreen */ }
    }
    // Clear on dispose
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { menuActions.clear() }
    }

    // Run compare on launch (and re-run when ruleSet changes)
    LaunchedEffect(leftPath, rightPath, ruleSet) {
        compareState = null
        withContext(Dispatchers.Default) {
            val leftText = withContext(Dispatchers.IO) { File(leftPath).readText() }
            val rightText = withContext(Dispatchers.IO) { File(rightPath).readText() }
            val leftLines = leftText.lines()
            val rightLines = rightText.lines()
            val leftDoc = PieceTableDocument.create(leftText)
            val rightDoc = PieceTableDocument.create(rightText)
            val result = DiffEngine.compareAuto(
                leftLineCount = leftLines.size.toLong(),
                rightLineCount = rightLines.size.toLong(),
                leftLine = { leftLines[it.toInt()] },
                rightLine = { rightLines[it.toInt()] },
                rules = ruleSet,
            )
            compareState = CompareState(
                result = result,
                leftLines = leftLines,
                rightLines = rightLines,
                ruleSet = ruleSet,
                leftDocument = leftDoc,
                rightDocument = rightDoc,
            )
        }
    }

    CompareScreen(
        state = compareState,
        ruleSet = ruleSet,
        onRuleSetChanged = { ruleSet = it },
        leftLabel = File(leftPath).name,
        rightLabel = File(rightPath).name,
        onNavigateBack = { navigator.back() },
        settingsState = com.omnieditor.feature.compare.CompareSettingsState(
            layoutMode = settings.defaultLayout,
            syncScroll = settings.syncScroll,
            granularity = settings.granularity,
        ),
        settingsCallbacks = com.omnieditor.feature.compare.CompareSettingsCallbacks(
            onSetLayout = { onSettingsChanged(settings.copy(defaultLayout = it)) },
            onToggleSyncScroll = { onSettingsChanged(settings.copy(syncScroll = !settings.syncScroll)) },
            onSetGranularity = { g ->
                onSettingsChanged(settings.copy(granularity = g))
                val newGranularity = when (g) {
                    "line" -> com.omnieditor.core.model.Granularity.LINE
                    "char" -> com.omnieditor.core.model.Granularity.CHARACTER
                    else -> com.omnieditor.core.model.Granularity.WORD
                }
                ruleSet = ruleSet.copy(granularity = newGranularity)
            },
            onOpenSettings = { navigator.navigate(Screen.Settings) },
        ),
        onSave = {
            scope.launch {
                val state = compareState ?: return@launch
                val backupDir = File(System.getProperty("java.io.tmpdir"), "omnieditor-backups")
                val errors = mutableListOf<String>()
                withContext(Dispatchers.IO) {
                    val leftDoc = state.leftDocument
                    if (state.leftDirty && leftDoc != null) {
                        val result = SaveOrchestrator.saveWithBackup(
                            leftDoc, File(leftPath), backupDir, "compare",
                        )
                        if (result.success) {
                            leftDoc.markSaved()
                        } else {
                            errors.add("Left: ${result.error ?: "save failed"}")
                        }
                    }
                    val rightDoc = state.rightDocument
                    if (state.rightDirty && rightDoc != null) {
                        val result = SaveOrchestrator.saveWithBackup(
                            rightDoc, File(rightPath), backupDir, "compare",
                        )
                        if (result.success) {
                            rightDoc.markSaved()
                        } else {
                            errors.add("Right: ${result.error ?: "save failed"}")
                        }
                    }
                }
                state.showMessage(
                    if (errors.isEmpty()) "Saved" else "Save failed: ${errors.joinToString("; ")}"
                )
            }
        },
        onOpenLeft = { navigator.navigate(Screen.Editor(leftPath)) },
        onOpenRight = { navigator.navigate(Screen.Editor(rightPath)) },
    )
}

@Composable
private fun DesktopSetupScreen(
    prefillLeft: String?,
    navigator: DesktopNavigator,
) {
    val scope = rememberCoroutineScope()
    var leftPath by remember { mutableStateOf(prefillLeft) }
    var rightPath by remember { mutableStateOf<String?>(null) }

    SourceSetupScreen(
        leftSource = leftPath?.let { pathToSourceRef(it) },
        rightSource = rightPath?.let { pathToSourceRef(it) },
        onPickLeft = {
            scope.launch {
                val file = DesktopFileDialogs.showOpenDialog("Select Left File")
                if (file != null) leftPath = file.absolutePath
            }
        },
        onPickRight = {
            scope.launch {
                val file = DesktopFileDialogs.showOpenDialog("Select Right File")
                if (file != null) rightPath = file.absolutePath
            }
        },
        onSwapSides = {
            val tmp = leftPath
            leftPath = rightPath
            rightPath = tmp
        },
        onCompare = {
            val l = leftPath
            val r = rightPath
            if (l != null && r != null) {
                navigator.navigate(Screen.Compare(l, r))
            }
        },
        onNavigateBack = { navigator.back() },
    )
}

/** Build a [SourceRef] from a local filesystem path for the setup screen. */
private fun pathToSourceRef(path: String): SourceRef {
    val file = File(path)
    return SourceRef(
        id = path,
        kind = SourceKind.LOCAL,
        path = path,
        label = file.name,
    )
}
