package com.omnieditor.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Invisible [BasicTextField] overlay that receives IME input and forwards it
 * to [EditorState]. This is the "windowed BasicTextField" fallback from the
 * build plan — it works reliably across all IMEs including CJK composing.
 *
 * The BasicTextField is fully transparent and positioned over the entire editor
 * surface. All visible rendering is done by [EditorContent]; this composable
 * only handles text input bridging.
 *
 * Hardware keyboard events are intercepted via [onPreviewKeyEvent] before
 * they reach the BasicTextField.
 */
@Composable
fun ImeHandler(
    state: EditorState,
    focusRequester: FocusRequester,
    onSave: () -> Unit = {},
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current

    // The "window" text: current line text around the caret, kept in sync.
    var tfv by remember { mutableStateOf(TextFieldValue()) }
    var syncGeneration by remember { mutableStateOf(0L) }

    // Sync EditorState -> TextFieldValue whenever caret moves or document changes
    LaunchedEffect(state) {
        snapshotFlow {
            Triple(state.caretLine, state.caretColumn, state.document.editGeneration)
        }
            .distinctUntilChanged()
            .collectLatest { (line, col, _) ->
                val lineText = try {
                    state.document.line(line).toString()
                } catch (_: Exception) {
                    ""
                }
                val safeCol = col.coerceIn(0, lineText.length)
                val newTfv = TextFieldValue(
                    text = lineText,
                    selection = TextRange(safeCol),
                )
                // Only update if content actually differs to avoid feedback loops
                if (newTfv.text != tfv.text || newTfv.selection != tfv.selection) {
                    tfv = newTfv
                    syncGeneration++
                }
            }
    }

    Box(modifier = modifier) {
        // Custom rendering layer
        content()

        // Invisible BasicTextField for IME bridging
        BasicTextField(
            value = tfv,
            onValueChange = { newValue ->
                handleTextFieldValueChange(state, tfv, newValue)
                tfv = newValue
            },
            modifier = Modifier
                .fillMaxSize()
                .alpha(0f) // Invisible — rendering is done by EditorContent
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    handleHardwareKey(state, event, clipboardManager, onSave, onUndo, onRedo)
                },
            textStyle = TextStyle(fontSize = 1.sp), // Minimise any layout impact
            singleLine = false,
        )
    }
}

/**
 * Compare old and new [TextFieldValue] to determine what changed and apply
 * the delta to [EditorState]. This handles IME composing, commits, and
 * regular character input.
 */
internal fun handleTextFieldValueChange(
    state: EditorState,
    oldValue: TextFieldValue,
    newValue: TextFieldValue,
) {
    if (state.readOnly) return

    val oldText = oldValue.text
    val newText = newValue.text

    if (oldText == newText) {
        // Selection-only change — update caret position
        val sel = newValue.selection
        if (sel.collapsed) {
            val col = sel.start.coerceIn(0, newText.length)
            if (col != state.caretColumn) {
                state.moveCaret(state.caretLine, col)
            }
        }
        return
    }

    // Track composing region from the BasicTextField
    val composition = newValue.composition
    if (composition != null) {
        // IME is composing — we have a composing region
        state.composingLine = state.caretLine
        state.composingStart = composition.start
        state.composingEnd = composition.end
    } else {
        state.clearComposingRegion()
    }

    // Replace the entire current line with the new text from the BasicTextField
    val currentLineText = try {
        state.document.line(state.caretLine).toString()
    } catch (_: Exception) {
        ""
    }

    if (newText != currentLineText) {
        // Handle newlines: if the new text contains newlines, split appropriately
        if (newText.contains('\n')) {
            // Multi-line change — rebuild the replacement
            val lines = newText.split('\n')
            val replacement = lines.joinToString("\n")
            state.document.edit(state.caretLine..state.caretLine, replacement)
            val newLines = lines.size - 1
            state.caretLine = state.caretLine + newLines
            state.caretColumn = lines.last().length.coerceAtMost(
                newValue.selection.start.coerceAtLeast(0),
            )
        } else {
            state.document.edit(state.caretLine..state.caretLine, newText)
        }
    }

    // Update caret from the new selection
    val sel = newValue.selection
    if (sel.collapsed) {
        state.caretColumn = sel.start.coerceIn(0, newText.length)
    }
}

/**
 * Handle hardware keyboard events. Returns true if the event was consumed.
 */
