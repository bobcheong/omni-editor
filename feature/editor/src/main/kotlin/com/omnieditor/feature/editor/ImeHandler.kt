package com.omnieditor.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Sentinel character prefixed to the IME window text (R-16, R-47).
 *
 * The window exposes only the caret line to the IME. Without a sentinel, a
 * backspace at column 0 is a no-op inside the field (there is nothing to the
 * left of offset 0), so `onValueChange` never fires and lines can never be
 * joined from the soft keyboard. Many IMEs delete via `deleteSurroundingText`
 * rather than KEYCODE_DEL, so a key-event fallback alone is not sufficient.
 *
 * With a zero-width-space sentinel at offset 0, a line-start backspace deletes
 * the sentinel — an observable change we translate into a join with the
 * previous line. The sentinel is invisible (the field is invisible anyway) and
 * is stripped before any text reaches the document.
 */
internal const val WINDOW_SENTINEL = "\u200B"

/**
 * IME bridge for the custom editing surface (R-16).
 *
 * A tiny (1dp), transparent [BasicTextField] receives soft-keyboard input and
 * forwards deltas to [EditorState]. All visible rendering is done by
 * [EditorContent].
 *
 * CRITICAL — sizing: the field must NOT cover the editor. Compose hit-testing
 * stops at the topmost sibling containing the pointer, so a `fillMaxSize()`
 * overlay silently swallows every tap and drag before they can reach the
 * LazyColumn or the editor's gesture handlers (no scrolling, no caret
 * placement). The field exists purely as an InputConnection anchor; gestures
 * are owned by [EditorContent], which calls back into `onRequestIme` (wired in
 * EditorScreen) to focus this field and show the keyboard on tap.
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

    // The IME "window": sentinel + current line text, kept in sync with state.
    var tfv by remember { mutableStateOf(TextFieldValue()) }

    // Sync EditorState -> window whenever the caret moves or the document
    // changes. Skipped while an IME composition is active: rebuilding the
    // TextFieldValue drops the composition region (TextFieldValue(text,
    // selection) has composition = null), which resets predictive IMEs
    // mid-word and produces dropped/duplicated characters.
    LaunchedEffect(state) {
        snapshotFlow {
            Triple(state.caretLine, state.caretColumn, state.document.editGeneration)
        }
            .distinctUntilChanged()
            .collect {
                if (state.isComposing) return@collect
                val newTfv = buildWindow(state)
                if (newTfv.text != tfv.text || newTfv.selection != tfv.selection) {
                    tfv = newTfv
                }
            }
    }

    Box(modifier = modifier) {
        // Custom rendering layer — receives all touch input.
        content()

        // Invisible 1dp IME anchor. Small enough never to win hit-testing
        // over the editor surface; still focusable so the soft keyboard opens.
        BasicTextField(
            value = tfv,
            onValueChange = { newValue ->
                val resync = handleWindowChange(state, tfv, newValue)
                tfv = if (resync) buildWindow(state) else newValue
            },
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    handleHardwareKey(state, event, clipboardManager, onSave, onUndo, onRedo)
                },
            textStyle = TextStyle(fontSize = 1.sp),
            singleLine = false,
        )
    }
}

/** Build the IME window value from the current editor state. */
internal fun buildWindow(state: EditorState): TextFieldValue {
    val lineText = try {
        state.document.line(state.caretLine).toString()
    } catch (_: Exception) {
        ""
    }
    val col = state.caretColumn.coerceIn(0, lineText.length)
    return TextFieldValue(
        text = WINDOW_SENTINEL + lineText,
        selection = TextRange(col + WINDOW_SENTINEL.length),
    )
}

/**
 * Apply a window change from the IME to [EditorState] (R-47).
 *
 * Returns `true` when the caller must rebuild the window from state (the edit
 * crossed a line boundary or replaced a multi-line selection, so the one-line
 * window no longer reflects the document), and `false` when the incoming
 * [newValue] can be kept as-is (same-line edits, composition in progress).
 */
@Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
internal fun handleWindowChange(
    state: EditorState,
    oldValue: TextFieldValue,
    newValue: TextFieldValue,
): Boolean {
    if (state.readOnly) return true

    val oldText = oldValue.text
    val newText = newValue.text

    // ── Selection-only change ────────────────────────────────────────────
    if (oldText == newText) {
        val sel = newValue.selection
        if (sel.collapsed) {
            val lineLen = (newText.length - WINDOW_SENTINEL.length).coerceAtLeast(0)
            val col = (sel.start - WINDOW_SENTINEL.length).coerceIn(0, lineLen)
            if (col != state.caretColumn) {
                state.moveCaret(state.caretLine, col)
            }
        }
        updateComposing(state, newValue)
        return false
    }

    // ── Sentinel deleted → backspace at column 0 → join with previous line ─
    if (!newText.startsWith(WINDOW_SENTINEL)) {
        state.clearComposingRegion()
        if (state.caretLine > 0) {
            val prev = state.document.line(state.caretLine - 1).toString()
            // newText is the surviving current-line content (sentinel removed).
            state.document.edit(state.caretLine - 1..state.caretLine, prev + newText)
            state.moveCaret(state.caretLine - 1, prev.length)
        }
        return true
    }

    val newLine = newText.substring(WINDOW_SENTINEL.length)

    // ── Active multi-line selection → replace it with the inserted delta ──
    // The window only mirrors the caret line, so a document-level selection
    // must be resolved here before applying the window text.
    if (state.hasSelection) {
        state.clearComposingRegion()
        val inserted = insertedDelta(oldText, newText)
        state.deleteSelection()
        if (inserted.isNotEmpty()) state.insertAtCaret(inserted)
        return true
    }

    // ── Newline(s) entered → split the line ──────────────────────────────
    if (newLine.contains('\n')) {
        state.clearComposingRegion()
        state.document.edit(state.caretLine..state.caretLine, newLine)
        // Caret position derives from the window selection, mapped into the
        // multi-line replacement. (The previous implementation compared a
        // whole-window offset against the last segment's length, landing the
        // caret at a wrong column after every Enter.)
        val selInLine = (newValue.selection.start - WINDOW_SENTINEL.length)
            .coerceIn(0, newLine.length)
        val before = newLine.substring(0, selInLine)
        val lineDelta = before.count { it == '\n' }
        val colInSegment = selInLine - (before.lastIndexOf('\n') + 1)
        state.moveCaret(state.caretLine + lineDelta, colInSegment)
        return true
    }

    // ── Plain same-line change ───────────────────────────────────────────
    val currentLineText = try {
        state.document.line(state.caretLine).toString()
    } catch (_: Exception) {
        ""
    }
    if (newLine != currentLineText) {
        state.document.edit(state.caretLine..state.caretLine, newLine)
    }
    val sel = newValue.selection
    if (sel.collapsed) {
        state.caretColumn = (sel.start - WINDOW_SENTINEL.length).coerceIn(0, newLine.length)
    }
    updateComposing(state, newValue)
    return false
}

/** Mirror the IME composition region into state, shifted past the sentinel. */
private fun updateComposing(state: EditorState, value: TextFieldValue) {
    val composition = value.composition
    if (composition != null) {
        val lineLen = (value.text.length - WINDOW_SENTINEL.length).coerceAtLeast(0)
        state.composingLine = state.caretLine
        state.composingStart = (composition.start - WINDOW_SENTINEL.length).coerceIn(0, lineLen)
        state.composingEnd = (composition.end - WINDOW_SENTINEL.length).coerceIn(0, lineLen)
    } else {
        state.clearComposingRegion()
    }
}

/**
 * Extract the inserted text of a window change via common prefix/suffix.
 * Used when a document-level selection is being replaced by typed input.
 */
