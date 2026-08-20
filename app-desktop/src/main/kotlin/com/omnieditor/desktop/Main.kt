package com.omnieditor.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.omnieditor.core.model.ShortcutAction
import kotlinx.coroutines.launch
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val action = parseArgs(args)

    application {
        var settings by remember { mutableStateOf(DesktopSettings.load()) }
        fun updateSettings(newSettings: DesktopSettings) {
            settings = newSettings
            DesktopSettings.save(newSettings)
        }

        val windowState = rememberWindowState(
            width = settings.windowWidth.dp,
            height = settings.windowHeight.dp,
        )
        val navigator = remember { DesktopNavigator() }
        val menuActions = remember { DesktopMenuActions() }
        val scope = rememberCoroutineScope()

        fun saveWindowStateAndQuit() {
            updateSettings(settings.copy(
                windowWidth = windowState.size.width.value.toInt(),
                windowHeight = windowState.size.height.value.toInt(),
            ))
            exitApplication()
        }

        Window(
            onCloseRequest = ::saveWindowStateAndQuit,
            title = "Omni Editor",
            state = windowState,
        ) {
            MenuBar {
                Menu("File") {
                    Item("Open…", shortcut = KeyShortcut(Key.O, ctrl = true)) {
                        scope.launch {
                            val file = DesktopFileDialogs.showOpenDialog()
                            if (file != null) navigator.navigate(Screen.Editor(file.absolutePath))
                        }
                    }
                    Item("Compare…") {
                        navigator.navigate(Screen.Setup())
                    }
                    Separator()
                    // Z-6b-3: File → Save from the active screen's save callback
                    Item(
                        "Save",
                        enabled = menuActions.isDirty && menuActions.onSave != null,
                        shortcut = shortcutFor(ShortcutAction.SAVE) ?: KeyShortcut(Key.S, ctrl = true),
                    ) {
                        menuActions.onSave?.invoke()
                    }
                    Item(
                        "Save As…",
                        enabled = menuActions.onSaveAs != null,
                        shortcut = KeyShortcut(Key.S, ctrl = true, shift = true),
                    ) {
                        menuActions.onSaveAs?.invoke()
                    }
                    Separator()
                    Item("Settings…") {
                        navigator.navigate(Screen.Settings)
                    }
                    Separator()
                    Item("Quit", shortcut = KeyShortcut(Key.Q, ctrl = true)) {
                        saveWindowStateAndQuit()
                    }
                }
                // Z-6b-1: Edit menu wired to focused screen actions; disabled when unavailable
                Menu("Edit") {
                    Item(
                        "Undo",
                        enabled = menuActions.onUndo != null,
                        shortcut = shortcutFor(ShortcutAction.UNDO) ?: KeyShortcut(Key.Z, ctrl = true),
                    ) { menuActions.onUndo?.invoke() }
                    Item(
                        "Redo",
                        enabled = menuActions.onRedo != null,
                        shortcut = shortcutFor(ShortcutAction.REDO) ?: KeyShortcut(Key.Z, ctrl = true, shift = true),
                    ) { menuActions.onRedo?.invoke() }
                    Separator()
                    Item(
                        "Cut",
                        enabled = menuActions.onCut != null,
                        shortcut = shortcutFor(ShortcutAction.CUT) ?: KeyShortcut(Key.X, ctrl = true),
                    ) { menuActions.onCut?.invoke() }
                    Item(
                        "Copy",
                        enabled = menuActions.onCopy != null,
                        shortcut = shortcutFor(ShortcutAction.COPY) ?: KeyShortcut(Key.C, ctrl = true),
                    ) { menuActions.onCopy?.invoke() }
                    Item(
                        "Paste",
                        enabled = menuActions.onPaste != null,
                        shortcut = shortcutFor(ShortcutAction.PASTE) ?: KeyShortcut(Key.V, ctrl = true),
                    ) { menuActions.onPaste?.invoke() }
                    Separator()
                    Item(
                        "Find",
                        enabled = menuActions.onFind != null,
                        shortcut = shortcutFor(ShortcutAction.FIND) ?: KeyShortcut(Key.F, ctrl = true),
                    ) { menuActions.onFind?.invoke() }
                }
                Menu("View") {
                    CheckboxItem(
                        "Word Wrap",
                        checked = settings.wordWrap,
                    ) { updateSettings(settings.copy(wordWrap = it)) }
                    CheckboxItem(
                        "Line Numbers",
                        checked = settings.showLineNumbers,
                    ) { updateSettings(settings.copy(showLineNumbers = it)) }
                    CheckboxItem(
                        "Show Whitespace",
                        checked = settings.showWhitespace,
                    ) { updateSettings(settings.copy(showWhitespace = it)) }
                    Separator()
                    Item("Increase Font Size") {
                        updateSettings(settings.copy(fontSize = (settings.fontSize + 2).coerceAtMost(48)))
                    }
                    Item("Decrease Font Size") {
                        updateSettings(settings.copy(fontSize = (settings.fontSize - 2).coerceAtLeast(8)))
                    }
                    Separator()
                    Menu("Theme") {
                        RadioButtonItem(
                            "System Default",
                            selected = settings.darkTheme == "system",
                        ) { updateSettings(settings.copy(darkTheme = "system")) }
                        RadioButtonItem(
                            "Light",
                            selected = settings.darkTheme == "light",
                        ) { updateSettings(settings.copy(darkTheme = "light")) }
                        RadioButtonItem(
                            "Dark",
                            selected = settings.darkTheme == "dark",
                        ) { updateSettings(settings.copy(darkTheme = "dark")) }
                    }
                }
                Menu("Help") {
                    Item("About") {
                        javax.swing.JOptionPane.showMessageDialog(
                            null,
                            "Omni Editor\n${DesktopBuildInfo.aboutString}\n\nText editor, compare and merge tool",
                            "About Omni Editor",
                            javax.swing.JOptionPane.INFORMATION_MESSAGE,
                        )
                    }
                }
            }

            DesktopApp(
                initialAction = action,
                navigator = navigator,
                settings = settings,
                onSettingsChanged = ::updateSettings,
                menuActions = menuActions,
            )
        }
    }
}

sealed class StartAction {
    data object None : StartAction()
    data class OpenFile(val path: String) : StartAction()
    data class Compare(val left: String, val right: String) : StartAction()
}

private fun parseArgs(args: Array<String>): StartAction {
    if (args.isEmpty()) return StartAction.None

    return when (args[0]) {
        "--compare" -> {
            if (args.size < 3) {
                System.err.println("Usage: omnieditor --compare <left> <right>")
                exitProcess(1)
            }
            val left = args[1]
            val right = args[2]
            if (!File(left).exists()) {
                System.err.println("Error: file not found: $left")
                exitProcess(1)
            }
            if (!File(right).exists()) {
                System.err.println("Error: file not found: $right")
                exitProcess(1)
            }
            StartAction.Compare(left, right)
        }
        "--merge" -> {
            System.err.println("Error: --merge not yet implemented")
            exitProcess(1)
        }
        "--help", "-h" -> {
            println("Usage: omnieditor [file]")
            println("       omnieditor --compare <left> <right>")
            exitProcess(0)
        }
        else -> {
            val path = args[0]
            if (!File(path).exists()) {
                System.err.println("Error: file not found: $path")
                exitProcess(1)
            }
            StartAction.OpenFile(path)
        }
    }
}
