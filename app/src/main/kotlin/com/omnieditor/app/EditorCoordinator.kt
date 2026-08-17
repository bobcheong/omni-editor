package com.omnieditor.app

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import com.omnieditor.core.model.DocumentLimits
import com.omnieditor.feature.editor.EditorScreen
import com.omnieditor.feature.editor.EditorSettingsState
import com.omnieditor.feature.editor.EditorUiState
import com.omnieditor.feature.editor.EditorViewModel
import com.omnieditor.feature.editor.TabInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

// ---------------------------------------------------------------------------
// Internal helpers — live in the app layer where ContentResolver is available.
// ---------------------------------------------------------------------------

/**
 * Reads a URI from the ContentResolver on whichever dispatcher the caller
 * chooses (callers must dispatch to IO). Stores the result in [DocumentRegistry]
 * keyed by [id]. Returns the [DocumentRegistry.LoadedDocument] or null on error.
 *
 * Error text is NOT stuffed into the document body — errors surface as null so
 * callers can decide what to display.
 */
internal fun readUriIntoRegistry(
    context: android.content.Context,
    uri: Uri,
    id: String,
): DocumentRegistry.LoadedDocument? {
    val (label, sizeBytes) = queryUriMeta(context, uri)
    val text = try {
        context.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader().readText()
        } ?: return null
    } catch (_: IOException) {
        return null
    }
    val doc = DocumentRegistry.LoadedDocument(
        id = id,
        text = text,
        label = label,
        uri = uri.toString(),
        sizeBytes = sizeBytes,
    )
    DocumentRegistry.put(doc)
    return doc
}

/** Query display name and byte size from ContentResolver in a single pass. */
internal fun queryUriMeta(context: android.content.Context, uri: Uri): Pair<String, Long> {
    try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                val label = if (!name.isNullOrBlank()) name else uriFallbackLabel(uri)
                return Pair(label, size)
            }
        }
    } catch (_: Exception) { }
    return Pair(uriFallbackLabel(uri), -1L)
}

internal fun uriFallbackLabel(uri: Uri): String {
    val path = uri.path ?: uri.toString()
    return path.substringAfterLast('/').substringAfterLast(':').ifBlank { "file" }
}

