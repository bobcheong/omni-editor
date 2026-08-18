package com.omnieditor.app

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Sort modes for the file browser list. */
enum class SortMode { NAME, SIZE, DATE }

/**
 * Minimum viable file browser for the direct flavour (ADR-009).
 *
 * Shows a flat list of files in a single directory with a path breadcrumb,
 * sort control, and tap-to-select. Tree navigation, favourites and hidden-file
 * toggle are deferred to P2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    onFilePicked: (File) -> Unit,
    onNavigateBack: () -> Unit,
) {
    var currentDir by remember {
        @Suppress("DEPRECATION") // Environment.getExternalStorageDirectory is deprecated but
        // we need a sensible starting point for the direct flavour's filesystem browser.
        mutableStateOf(Environment.getExternalStorageDirectory())
    }
    var sortMode by remember { mutableStateOf(SortMode.NAME) }
    var showSortMenu by remember { mutableStateOf(false) }

    val entries = remember(currentDir, sortMode) {
        val files = currentDir.listFiles()?.toList() ?: emptyList()
        val sorted = when (sortMode) {
            SortMode.NAME -> files.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            SortMode.SIZE -> files.sortedWith(compareBy<File> { !it.isDirectory }.thenByDescending { it.length() })
            SortMode.DATE -> files.sortedWith(compareBy<File> { !it.isDirectory }.thenByDescending { it.lastModified() })
        }
        sorted
    }

    val breadcrumbParts = remember(currentDir) {
        val root = File("/")
        val parts = mutableListOf<Pair<String, File>>()
        var f: File? = currentDir
        while (f != null && f != root) {
            parts.add(0, f.name to f)
            f = f.parentFile
        }
        parts.add(0, "/" to root)
        parts
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Browse files") },
                navigationIcon = {
                    IconButton(onClick = {
                        val parent = currentDir.parentFile
                        if (parent != null && parent.canRead()) {
                            currentDir = parent
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back / Up")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
        ) {
            // Breadcrumb
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                breadcrumbParts.forEachIndexed { index, (name, dir) ->
                    if (index > 0) {
                        Text(
                            " > ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (dir == currentDir) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.clickable {
                            if (dir.canRead()) currentDir = dir
                        },
                    )
                }
            }

            HorizontalDivider()

            // Sort control
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${entries.size} items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { showSortMenu = true }) {
                    Text("Sort: ${sortMode.name.lowercase()}")
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                    ) {
                        SortMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.name.lowercase()) },
                                onClick = {
                                    sortMode = mode
                                    showSortMenu = false
                                },
                            )
                        }
                    }
                }
            }

            // File list
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries, key = { it.absolutePath }) { file ->
                    FileRow(
                        file = file,
                        onClick = {
                            if (file.isDirectory) {
                                if (file.canRead()) currentDir = file
                            } else {
                                onFilePicked(file)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FileRow(file: File, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (file.isDirectory) Icons.Default.Folder
            else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = if (file.isDirectory) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!file.isDirectory) {
                Text(
                    text = "${formatSize(file.length())} · ${formatDate(file.lastModified())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
}

private fun formatDate(millis: Long): String {
    if (millis <= 0) return ""
    return DateFormat.getDateInstance(DateFormat.SHORT).format(Date(millis))
}