internal fun handleHardwareKey(
    state: EditorState,
    event: androidx.compose.ui.input.key.KeyEvent,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    onSave: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
): Boolean {
    val ctrl = event.isCtrlPressed
    val shift = event.isShiftPressed

    return when {
        // Ctrl+S — save
        ctrl && event.key == Key.S -> {
            onSave()
            true
        }
        // Ctrl+Z — undo
        ctrl && !shift && event.key == Key.Z -> {
            onUndo()
            true
        }
        // Ctrl+Shift+Z or Ctrl+Y — redo
        (ctrl && shift && event.key == Key.Z) || (ctrl && event.key == Key.Y) -> {
            onRedo()
            true
        }
        // Ctrl+A — select all
        ctrl && event.key == Key.A -> {
            val lastLine = state.lineCount - 1
            val lastLineText = state.document.line(lastLine).toString()
            state.setSelection(0L, 0, lastLine, lastLineText.length)
            true
        }
        // Ctrl+C — copy
        ctrl && event.key == Key.C -> {
            if (state.hasSelection) {
                clipboardManager.setText(AnnotatedString(state.selectedText()))
            }
            true
        }
        // Ctrl+X — cut
        ctrl && event.key == Key.X -> {
            if (state.hasSelection) {
                clipboardManager.setText(AnnotatedString(state.selectedText()))
                state.deleteSelection()
            }
            true
        }
        // Ctrl+V — paste (let BasicTextField handle it for IME compat)
        ctrl && event.key == Key.V -> false

        // Navigation keys with shift for selection
        event.key == Key.DirectionLeft -> {
            if (shift) {
                state.startSelection()
                if (state.caretColumn > 0) {
                    state.moveCaretWithSelection(state.caretLine, state.caretColumn - 1)
                } else if (state.caretLine > 0) {
                    val prev = state.document.line(state.caretLine - 1)
                    state.moveCaretWithSelection(state.caretLine - 1, prev.length)
                }
            } else {
                if (state.caretColumn > 0) {
                    state.moveCaret(state.caretLine, state.caretColumn - 1)
                } else if (state.caretLine > 0) {
                    val prev = state.document.line(state.caretLine - 1)
                    state.moveCaret(state.caretLine - 1, prev.length)
                }
            }
            true
        }
        event.key == Key.DirectionRight -> {
            val cur = state.document.line(state.caretLine)
            if (shift) {
                state.startSelection()
                if (state.caretColumn < cur.length) {
                    state.moveCaretWithSelection(state.caretLine, state.caretColumn + 1)
                } else if (state.caretLine < state.lineCount - 1) {
                    state.moveCaretWithSelection(state.caretLine + 1, 0)
                }
            } else {
                if (state.caretColumn < cur.length) {
                    state.moveCaret(state.caretLine, state.caretColumn + 1)
                } else if (state.caretLine < state.lineCount - 1) {
                    state.moveCaret(state.caretLine + 1, 0)
                }
            }
            true
        }
        event.key == Key.DirectionUp -> {
            if (shift) {
                state.startSelection()
                if (state.caretLine > 0) {
                    state.moveCaretWithSelection(state.caretLine - 1, state.caretColumn)
                }
            } else {
                if (state.caretLine > 0) state.moveCaret(state.caretLine - 1, state.caretColumn)
            }
            true
        }
        event.key == Key.DirectionDown -> {
            if (shift) {
                state.startSelection()
                if (state.caretLine < state.lineCount - 1) {
                    state.moveCaretWithSelection(state.caretLine + 1, state.caretColumn)
                }
            } else {
                if (state.caretLine < state.lineCount - 1) {
                    state.moveCaret(state.caretLine + 1, state.caretColumn)
                }
            }
            true
        }
        event.key == Key.MoveHome -> {
            if (shift) {
                state.startSelection()
                state.moveCaretWithSelection(state.caretLine, 0)
            } else {
                state.moveCaret(state.caretLine, 0)
            }
            true
        }
        event.key == Key.MoveEnd -> {
            val lineText = state.document.line(state.caretLine)
            if (shift) {
                state.startSelection()
                state.moveCaretWithSelection(state.caretLine, lineText.length)
            } else {
                state.moveCaret(state.caretLine, lineText.length)
            }
            true
        }

        // Tab key — insert spaces (code editor convention)
        event.key == Key.Tab -> {
            if (!state.readOnly) {
                state.insertAtCaret("    ") // 4 spaces
            }
            true
        }

        // Let all other keys pass through to BasicTextField for IME handling
        else -> false
    }
}