/** Editor route body extracted to keep OmniNavGraph within complexity budget. */
@Suppress("LongParameterList")
@Composable
internal fun EditorDestination(
    contentKey: String,
    tabs: List<TabInfo> = emptyList(),
    activeTabId: String? = null,
    onTabSelected: (String) -> Unit = {},
    onTabClosed: (String) -> Unit = {},
    onNewTab: () -> Unit = {},
    onNavigateBack: () -> Unit,
    onCompareWith: () -> Unit,
    settingsState: EditorSettingsState = EditorSettingsState(),
    onToggleWordWrap: () -> Unit = {},
    onToggleLineNumbers: () -> Unit = {},
    onToggleWhitespace: () -> Unit = {},
    onIncreaseFontSize: () -> Unit = {},
    onDecreaseFontSize: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val cached = DocumentRegistry.get(contentKey)
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel: EditorViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    // Save As: launch document creator to pick destination, then write content
    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val content = viewModel.getContent()
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                            out.write(content.toByteArray())
                            out.flush()
                        }
                    }
                } catch (_: Exception) {
                    // Write failure — silently handled for now
                }
            }
        }
    }

    // R-34b: pin this document so LRU eviction cannot remove it while open.
    LaunchedEffect(contentKey) {
        DocumentRegistry.pin(contentKey)
    }

    // R-22: snapshot of the file's size and modification time taken when the file is opened.
    // Stored as state so they survive recomposition.
    var initialSize by remember { mutableLongStateOf(-1L) }
    var initialModified by remember { mutableLongStateOf(-1L) }

    LaunchedEffect(contentKey) {
        if (uiState is EditorUiState.Empty && cached != null) {
            val size = cached.sizeBytes
            when (DocumentLimits.editorTier(size)) {
                DocumentLimits.SizeTier.FULL_MEMORY -> {
                    viewModel.openDocument(cached.text, fileName = cached.label)
                }
                DocumentLimits.SizeTier.INDEXED_READ_ONLY -> {
                    // F-01: Large file — open via LargeFileDocument (read-only).
                    val sourceUri = cached.uri
                    val uri = Uri.parse(sourceUri)
                    try {
                        val largeDoc = withContext(Dispatchers.IO) {
                            // Copy URI content to a cache file for FileIndexer (needs java.io.File)
                            val cacheFile = java.io.File(
                                context.cacheDir,
                                "large-${contentKey.hashCode().toUInt()}.tmp",
                            )
                            if (!cacheFile.exists()) {
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                                }
                            }
                            com.omnieditor.core.io.LargeFileDocument.open(cacheFile)
                        }
                        viewModel.openLargeDocument(
                            document = largeDoc,
                            readOnly = true,
                            fileName = cached.label + " (read-only)",
                        )
                    } catch (e: Exception) {
                        viewModel.signalOverThreshold(
                            fileName = cached.label,
                            fileBytes = size,
                            limitBytes = DocumentLimits.INDEXED_MAX_BYTES,
                        )
                    }
                }
                DocumentLimits.SizeTier.REFUSED -> {
                    // R-12: refuse oversized files; content was never read from disk.
                    viewModel.signalOverThreshold(
                        fileName = cached.label,
                        fileBytes = size,
                        limitBytes = DocumentLimits.INDEXED_MAX_BYTES,
                    )
                }
            }
        }

        val sourceUri = cached?.uri
        if (sourceUri != null) {
            val uri = Uri.parse(sourceUri)

            // R-22: capture initial fingerprint (size + lastModified) from ContentResolver.
            withContext(Dispatchers.IO) {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        initialSize = cursor.getLong(0)
                        initialModified = cursor.getLong(1)
                    }
                }
            }

            // R-22: inject fingerprint check function — compares current metadata to the snapshot.
            viewModel.setCheckFingerprintFunction {
                withContext(Dispatchers.IO) {
                    var currentSize = -1L
                    var currentModified = -1L
                    context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                        null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            currentSize = cursor.getLong(0)
                            currentModified = cursor.getLong(1)
                        }
                    }
                    // Only report changed when we have valid snapshots (both > -1)
                    initialSize >= 0 &&
                        (currentSize != initialSize || currentModified != initialModified)
                }
            }

            // R-20: inject save function so the editor can write back to the source URI.
            // The lambda runs in the app layer where ContentResolver is available,
            // keeping feature:editor free of Android framework dependencies.
            viewModel.setSaveFunction { bytes ->
                withContext(Dispatchers.IO) {
                    if (uri.scheme == "file") {
                        // Direct flavour: write to filesystem path directly.
                        // ContentResolver.openOutputStream does not support file:// URIs
                        // on modern Android (API 31+).
                        val file = java.io.File(uri.path!!)
                        file.writeBytes(bytes)
                    } else {
                        // Store flavour: write through ContentResolver (content:// URIs).
                        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                            out.write(bytes)
                            out.flush()
                        } ?: throw java.io.IOException("Cannot open $sourceUri for writing")
                    }
                    // Update fingerprint snapshot after a successful save so the next
                    // resume check doesn't false-positive on our own write.
                    try {
                        context.contentResolver.query(
                            uri,
                            arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                            null, null, null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                initialSize = cursor.getLong(0)
                                initialModified = cursor.getLong(1)
                            }
                        }
                    } catch (_: Exception) {
                        // file:// URIs may not support ContentResolver queries — that's OK,
                        // the file is already written.
                        if (uri.scheme == "file") {
                            val file = java.io.File(uri.path!!)
                            initialSize = file.length()
                            initialModified = file.lastModified()
                        }
                    }
                }
            }

            // R-22: inject reload function — re-reads file content and re-opens document.
            viewModel.setReloadFunction {
                val reloaded = withContext(Dispatchers.IO) {
                    readUriIntoRegistry(context, uri, id = contentKey)
                }
                if (reloaded != null) {
                    // Refresh fingerprint snapshot after reload.
                    withContext(Dispatchers.IO) {
                        context.contentResolver.query(
                            uri,
                            arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                            null, null, null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                initialSize = cursor.getLong(0)
                                initialModified = cursor.getLong(1)
                            }
                        }
                    }
                    viewModel.openDocument(reloaded.text, fileName = reloaded.label)
                }
            }
        }
    }

    // R-22: check for external changes whenever the app comes back to the foreground.
    LifecycleResumeEffect(Unit) {
        viewModel.checkForExternalChanges()
        onPauseOrDispose { }
    }

    EditorScreen(
        fileName = cached?.label ?: "",
        tabs = tabs,
        selectedTabId = activeTabId,
        onTabSelected = onTabSelected,
        onTabClosed = onTabClosed,
        onNewTab = onNewTab,
        onNavigateBack = onNavigateBack,
        onCompareWith = onCompareWith,
        onSaveAs = {
            val name = cached?.label ?: "document.txt"
            saveAsLauncher.launch(name)
        },
        settingsState = settingsState,
        onToggleWordWrap = onToggleWordWrap,
        onToggleLineNumbers = onToggleLineNumbers,
        onToggleWhitespace = onToggleWhitespace,
        onIncreaseFontSize = onIncreaseFontSize,
        onDecreaseFontSize = onDecreaseFontSize,
        onOpenSettings = onOpenSettings,
        viewModel = viewModel,
    )
}
