package com.omnieditor.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnieditor.core.io.PieceTableDocument
import com.omnieditor.core.model.LineEnding
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import javax.inject.Inject

/**
 * ViewModel for the editor screen (S-04).
 *
 * Manages the document lifecycle: open, edit, save, undo/redo.
 * The [EditorState] is created here and exposed to the UI.
 */
@HiltViewModel
class EditorViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.Empty)
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var editorState: EditorState? = null

    /**
     * Open a document with the given content.
     */
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

    /**
     * Get the document content as a string (for save).
     */
    fun getContent(): String {
        return editorState?.document?.text() ?: ""
    }

    /**
     * Save the document by materialising the piece table.
     */
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
        state.firstVisibleLine = maxOf(0L, line - 5) // scroll with context
    }
}

sealed interface EditorUiState {
    data object Empty : EditorUiState
    data class Loaded(val editorState: EditorState) : EditorUiState
    data class Error(val message: String) : EditorUiState
}
