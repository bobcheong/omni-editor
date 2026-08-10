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
import java.nio.channels.Channels
import javax.inject.Inject

@HiltViewModel
class EditorViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.Empty)
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var editorState: EditorState? = null

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
    ) {
        val doc = PieceTableDocument.create(content, encoding, lineEnding)
        val state = EditorState(doc)
        state.readOnly = readOnly
        editorState = state
        _uiState.value = EditorUiState.Loaded(state)
    }

    fun getContent(): String = editorState?.document?.text() ?: ""

    fun save(callback: (ByteArray) -> Unit) {
        val state = editorState ?: return
        viewModelScope.launch {
            val baos = ByteArrayOutputStream()
            state.document.materialise(Channels.newChannel(baos))
            callback(baos.toByteArray())
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
    data class Loaded(val editorState: EditorState) : EditorUiState
    data class Error(val message: String) : EditorUiState
    /** File exceeded DocumentLimits. Content was never read. */
    data class OverThreshold(
        val fileName: String,
        val fileBytes: Long,
        val limitBytes: Long,
    ) : EditorUiState
}