internal fun insertedDelta(oldText: String, newText: String): String {
    var prefix = 0
    val maxPrefix = minOf(oldText.length, newText.length)
    while (prefix < maxPrefix && oldText[prefix] == newText[prefix]) prefix++
    var suffix = 0
    val maxSuffix = minOf(oldText.length, newText.length) - prefix
    while (suffix < maxSuffix &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) {
        suffix++
    }
    return newText.substring(prefix, newText.length - suffix)
}

/**
 * Handle hardware keyboard events. Returns true if the event was consumed.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
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

        // Forward delete at end of line — join with the next line. The IME
        // window contains no next-line character, so this edit is not
        // representable inside the field and must be handled here (R-47).
        event.key == Key.Delete -> {
            if (state.readOnly) return true
            val cur = state.document.line(state.caretLine).toString()
            if (state.caretColumn >= cur.length && state.caretLine < state.lineCount - 1) {
                val next = state.document.line(state.caretLine + 1).toString()
                state.document.edit(state.caretLine..state.caretLine + 1, cur + next)
                state.moveCaret(state.caretLine, cur.length)
                true
            } else {
                false // in-line forward delete flows through the field
            }
        }

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
            val lineText = state.document.line(state.caretLine)
            val newCol = if (state.wordWrap) {
                state.wrappedRowCache.moveUpVisualRow(
                    state.caretLine, state.caretColumn, lineText.length,
                )
            } else null
            if (shift) {
                state.startSelection()
                if (newCol != null) {
                    state.moveCaretWithSelection(state.caretLine, newCol)
                } else if (state.caretLine > 0) {
                    state.moveCaretWithSelection(state.caretLine - 1, state.caretColumn)
                }
            } else {
                if (newCol != null) {
                    state.moveCaret(state.caretLine, newCol)
                } else if (state.caretLine > 0) {
                    state.moveCaret(state.caretLine - 1, state.caretColumn)
                }
            }
            true
        }
        event.key == Key.DirectionDown -> {
            val lineText = state.document.line(state.caretLine)
            val newCol = if (state.wordWrap) {
                state.wrappedRowCache.moveDownVisualRow(
                    state.caretLine, state.caretColumn, lineText.length,
                )
            } else null
            if (shift) {
                state.startSelection()
                if (newCol != null) {
                    state.moveCaretWithSelection(state.caretLine, newCol)
                } else if (state.caretLine < state.lineCount - 1) {
                    state.moveCaretWithSelection(state.caretLine + 1, state.caretColumn)
                }
            } else {
                if (newCol != null) {
                    state.moveCaret(state.caretLine, newCol)
                } else if (state.caretLine < state.lineCount - 1) {
                    state.moveCaret(state.caretLine + 1, state.caretColumn)
                }
            }
            true
        }
        event.key == Key.MoveHome -> {
            val visualRowStart = if (state.wordWrap) {
                val vRow = state.wrappedRowCache.visualRowOf(state.caretLine, state.caretColumn)
                state.wrappedRowCache.rowStart(state.caretLine, vRow)
            } else {
                0
            }
            val target = if (state.caretColumn == visualRowStart && visualRowStart != 0) {
                0
            } else {
                visualRowStart
            }
            if (shift) {
                state.startSelection()
                state.moveCaretWithSelection(state.caretLine, target)
            } else {
                state.moveCaret(state.caretLine, target)
            }
            true
        }
        event.key == Key.MoveEnd -> {
            val lineText = state.document.line(state.caretLine)
            val visualRowEnd = if (state.wordWrap) {
                val vRow = state.wrappedRowCache.visualRowOf(state.caretLine, state.caretColumn)
                state.wrappedRowCache.rowEnd(state.caretLine, vRow, lineText.length)
            } else {
                lineText.length
            }
            if (shift) {
                state.startSelection()
                state.moveCaretWithSelection(state.caretLine, visualRowEnd)
            } else {
                state.moveCaret(state.caretLine, visualRowEnd)
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
