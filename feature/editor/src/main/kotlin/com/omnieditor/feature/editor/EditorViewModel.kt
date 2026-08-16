package com.omnieditor.feature.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnieditor.core.io.FindReplace
import com.omnieditor.core.io.PieceTableDocument
import com.omnieditor.core.io.TextDocument
import com.omnieditor.core.io.TextTools
import com.omnieditor.core.model.LineEnding
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.channels.Channels
import javax.inject.Inject

/**
 * A tool that is pending user confirmation because it would change more than half the lines
 * (spec §2.3: counted confirmation for destructive tools).
 */
data class PendingTool(
    val label: String,
    val changedLines: Int,
    val totalLines: Int,
    val apply: () -> Unit,
)

@HiltViewModel
class EditorViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.Empty)
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var editorState: EditorState? = null

    /** The last EditorState that was in the Loaded state; remains accessible while in ExternallyChanged. */
    val lastLoadedState: EditorState? get() = editorState

    /** Injected by the NavGraph so the feature module stays free of Android/app deps. */
    private var saveFn: (suspend (ByteArray) -> Unit)? = null

    /** Returns true if the file on disk differs from the snapshot taken at open time. */
    private var checkFingerprintFn: (suspend () -> Boolean)? = null

    /** Re-reads the file from its source URI and re-opens it in the editor. */
    private var reloadFn: (suspend () -> Unit)? = null

    /** File name remembered when the document is loaded, used for the ExternallyChanged banner. */
    private var currentFileName: String = ""

    /**
     * Tool awaiting confirmation. Non-null when a destructive tool would change >50% of lines.
     * The UI should present a confirmation dialog and call [confirmPendingTool] or [cancelPendingTool].
     */
    private val _pendingTool = MutableStateFlow<PendingTool?>(null)
    val pendingTool: StateFlow<PendingTool?> = _pendingTool.asStateFlow()

    fun setSaveFunction(fn: suspend (ByteArray) -> Unit) {
        saveFn = fn
    }

    fun setCheckFingerprintFunction(fn: suspend () -> Boolean) {
        checkFingerprintFn = fn
    }

    fun setReloadFunction(fn: suspend () -> Unit) {
        reloadFn = fn
    }

    fun setCurrentFileName(name: String) {
        currentFileName = name
    }

    /** Called on resume; transitions to ExternallyChanged if the file has been modified on disk.
     *  No-ops when already in ExternallyChanged or when no document is loaded. */
    fun checkForExternalChanges() {
        val fn = checkFingerprintFn ?: return
        // Skip if not in a state where a live document is open and editable.
        val current = _uiState.value
        if (current !is EditorUiState.Loaded) return
        viewModelScope.launch {
            if (fn()) {
                _uiState.value = EditorUiState.ExternallyChanged(fileName = currentFileName)
            }
        }
    }

    /** Keep current edits — dismiss the banner and return to Loaded state. */
    fun dismissExternalChange() {
        val state = editorState ?: return
        _uiState.value = EditorUiState.Loaded(state)
    }

    /** Reload from disk — delegates to the injected reload function provided by the NavGraph. */
    fun reloadFromDisk() {
        val fn = reloadFn ?: return
        viewModelScope.launch {
            try {
                fn()
            } catch (e: IOException) {
                _uiState.value = EditorUiState.Error("Reload failed: ${e.message}")
            } catch (e: SecurityException) {
                _uiState.value = EditorUiState.Error("Reload failed: ${e.message}")
            } catch (e: IllegalStateException) {
                _uiState.value = EditorUiState.Error("Reload failed: ${e.message}")
            }
        }
    }

    /** Observable dirty flag — updated by collecting the document's change flow. */
    var isDirty by mutableStateOf(false)
        private set

    // Find/replace state
    var findMatches by mutableStateOf<List<FindReplace.Match>>(emptyList())
        private set
    var currentMatchIndex by mutableIntStateOf(0)
        private set
    var findOptions by mutableStateOf(FindReplace.FindOptions())
        private set

    /** Called by the NavGraph when the file size exceeds DocumentLimits. Content is never read. */
    fun signalOverThreshold(fileName: String, fileBytes: Long, limitBytes: Long) {
        _uiState.value = EditorUiState.OverThreshold(fileName, fileBytes, limitBytes)
    }

    fun openDocument(
        content: String,
        encoding: String = "UTF-8",
        lineEnding: LineEnding = LineEnding.LF,
        readOnly: Boolean = false,
        fileName: String = "",
    ) {
        if (fileName.isNotBlank()) currentFileName = fileName
        val doc = PieceTableDocument.create(content, encoding, lineEnding)
        val state = EditorState(doc)
        state.readOnly = readOnly
        editorState = state
        isDirty = false
        _uiState.value = EditorUiState.Loaded(state)
        // Collect document changes to keep isDirty observable by Compose.
        try {
            viewModelScope.launch {
                doc.changes.collect {
                    isDirty = doc.dirty
                }
            }
        } catch (_: IllegalStateException) {
            // viewModelScope unavailable in unit tests without Dispatchers.Main.
            // isDirty will be updated manually via save() in that case.
        }
    }

    /**
     * Open a pre-constructed TextDocument (e.g. LargeFileDocument for large files).
     * The document is used directly — no PieceTableDocument.create() step.
     */
    fun openLargeDocument(
        document: TextDocument,
        readOnly: Boolean = true,
        fileName: String = "",
    ) {
        if (fileName.isNotBlank()) currentFileName = fileName
        val state = EditorState(document)
        state.readOnly = readOnly
        editorState = state
        isDirty = false
        _uiState.value = EditorUiState.Loaded(state)
        // Read-only documents don't need dirty tracking — skip changes collection.
    }

    fun getContent(): String = editorState?.document?.text() ?: ""

    /**
     * Materialise the document and write it back to the source via the injected
     * save function. The save function is provided by the NavGraph (app layer)
     * so that feature:editor remains free of Android framework dependencies.
     */
    fun save() {
        val state = editorState ?: return
        val fn = saveFn ?: return
        viewModelScope.launch {
            // R-22: check for external changes before writing to avoid silently overwriting
            val fingerprintFn = checkFingerprintFn
            if (fingerprintFn != null && fingerprintFn()) {
                _uiState.value = EditorUiState.ExternallyChanged(fileName = currentFileName)
                return@launch
            }
            try {
                _uiState.value = EditorUiState.Saving
                val baos = ByteArrayOutputStream()
                state.document.materialise(Channels.newChannel(baos))
                fn(baos.toByteArray())
                (state.document as? PieceTableDocument)?.markSaved()
                isDirty = false
                _uiState.value = EditorUiState.Loaded(state)
            } catch (e: IOException) {
                _uiState.value = EditorUiState.Error("Save failed: ${e.message}")
            } catch (e: SecurityException) {
                _uiState.value = EditorUiState.Error("Save failed: ${e.message}")
            } catch (e: IllegalStateException) {
                _uiState.value = EditorUiState.Error("Save failed: ${e.message}")
            }
        }
    }

    fun undo() { editorState?.document?.undo() }
    fun redo() { editorState?.document?.redo() }

    fun goToLine(line: Long) {
        val state = editorState ?: return
        state.moveCaret(line, 0)
        state.firstVisibleLine = maxOf(0L, line - 5)
    }

    // ── Find/Replace ──

    fun search(query: String) {
        val doc = editorState?.document ?: return
        if (query.isBlank()) { findMatches = emptyList(); return }
        val result = FindReplace.findAll(doc, query, findOptions)
        findMatches = result.matches
        currentMatchIndex = 0
        // Navigate to first match
        if (findMatches.isNotEmpty()) {
            val m = findMatches[0]
            editorState?.moveCaret(m.line, m.startColumn)
            editorState?.firstVisibleLine = maxOf(0L, m.line - 5)
        }
    }

    fun findNext() {
        if (findMatches.isEmpty()) return
        currentMatchIndex = (currentMatchIndex + 1) % findMatches.size
        val m = findMatches[currentMatchIndex]
        editorState?.moveCaret(m.line, m.startColumn)
        editorState?.firstVisibleLine = maxOf(0L, m.line - 5)
    }

    fun findPrev() {
        if (findMatches.isEmpty()) return
        currentMatchIndex = (currentMatchIndex - 1 + findMatches.size) % findMatches.size
        val m = findMatches[currentMatchIndex]
        editorState?.moveCaret(m.line, m.startColumn)
        editorState?.firstVisibleLine = maxOf(0L, m.line - 5)
    }

    fun replaceOne(replacement: String) {
        if (findMatches.isEmpty()) return
        val doc = editorState?.document ?: return
        val m = findMatches[currentMatchIndex]
        val lineText = doc.line(m.line).toString()
        val newLine = lineText.substring(0, m.startColumn) + replacement + lineText.substring(m.endColumn)
        doc.edit(m.line..m.line, newLine)
        // Re-search
        search(findMatches.firstOrNull()?.text ?: "")
    }

    fun replaceAll(query: String, replacement: String) {
        val doc = editorState?.document ?: return
        val fullText = doc.text()
        val result = FindReplace.findAll(doc, query, findOptions)
        if (result.matches.isEmpty()) return
        // Build the replaced content and apply as a single undoable operation
        val newText = FindReplace.replaceAllInText(fullText, result.matches, replacement)
        doc.replaceAll(0, doc.length, newText)
        _uiState.value = EditorUiState.Loaded(editorState!!)
        findMatches = emptyList()
    }

    fun updateFindOptions(caseSensitive: Boolean, wholeWord: Boolean, regex: Boolean) {
        findOptions = FindReplace.FindOptions(caseSensitive, wholeWord, regex)
    }

    // ── Text Tools (R-36b: selection-scoped) ──────────────────────────────

    fun sortLines() = applyTextTool("Sort lines") { text ->
        val lines = text.split("\n")
        TextTools.sortLines(lines).joinToString("\n")
    }

    fun deduplicateLines() = applyTextTool("Remove duplicates") { text ->
        val lines = text.split("\n")
        TextTools.deduplicateLines(lines).joinToString("\n")
    }

    fun trimTrailing() = applyTextTool("Trim trailing spaces") { text ->
        val lines = text.split("\n")
        TextTools.trimTrailingWhitespace(lines).joinToString("\n")
    }

    fun reverseLines() = applyTextTool("Reverse lines") { text ->
        val lines = text.split("\n")
        TextTools.reverseLines(lines).joinToString("\n")
    }

    fun removeBlankLines() = applyTextTool("Remove blank lines") { text ->
        val lines = text.split("\n")
        TextTools.removeBlankLines(lines).joinToString("\n")
    }

    fun tabsToSpaces() = applyTextTool("Tabs to spaces") { text ->
        val lines = text.split("\n")
        TextTools.tabsToSpaces(lines).joinToString("\n")
    }

    fun spacesToTabs() = applyTextTool("Spaces to tabs") { text ->
        val lines = text.split("\n")
        TextTools.spacesToTabs(lines).joinToString("\n")
    }

    /** Join all lines into one line, separated by a space. */
    fun joinLines() = applyTextTool("Join lines") { text ->
        val lines = text.split("\n")
        TextTools.joinLines(lines, " ")
    }

    /**
     * Split lines: each line is split at [delimiter], producing multiple lines.
     * If [delimiter] is empty this is a no-op.
     */
    fun splitLines(delimiter: String) = applyTextTool("Split lines") { text ->
        if (delimiter.isEmpty()) text
        else {
            val lines = text.split("\n")
            lines.flatMap { TextTools.splitLine(it, delimiter) }.joinToString("\n")
        }
    }

    // ── Case conversion (R-36b, ships in v0.2 per spec §2.3) ─────────────

    fun toUpperCase() = applyTextTool("To uppercase") { text -> text.uppercase() }

    fun toLowerCase() = applyTextTool("To lowercase") { text -> text.lowercase() }

    fun toTitleCase() = applyTextTool("To title case") { text ->
        text.split("\n").joinToString("\n") { line ->
            line.split(" ").joinToString(" ") { word ->
                if (word.isEmpty()) word else word[0].uppercaseChar() + word.substring(1).lowercase()
            }
        }
    }

    // ── Bookmarks (R-36b) ─────────────────────────────────────────────────

    fun toggleBookmark() { editorState?.toggleBookmark() }

    fun nextBookmark() { editorState?.nextBookmark() }

    fun prevBookmark() { editorState?.prevBookmark() }

    // ── Column select (R-36b) ─────────────────────────────────────────────

    fun toggleColumnSelectMode() { editorState?.toggleColumnSelectMode() }

    /**
     * Convert line endings in the whole document.
     * [to] is one of "\r\n" (CRLF), "\n" (LF), "\r" (CR).
     */
    fun convertLineEnding(to: String) {
        val doc = editorState?.document ?: return
        val currentText = doc.text()
        val newText = TextTools.convertLineEndings(currentText, to)
        if (newText == currentText) return
        doc.replaceAll(0, doc.length, newText)
        _uiState.value = EditorUiState.Loaded(editorState!!)
    }

    /**
     * Re-encode the document text from [fromCharset] to [toCharset].
     * No-ops and emits an error state if conversion fails.
     */
    fun convertEncoding(fromCharset: String, toCharset: String) {
        val doc = editorState?.document ?: return
        val currentText = doc.text()
        val converted = TextTools.convertEncoding(currentText, fromCharset, toCharset)
        if (converted == null) {
            _uiState.value = EditorUiState.Error("Encoding conversion from $fromCharset to $toCharset failed")
            return
        }
        if (converted == currentText) return
        doc.replaceAll(0, doc.length, converted)
        _uiState.value = EditorUiState.Loaded(editorState!!)
    }

    /** User confirmed the pending destructive tool — apply it now. */
    fun confirmPendingTool() {
        _pendingTool.value?.apply?.invoke()
        _pendingTool.value = null
    }

    /** User cancelled the pending destructive tool. */
    fun cancelPendingTool() {
        _pendingTool.value = null
    }

    // ── Internal helpers ──

    /**
     * Apply a text transform as a single undo step.
     *
     * When a selection is active, the transform is applied to the selected text only
     * (inline replacement, no confirmation dialog needed — the scope is already limited).
     * When no selection is active, delegates to [applyTextToolWithConfirmation] which
     * shows a confirmation dialog for whole-document transforms that change >50% of lines.
     *
     * [label] is shown in the confirmation message (whole-doc path only).
     * [transform] receives the text to transform and returns the replacement.
     */
    internal fun applyTextTool(label: String, transform: (String) -> String) {
        val state = editorState ?: return
        if (state.hasSelection) {
            // Selection-scoped: replace selection in one document.edit() call = single undo step
            val doc = state.document
            val anchorLine = state.selectionAnchorLine ?: return
            val anchorCol = state.selectionAnchorColumn ?: return

            val (startLine, startCol, endLine, endCol) = if (
                anchorLine < state.caretLine ||
                (anchorLine == state.caretLine && anchorCol < state.caretColumn)
            ) {
                listOf(anchorLine, anchorCol.toLong(), state.caretLine, state.caretColumn.toLong())
            } else {
                listOf(state.caretLine, state.caretColumn.toLong(), anchorLine, anchorCol.toLong())
            }

            val selectedText = state.selectedText()
            val transformed = transform(selectedText)
            if (transformed == selectedText) return

            // Build replacement: prefix + transformed + suffix (single document edit = one undo step)
            val prefix = doc.line(startLine).substring(0, startCol.toInt())
            val endLineText = doc.line(endLine)
            val suffix = endLineText.substring(endCol.toInt().coerceAtMost(endLineText.length))
            val replacement = prefix + transformed + suffix

            // If transformed spans multiple lines, the edit range must cover endLine
            val transformedNewlines = transformed.count { it == '\n' }
            val editEndLine = if (transformedNewlines > 0 || startLine != endLine) endLine else startLine
            doc.edit(startLine..editEndLine, replacement)

            // Place caret at end of transformed text
            val newLines = transformed.count { it == '\n' }
            if (newLines > 0) {
                state.caretLine = startLine + newLines
                state.caretColumn = transformed.length - transformed.lastIndexOf('\n') - 1
            } else {
                state.caretLine = startLine
                state.caretColumn = startCol.toInt() + transformed.length
            }
            state.clearSelection()
            _uiState.value = EditorUiState.Loaded(state)
        } else {
            // Whole-document path: use counted confirmation
            applyTextToolWithConfirmation(label) { lines ->
                transform(lines.joinToString("\n"))
            }
        }
    }

    /**
     * Apply a line-based transform as a single undo step, with a counted confirmation dialog
     * when the transform would change more than half the document's lines (spec §2.3).
     *
     * [label] is shown in the confirmation message.
     * [transform] receives all lines and returns the full replacement text.
     */
    internal fun applyTextToolWithConfirmation(label: String, transform: (List<String>) -> String) {
        val doc = editorState?.document ?: return
        val lines = (0 until doc.lineCount).map { doc.line(it).toString() }
        val newText = transform(lines)
        val changedLines = countChangedLines(lines.joinToString("\n"), newText)
        val totalLines = doc.lineCount.toInt()

        if (changedLines > totalLines / 2) {
            _pendingTool.value = PendingTool(
                label = label,
                changedLines = changedLines,
                totalLines = totalLines,
                apply = {
                    doc.replaceAll(0, doc.length, newText)
                    _uiState.value = EditorUiState.Loaded(editorState!!)
                },
            )
        } else {
            doc.replaceAll(0, doc.length, newText)
            _uiState.value = EditorUiState.Loaded(editorState!!)
        }
    }

    /**
     * Count the number of lines that differ between [oldText] and [newText].
     * Uses line-by-line comparison; lines present in one but not the other are all counted.
     */
    internal fun countChangedLines(oldText: String, newText: String): Int {
        val oldLines = oldText.split("\n")
        val newLines = newText.split("\n")
        val maxLen = maxOf(oldLines.size, newLines.size)
        var changed = 0
        for (i in 0 until maxLen) {
            val old = oldLines.getOrNull(i)
            val new = newLines.getOrNull(i)
            if (old != new) changed++
        }
        return changed
    }
}

sealed interface EditorUiState {
    data object Empty : EditorUiState
    data object Saving : EditorUiState
    data class Loaded(val editorState: EditorState) : EditorUiState
    data class Error(val message: String) : EditorUiState
    /** File exceeded DocumentLimits. Content was never read. */
    data class OverThreshold(
        val fileName: String,
        val fileBytes: Long,
        val limitBytes: Long,
    ) : EditorUiState
    /**
     * A crash-recovery journal was found for this document (S-04 in spec §13).
     * The UI should offer to restore the unsaved changes from the previous session.
     * Detection and recovery logic are deferred to Journal integration; this state
     * variant is stubbed here so the sealed interface is complete.
     */
    data class RecoveryAvailable(val documentId: String) : EditorUiState
    /**
     * The file on disk has been modified since it was opened (R-22).
     * The editor shows a banner offering Reload or Keep mine.
     */
    data class ExternallyChanged(val fileName: String) : EditorUiState
}
