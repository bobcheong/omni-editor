package com.omnieditor.feature.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatIndentDecrease
import androidx.compose.material.icons.filled.FormatIndentIncrease
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Persistent bottom touch bar with scrollable icon buttons for common
 * editing operations (#12).
 *
 * Groups: clipboard, undo/redo, line ops, indent, comment, find.
 */
@Suppress("LongParameterList")
@Composable
fun EditorTouchBar(
    canUndo: Boolean,
    canRedo: Boolean,
    hasSelection: Boolean,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDeleteLine: () -> Unit,
    onDuplicateLine: () -> Unit,
    onInsertLineAbove: () -> Unit,
    onInsertLineBelow: () -> Unit,
    onMoveLineUp: () -> Unit,
    onMoveLineDown: () -> Unit,
    onIndent: () -> Unit,
    onOutdent: () -> Unit,
    onToggleComment: () -> Unit,
    onFind: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Clipboard group
            TouchBarButton(Icons.Default.ContentCut, "Cut", onCut)
            TouchBarButton(Icons.Default.ContentCopy, "Copy", onCopy)
            TouchBarButton(Icons.Default.ContentPaste, "Paste", onPaste)
            TouchBarButton(Icons.Default.SelectAll, "Select All", onSelectAll)

            VerticalDivider(modifier = Modifier.height(32.dp))

            // Undo group
            TouchBarButton(Icons.Default.Undo, "Undo", onUndo, enabled = canUndo)
            TouchBarButton(Icons.Default.Redo, "Redo", onRedo, enabled = canRedo)

            VerticalDivider(modifier = Modifier.height(32.dp))

            // Line ops group
            TouchBarButton(Icons.Default.Delete, "Delete Line", onDeleteLine)
            TouchBarButton(Icons.Default.ContentCopy, "Duplicate Line", onDuplicateLine)
            TouchBarButton(Icons.Default.VerticalAlignTop, "Insert Above", onInsertLineAbove)
            TouchBarButton(Icons.Default.VerticalAlignBottom, "Insert Below", onInsertLineBelow)
            TouchBarButton(Icons.Default.ArrowUpward, "Move Up", onMoveLineUp)
            TouchBarButton(Icons.Default.ArrowDownward, "Move Down", onMoveLineDown)

            VerticalDivider(modifier = Modifier.height(32.dp))

            // Indent group
            TouchBarButton(Icons.Default.FormatIndentIncrease, "Indent", onIndent)
            TouchBarButton(Icons.Default.FormatIndentDecrease, "Outdent", onOutdent)

            VerticalDivider(modifier = Modifier.height(32.dp))

            // Code group
            TouchBarButton(Icons.Default.Code, "Comment", onToggleComment)

            VerticalDivider(modifier = Modifier.height(32.dp))

            // Nav group
            TouchBarButton(Icons.Default.Search, "Find", onFind)
        }
    }
}

@Composable
private fun TouchBarButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp),
    ) {
        Icon(icon, description, modifier = Modifier.size(20.dp))
    }
}

/**
 * Return the line-comment prefix for a file extension.
 * Used by the editor to pass the correct prefix to [EditorState.toggleComment].
 */
fun commentPrefixForExtension(ext: String): String = when (ext.lowercase()) {
    "kt", "kts", "java", "js", "ts", "c", "cpp", "h", "cs", "go", "rs", "swift" -> "//"
    "py", "rb", "sh", "bash", "zsh", "yaml", "yml", "toml" -> "#"
    "sql", "lua" -> "--"
    "css" -> "/*"
    else -> "//"
}
