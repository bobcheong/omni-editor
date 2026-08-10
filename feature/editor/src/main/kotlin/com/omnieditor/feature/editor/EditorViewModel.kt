package com.omnieditor.feature.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnieditor.core.io.FindReplace
import com.omnieditor.core.io.PieceTable
import com.omnieditor.core.io.PieceTableDocument
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
        _uiState.value = EditorUiState.Loaded(state)
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
                state.document.markSaved()
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
        val table = getPieceTable(doc)
        if (table != null) {
            val result = FindReplace.replaceAll(
                table, query, replacement, findOptions,
                lineCount = doc.lineCount,
                lineReader = { doc.line(it) },
            )
            // Force UI refresh
            _uiState.value = EditorUiState.Loaded(editorState!!)
            findMatches = emptyList()
        }
    }

    fun updateFindOptions(caseSensitive: Boolean, wholeWord: Boolean, regex: Boolean) {
        findOptions = FindReplace.FindOptions(caseSensitive, wholeWord, regex)
    }

    // ── Text Tools ──

    fun sortLines() = applyTextTool { TextTools.sortLines(it).joinToString("\n") }
    fun deduplicateLines() = applyTextTool { TextTools.deduplicateLines(it).joinToString("\n") }
    fun trimTrailing() = applyTextTool { TextTools.trimTrailingWhitespace(it).joinToString("\n") }
    fun toUpperCase() = applyTextTool { lines -> lines.map { it.uppercase() }.joinToString("\n") }
    fun toLowerCase() = applyTextTool { lines -> lines.map { it.lowercase() }.joinToString("\n") }
    fun reverseLines() = applyTextTool { TextTools.reverseLines(it).joinToString("\n") }
    fun removeBlankLines() = applyTextTool { TextTools.removeBlankLines(it).joinToString("\n") }
    fun tabsToSpaces() = applyTextTool { TextTools.tabsToSpaces(it).joinToString("\n") }
    fun spacesToTabs() = applyTextTool { TextTools.spacesToTabs(it).joinToString("\n") }

    private fun applyTextTool(transform: (List<String>) -> String) {
        val doc = editorState?.document ?: return
        val table = getPieceTable(doc)
        if (table != null) {
            val lines = (0 until doc.lineCount).map { doc.line(it).toString() }
            val result = transform(lines)
            table.replace(0, table.length, result)
            _uiState.value = EditorUiState.Loaded(editorState!!)
        }
    }

    private fun getPieceTable(doc: PieceTableDocument): PieceTable? {
        return try {
            val field = PieceTableDocument::class.java.getDeclaredField("table")
            field.isAccessible = true
            field.get(doc) as PieceTable
        } catch (_: Exception) { null }
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
