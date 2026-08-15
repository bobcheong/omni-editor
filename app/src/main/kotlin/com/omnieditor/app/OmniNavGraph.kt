package com.omnieditor.app

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.omnieditor.feature.editor.TabInfo
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavType
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.omnieditor.app.home.HomeScreen
import com.omnieditor.core.diff.ReportGenerator
import com.omnieditor.core.io.MergeSafety
import com.omnieditor.core.io.RecentsStore
import com.omnieditor.core.io.ResultStore
import com.omnieditor.core.io.SessionStore
import com.omnieditor.core.model.CompareMode
import com.omnieditor.core.model.Granularity
import com.omnieditor.core.model.DocumentLimits
import com.omnieditor.core.model.Session
import com.omnieditor.core.model.RuleSet
import com.omnieditor.core.model.SourceKind
import com.omnieditor.core.model.SourceRef
import com.omnieditor.core.io.PieceTableDocument
import com.omnieditor.feature.compare.CompareScreen
import com.omnieditor.feature.compare.CompareSettingsCallbacks
import com.omnieditor.feature.compare.CompareSettingsState
import com.omnieditor.feature.compare.CompareState
import com.omnieditor.feature.editor.EditorScreen
import com.omnieditor.feature.editor.EditorSettingsState
import com.omnieditor.feature.editor.EditorUiState
import com.omnieditor.feature.editor.EditorViewModel
import com.omnieditor.feature.setup.SourceSetupScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

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
private fun readUriIntoRegistry(
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
private fun queryUriMeta(context: android.content.Context, uri: Uri): Pair<String, Long> {
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

private fun uriFallbackLabel(uri: Uri): String {
    val path = uri.path ?: uri.toString()
    return path.substringAfterLast('/').substringAfterLast(':').ifBlank { "file" }
}

/**
 * Loads a document into [DocumentRegistry] from [uriString] if [id] is not already cached.
 * No-op when the document is already present or the URI is null.
 */
private suspend fun reloadIfAbsent(
    context: android.content.Context,
    id: String,
    uriString: String?,
) {
    if (DocumentRegistry.get(id) != null || uriString == null) return
    withContext(Dispatchers.IO) {
        runCatching { readUriIntoRegistry(context, Uri.parse(uriString), id = id) }.getOrNull()
    }
}

// ---------------------------------------------------------------------------
// Nav graph
// ---------------------------------------------------------------------------

@Composable
fun OmniNavGraph(
    navController: NavHostController = rememberNavController(),
    initialAction: IntentRouter.IntentAction = IntentRouter.IntentAction.ShowHome,
) {
    val context = LocalContext.current
    val recentsStore = remember {
        RecentsStore(File(context.filesDir, "recents.json"))
    }
    val sessionStore = remember {
        SessionStore(File(context.filesDir, "sessions"))
    }
    val resultStore = remember {
        ResultStore(File(context.cacheDir, "results"))
    }

    // Settings ViewModel — shared across screens for live toggle state.
    val settingsVm: SettingsViewModel = hiltViewModel()
    val wordWrap by settingsVm.wordWrap.collectAsState()
    val showLineNumbers by settingsVm.showLineNumbers.collectAsState()
    val showWhitespace by settingsVm.showWhitespace.collectAsState()
    val fontSize by settingsVm.fontSize.collectAsState()
    val defaultLayout by settingsVm.defaultLayout.collectAsState()
    val syncScroll by settingsVm.syncScroll.collectAsState()
    val granularity by settingsVm.granularity.collectAsState()

    val editorSettingsState = EditorSettingsState(
        wordWrapEnabled = wordWrap,
        showLineNumbers = showLineNumbers,
        showWhitespace = showWhitespace,
        fontSize = fontSize,
    )
    val compareSettingsState = CompareSettingsState(
        layoutMode = defaultLayout,
        syncScroll = syncScroll,
        granularity = granularity,
    )
    val compareSettingsCallbacks = CompareSettingsCallbacks(
        onSetLayout = { settingsVm.setDefaultLayout(it) },
        onToggleSyncScroll = { settingsVm.setSyncScroll(!syncScroll) },
        onSetGranularity = { settingsVm.setGranularity(it) },
        onOpenSettings = { navController.navigate("settings") },
    )

    // R-34b: shared tab strip state — one list, one active ID, across all screens.
    val tabs = remember { mutableStateListOf<TabInfo>() }
    var activeTabId by remember { mutableStateOf<String?>(null) }

    // R-23a: file browser picked sources, keyed by slot ("left" / "right").
    // Updated by flavourDestinations() callback, read by the setup screen.
    var fileBrowserLeftRef by remember { mutableStateOf<SourceRef?>(null) }
    var fileBrowserRightRef by remember { mutableStateOf<SourceRef?>(null) }
    var fileBrowserThirdRef by remember { mutableStateOf<SourceRef?>(null) }

    // Setup screen state hoisted here so it survives navigation to the file browser.
    // Bug fix: `remember` inside SetupDestination was lost when navigating to filebrowser
    // and back, because Navigation Compose removes composables from composition on navigate.
    var setupLeftSource by remember { mutableStateOf<SourceRef?>(null) }
    var setupRightSource by remember { mutableStateOf<SourceRef?>(null) }
    var setupThirdSource by remember { mutableStateOf<SourceRef?>(null) }
    var setupShowThirdSlot by remember { mutableStateOf(false) }
    var setupLeftKey by remember { mutableStateOf<String?>(null) }
    var setupRightKey by remember { mutableStateOf<String?>(null) }
    var setupThirdKey by remember { mutableStateOf<String?>(null) }

    // R-33: consume initialAction once the nav graph is ready.
    LaunchedEffect(initialAction) {
        when (val action = initialAction) {
            is IntentRouter.IntentAction.ShowHome -> {
                // Already at home — nothing to do.
            }
            is IntentRouter.IntentAction.OpenFile -> {
                // Load the URI into the registry then navigate to the editor.
                val ref = action.source
                val uriString = ref.uriGrant ?: return@LaunchedEffect
                val uri = android.net.Uri.parse(uriString)
                withContext(Dispatchers.IO) {
                    readUriIntoRegistry(context, uri, id = ref.id)
                }
                navController.navigate("editor/${ref.id}")
            }
            is IntentRouter.IntentAction.CompareWithPrompt -> {
                // Load the single source, then navigate to setup with left pre-filled.
                val ref = action.source
                val uriString = ref.uriGrant ?: return@LaunchedEffect
                val uri = android.net.Uri.parse(uriString)
                withContext(Dispatchers.IO) {
                    readUriIntoRegistry(context, uri, id = ref.id)
                }
                navController.navigate("setup?leftKey=${ref.id}")
            }
            is IntentRouter.IntentAction.CompareDirectly -> {
                // Load both sources, then navigate directly to compare.
                val left = action.left
                val right = action.right
                withContext(Dispatchers.IO) {
                    left.uriGrant?.let { readUriIntoRegistry(context, android.net.Uri.parse(it), id = left.id) }
                    right.uriGrant?.let { readUriIntoRegistry(context, android.net.Uri.parse(it), id = right.id) }
                }
                navController.navigate("compare/${left.id}/${right.id}")
            }
            is IntentRouter.IntentAction.SnippetCompare -> {
                // Store the snippet text as an in-memory document, navigate to setup.
                val snippetId = UUID.randomUUID().toString()
                DocumentRegistry.put(
                    DocumentRegistry.LoadedDocument(
                        id = snippetId,
                        text = action.text,
                        label = "snippet",
                        uri = "",
                        sizeBytes = action.text.length.toLong(),
                    )
                )
                navController.navigate("setup?leftKey=$snippetId")
            }
        }
    }

    NavHost(navController = navController, startDestination = flavourStartDestination()) {

        // ── Flavour-specific routes (permission screen, file browser) ──
        flavourDestinations(navController) { slot, ref ->
            when (slot) {
                "left" -> fileBrowserLeftRef = ref
                "right" -> fileBrowserRightRef = ref
                "third" -> fileBrowserThirdRef = ref
            }
        }

        // ── Home ──
        composable("home") {
            HomeDestination(
                recentsStore = recentsStore,
                sessionStore = sessionStore,
                navController = navController,
            )
        }

        // ── Editor ──
        composable("editor/{contentKey}") { backStackEntry ->
            val contentKey = backStackEntry.arguments?.getString("contentKey") ?: ""
            // R-34b: register this document as the active tab.
            LaunchedEffect(contentKey) {
                activeTabId = contentKey
                val label = DocumentRegistry.get(contentKey)?.label ?: contentKey
                val existing = tabs.indexOfFirst { it.id == contentKey }
                val tab = TabInfo(id = contentKey, label = label, dirty = false, isCompare = false)
                if (existing >= 0) tabs[existing] = tab else tabs.add(tab)
            }
            EditorDestination(
                contentKey = contentKey,
                tabs = tabs,
                activeTabId = activeTabId,
                onTabSelected = { id ->
                    activeTabId = id
                    val selectedTab = tabs.find { it.id == id }
                    if (selectedTab?.isCompare == true && id.length == 73) {
                        val lk = id.substring(0, 36)
                        val rk = id.substring(37)
                        navController.navigate("compare/$lk/$rk")
                    } else {
                        navController.navigate("editor/$id")
                    }
                },
                onTabClosed = { id ->
                    tabs.removeAll { it.id == id }
                    DocumentRegistry.unpin(id)
                    if (id == contentKey) navController.popBackStack()
                },
                onNewTab = { navController.navigate("home") },
                onNavigateBack = { navController.popBackStack() },
                onCompareWith = { navController.navigate("setup?leftKey=$contentKey") },
                settingsState = editorSettingsState,
                onToggleWordWrap = { settingsVm.setWordWrap(!wordWrap) },
                onToggleLineNumbers = { settingsVm.setShowLineNumbers(!showLineNumbers) },
                onToggleWhitespace = { settingsVm.setShowWhitespace(!showWhitespace) },
                onIncreaseFontSize = { settingsVm.setFontSize((fontSize + 2).coerceAtMost(32)) },
                onDecreaseFontSize = { settingsVm.setFontSize((fontSize - 2).coerceAtLeast(8)) },
                onOpenSettings = { navController.navigate("settings") },
            )
        }

        // ── Source Setup ──
        composable(
            route = "setup?leftKey={leftKey}",
            arguments = listOf(
                navArgument("leftKey") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            ),
        ) { backStackEntry ->
            val prefilledLeftKey = backStackEntry.arguments?.getString("leftKey")
            // Initialize hoisted state from prefilled key on first entry
            LaunchedEffect(prefilledLeftKey) {
                if (prefilledLeftKey != null && setupLeftKey == null) {
                    setupLeftKey = prefilledLeftKey
                    val doc = DocumentRegistry.get(prefilledLeftKey)
                    if (doc != null) {
                        setupLeftSource = SourceRef(
                            id = prefilledLeftKey,
                            kind = SourceKind.LOCAL,
                            uriGrant = doc.uri,
                            label = doc.label,
                        )
                    }
                }
            }
            SetupDestination(
                leftSource = setupLeftSource,
                rightSource = setupRightSource,
                thirdSource = setupThirdSource,
                showThirdSlot = setupShowThirdSlot || setupThirdSource != null,
                onShowThirdSlot = { setupShowThirdSlot = true },
                leftKey = setupLeftKey,
                rightKey = setupRightKey,
                thirdKey = setupThirdKey,
                onLeftChanged = { key, ref -> setupLeftKey = key; setupLeftSource = ref },
                onRightChanged = { key, ref -> setupRightKey = key; setupRightSource = ref },
                onThirdChanged = { key, ref -> setupThirdKey = key; setupThirdSource = ref },
                recentsStore = recentsStore,
                sessionStore = sessionStore,
                navController = navController,
                fileBrowserLeftRef = fileBrowserLeftRef,
                fileBrowserRightRef = fileBrowserRightRef,
                fileBrowserThirdRef = fileBrowserThirdRef,
                onConsumeLeftRef = { fileBrowserLeftRef = null },
                onConsumeRightRef = { fileBrowserRightRef = null },
                onConsumeThirdRef = { fileBrowserThirdRef = null },
            )
        }

        // ── Compare ──
        composable(
            route = "compare/{leftKey}/{rightKey}?baseKey={baseKey}",
            arguments = listOf(
                navArgument("baseKey") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            ),
        ) { backStackEntry ->
            val leftKey = backStackEntry.arguments?.getString("leftKey") ?: ""
            val rightKey = backStackEntry.arguments?.getString("rightKey") ?: ""
            val baseKey = backStackEntry.arguments?.getString("baseKey")
            val compareTabId = if (baseKey != null) "$leftKey-$rightKey-$baseKey" else "$leftKey-$rightKey"
            // R-34b: register this compare as the active tab.
            LaunchedEffect(compareTabId) {
                activeTabId = compareTabId
                val leftLabel = DocumentRegistry.get(leftKey)?.label ?: "left"
                val rightLabel = DocumentRegistry.get(rightKey)?.label ?: "right"
                val baseLabel = baseKey?.let { DocumentRegistry.get(it)?.label }
                val label = if (baseLabel != null) {
                    "$leftLabel ↔ $rightLabel (base: $baseLabel)"
                } else {
                    "$leftLabel ↔ $rightLabel"
                }
                val existing = tabs.indexOfFirst { it.id == compareTabId }
                val tab = TabInfo(id = compareTabId, label = label, dirty = false, isCompare = true)
                if (existing >= 0) tabs[existing] = tab else tabs.add(tab)
            }
            CompareDestination(
                leftKey = leftKey,
                rightKey = rightKey,
                baseKey = baseKey,
                resultStore = resultStore,
                tabs = tabs,
                activeTabId = activeTabId,
                onTabSelected = { id ->
                    activeTabId = id
                    val selectedTab = tabs.find { it.id == id }
                    if (selectedTab?.isCompare == true && id.length == 73) {
                        val lk = id.substring(0, 36)
                        val rk = id.substring(37)
                        navController.navigate("compare/$lk/$rk")
                    } else {
                        navController.navigate("editor/$id")
                    }
                },
                onTabClosed = { id ->
                    tabs.removeAll { it.id == id }
                    if (id == compareTabId) navController.popBackStack()
                },
                onNewTab = { navController.navigate("home") },
                onNavigateBack = { navController.popBackStack() },
                navController = navController,
                settingsState = compareSettingsState,
                settingsCallbacks = compareSettingsCallbacks,
            )
        }

        // ── Settings ──
        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}

/** Home route body extracted to keep OmniNavGraph within complexity budget. */
@Composable
private fun HomeDestination(
    recentsStore: RecentsStore,
    sessionStore: SessionStore,
    navController: NavHostController,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recentSessions = remember { mutableStateListOf<Session>() }
    val pinnedSessions = remember { mutableStateListOf<Session>() }

    // R-34b: load sessions from SessionStore (preserves correct CompareMode labels).
    LaunchedEffect(Unit) {
        val all = sessionStore.listAll()
        pinnedSessions.clear()
        pinnedSessions.addAll(all.filter { it.pinned })
        recentSessions.clear()
        recentSessions.addAll(all.filter { !it.pinned })
    }

    val openFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            takePersistablePermission(context.contentResolver, uri)
            val sourceId = UUID.randomUUID().toString()
            scope.launch {
                val doc = withContext(Dispatchers.IO) {
                    readUriIntoRegistry(context, uri, id = sourceId)
                }
                val ref = SourceRef(
                    id = sourceId,
                    kind = SourceKind.LOCAL,
                    uriGrant = uri.toString(),
                    label = doc?.label ?: "file",
                )
                recentsStore.addRecent(ref)
                sessionStore.save(Session(
                    id = sourceId,
                    name = ref.label,
                    mode = CompareMode.EDITOR,
                    createdAt = System.currentTimeMillis(),
                    sources = listOf(ref),
                ))
                navController.navigate("editor/$sourceId")
            }
        }
    }

    HomeScreen(
        pinnedSessions = pinnedSessions,
        recentSessions = recentSessions,
        onOpenFile = { openFileLauncher.launch(arrayOf("*/*")) },
        onNewCompare = { navController.navigate("setup") },
        onSessionTap = { sessionId ->
            val session = (pinnedSessions + recentSessions).find { it.id == sessionId }
            if (session != null && session.mode == CompareMode.TEXT) {
                val leftRef = session.sources.getOrNull(0)
                val rightRef = session.sources.getOrNull(1)
                val baseRef = session.sources.getOrNull(2)  // 3-way: third source is the base
                if (leftRef != null && rightRef != null) {
                    scope.launch {
                        reloadIfAbsent(context, leftRef.id, leftRef.uriGrant)
                        reloadIfAbsent(context, rightRef.id, rightRef.uriGrant)
                        baseRef?.let { reloadIfAbsent(context, it.id, it.uriGrant) }
                        val route = if (baseRef != null) {
                            "compare/${leftRef.id}/${rightRef.id}?baseKey=${baseRef.id}"
                        } else {
                            "compare/${leftRef.id}/${rightRef.id}"
                        }
                        navController.navigate(route)
                    }
                }
            } else {
                val cached = DocumentRegistry.get(sessionId)
                if (cached != null) {
                    navController.navigate("editor/$sessionId")
                } else {
                    scope.launch {
                        val storedSession = sessionStore.load(sessionId)
                        val uriString = storedSession?.sources?.firstOrNull()?.uriGrant
                            ?: recentsStore.getRecents().find { it.id == sessionId }?.uriGrant
                        if (uriString != null) {
                            val doc = withContext(Dispatchers.IO) {
                                runCatching {
                                    readUriIntoRegistry(context, Uri.parse(uriString), id = sessionId)
                                }.getOrNull()
                            }
                            if (doc != null) navController.navigate("editor/$sessionId")
                            else openFileLauncher.launch(arrayOf("*/*"))
                        } else {
                            openFileLauncher.launch(arrayOf("*/*"))
                        }
                    }
                }
            }
        },
        onSettings = { navController.navigate("settings") },
    )
}

/** Editor route body extracted to keep OmniNavGraph within complexity budget. */
@Suppress("LongParameterList")
@Composable
private fun EditorDestination(
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
    val context = LocalContext.current
    val viewModel: EditorViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()

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
            if (size > 0 && size > DocumentLimits.EDITOR_MAX_BYTES) {
                // R-12: refuse oversized files; content was never read from disk.
                viewModel.signalOverThreshold(
                    fileName = cached.label,
                    fileBytes = size,
                    limitBytes = DocumentLimits.EDITOR_MAX_BYTES,
                )
            } else {
                viewModel.openDocument(cached.text, fileName = cached.label)
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

/** Setup route body extracted to keep OmniNavGraph within complexity budget. */
@Composable
private fun SetupDestination(
    leftSource: SourceRef?,
    rightSource: SourceRef?,
    thirdSource: SourceRef?,
    showThirdSlot: Boolean = false,
    onShowThirdSlot: () -> Unit = {},
    leftKey: String?,
    rightKey: String?,
    thirdKey: String?,
    onLeftChanged: (String?, SourceRef?) -> Unit,
    onRightChanged: (String?, SourceRef?) -> Unit,
    onThirdChanged: (String?, SourceRef?) -> Unit,
    recentsStore: RecentsStore,
    sessionStore: SessionStore,
    navController: NavHostController,
    fileBrowserLeftRef: SourceRef?,
    fileBrowserRightRef: SourceRef?,
    fileBrowserThirdRef: SourceRef?,
    onConsumeLeftRef: () -> Unit,
    onConsumeRightRef: () -> Unit,
    onConsumeThirdRef: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val leftPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            takePersistablePermission(context.contentResolver, uri)
            // R-34a: SourceRef.id is generated first and used as the registry key.
            val sourceId = UUID.randomUUID().toString()
            scope.launch {
                val doc = withContext(Dispatchers.IO) {
                    readUriIntoRegistry(context, uri, id = sourceId)
                }
                onLeftChanged(sourceId, SourceRef(
                    id = sourceId,
                    kind = SourceKind.LOCAL,
                    uriGrant = uri.toString(),
                    label = doc?.label ?: "left",
                ))
            }
        }
    }

    val rightPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            takePersistablePermission(context.contentResolver, uri)
            // R-34a: SourceRef.id is generated first and used as the registry key.
            val sourceId = UUID.randomUUID().toString()
            scope.launch {
                val doc = withContext(Dispatchers.IO) {
                    readUriIntoRegistry(context, uri, id = sourceId)
                }
                onRightChanged(sourceId, SourceRef(
                    id = sourceId,
                    kind = SourceKind.LOCAL,
                    uriGrant = uri.toString(),
                    label = doc?.label ?: "right",
                ))
            }
        }
    }

    val thirdPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            takePersistablePermission(context.contentResolver, uri)
            val sourceId = UUID.randomUUID().toString()
            scope.launch {
                val doc = withContext(Dispatchers.IO) {
                    readUriIntoRegistry(context, uri, id = sourceId)
                }
                onThirdChanged(sourceId, SourceRef(
                    id = sourceId,
                    kind = SourceKind.LOCAL,
                    uriGrant = uri.toString(),
                    label = doc?.label ?: "base",
                ))
            }
        }
    }

    // R-23a: consume file browser picks (direct flavour).
    // R-34a: use ref.id as the registry key so SourceRef.id is authoritative.
    LaunchedEffect(fileBrowserLeftRef) {
        val ref = fileBrowserLeftRef ?: return@LaunchedEffect
        val path = ref.path ?: run { onConsumeLeftRef(); return@LaunchedEffect }
        withContext(Dispatchers.IO) { readFileIntoRegistry(path, id = ref.id) }
        onLeftChanged(ref.id, ref)
        onConsumeLeftRef()  // consume AFTER state is updated to avoid race
    }
    LaunchedEffect(fileBrowserRightRef) {
        val ref = fileBrowserRightRef ?: return@LaunchedEffect
        val path = ref.path ?: run { onConsumeRightRef(); return@LaunchedEffect }
        withContext(Dispatchers.IO) { readFileIntoRegistry(path, id = ref.id) }
        onRightChanged(ref.id, ref)
        onConsumeRightRef()
    }
    LaunchedEffect(fileBrowserThirdRef) {
        val ref = fileBrowserThirdRef ?: return@LaunchedEffect
        val path = ref.path ?: run { onConsumeThirdRef(); return@LaunchedEffect }
        withContext(Dispatchers.IO) { readFileIntoRegistry(path, id = ref.id) }
        onThirdChanged(ref.id, ref)
        onConsumeThirdRef()
    }

    SourceSetupScreen(
        leftSource = leftSource,
        rightSource = rightSource,
        thirdSource = thirdSource,
        showThirdSlot = showThirdSlot,
        onShowThirdSlot = onShowThirdSlot,
        onPickLeft = {
            if (hasFlavourFileBrowser()) {
                navController.navigate("filebrowser/left")
            } else {
                leftPicker.launch(arrayOf("*/*"))
            }
        },
        onPickRight = {
            if (hasFlavourFileBrowser()) {
                navController.navigate("filebrowser/right")
            } else {
                rightPicker.launch(arrayOf("*/*"))
            }
        },
        onPickThird = {
            if (hasFlavourFileBrowser()) {
                navController.navigate("filebrowser/third")
            } else {
                thirdPicker.launch(arrayOf("*/*"))
            }
        },
        onSwapSides = {
            val tmpSrc = leftSource; val tmpKey = leftKey
            onLeftChanged(rightKey, rightSource)
            onRightChanged(tmpKey, tmpSrc)
        },
        onCompare = {
            val lk = leftKey
            val rk = rightKey
            val tk = thirdKey
            val ls = leftSource
            val rs = rightSource
            val ts = thirdSource
            if (lk != null && rk != null) {
                // Track in recents and persist as a compare session (R-34a)
                scope.launch {
                    ls?.let { recentsStore.addRecent(it) }
                    rs?.let { recentsStore.addRecent(it) }
                    ts?.let { recentsStore.addRecent(it) }
                    val sources = listOfNotNull(ls, rs, ts)
                    val sessionId = if (tk != null) "$lk-$rk-$tk" else "$lk-$rk"
                    val sessionName = if (ts != null) {
                        "${ls?.label ?: "left"} ↔ ${rs?.label ?: "right"} (base: ${ts.label})"
                    } else {
                        "${ls?.label ?: "left"} ↔ ${rs?.label ?: "right"}"
                    }
                    sessionStore.save(Session(
                        id = sessionId,
                        name = sessionName,
                        mode = CompareMode.TEXT,
                        createdAt = System.currentTimeMillis(),
                        sources = sources,
                    ))
                }
                val route = if (tk != null) {
                    "compare/$lk/$rk?baseKey=$tk"
                } else {
                    "compare/$lk/$rk"
                }
                navController.navigate(route)
            }
        },
        onNavigateBack = { navController.popBackStack() },
    )
}

/** Compare route body extracted to keep OmniNavGraph within complexity budget. */
@Composable
private fun CompareDestination(
    leftKey: String,
    rightKey: String,
    baseKey: String? = null,
    resultStore: ResultStore,
    tabs: List<TabInfo> = emptyList(),
    activeTabId: String? = null,
    onTabSelected: (String) -> Unit = {},
    onTabClosed: (String) -> Unit = {},
    onNewTab: () -> Unit = {},
    onNavigateBack: () -> Unit,
    navController: NavHostController,
    settingsState: CompareSettingsState = CompareSettingsState(),
    settingsCallbacks: CompareSettingsCallbacks = CompareSettingsCallbacks(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Flip state: when true left/right registrations are swapped.
    var flipped by remember { mutableStateOf(false) }
    // Increment to force a re-run (bypasses cache).
    var rerunVersion by remember { mutableIntStateOf(0) }

    // Resolve effective keys and cached docs based on flip state.
    val effectiveLeftKey = if (flipped) rightKey else leftKey
    val effectiveRightKey = if (flipped) leftKey else rightKey
    val leftCached = DocumentRegistry.get(effectiveLeftKey)
    val rightCached = DocumentRegistry.get(effectiveRightKey)

    var compareState by remember { mutableStateOf<CompareState?>(null) }
    var currentRuleSet by remember { mutableStateOf(RuleSet.DEFAULT) }
    var compareProgress by remember { mutableStateOf<Float?>(null) }
    var compareJob by remember { mutableStateOf<Job?>(null) }

    // Sync settings granularity into the current rule set so menu toggles
    // trigger a re-compare. The LaunchedEffect fires when the DataStore
    // value changes (propagated via settingsState.granularity).
    val settingsGranularity = when (settingsState.granularity) {
        "line" -> Granularity.LINE
        "char" -> Granularity.CHARACTER
        else -> Granularity.WORD
    }
    LaunchedEffect(settingsGranularity) {
        if (currentRuleSet.granularity != settingsGranularity) {
            currentRuleSet = currentRuleSet.copy(granularity = settingsGranularity)
        }
    }

    // R-27: create PieceTableDocuments for merge write-back.
    // Key on effective keys so documents are recreated when sides are flipped.
    val leftDocument = remember(effectiveLeftKey, flipped) {
        leftCached?.let { PieceTableDocument.create(it.text) }
    }
    val rightDocument = remember(effectiveRightKey, flipped) {
        rightCached?.let { PieceTableDocument.create(it.text) }
    }

    // R-28: fingerprints captured when files are opened, used to detect external changes.
    // size+modifiedAt of the source URIs (same approach as R-22 in the editor).
    var leftInitialSize by remember { mutableLongStateOf(-1L) }
    var leftInitialModified by remember { mutableLongStateOf(-1L) }
    var rightInitialSize by remember { mutableLongStateOf(-1L) }
    var rightInitialModified by remember { mutableLongStateOf(-1L) }

    LaunchedEffect(effectiveLeftKey, effectiveRightKey, flipped) {
        // R-28: capture size+modifiedAt fingerprints for both sides on open.
        val leftUri = leftCached?.uri?.let { android.net.Uri.parse(it) }
        val rightUri = rightCached?.uri?.let { android.net.Uri.parse(it) }
        withContext(Dispatchers.IO) {
            leftUri?.let { uri ->
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                    null, null, null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        leftInitialSize = cursor.getLong(0)
                        leftInitialModified = cursor.getLong(1)
                    }
                }
            }
            rightUri?.let { uri ->
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                    null, null, null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        rightInitialSize = cursor.getLong(0)
                        rightInitialModified = cursor.getLong(1)
                    }
                }
            }
        }
    }

    LaunchedEffect(effectiveLeftKey, effectiveRightKey, currentRuleSet, flipped, rerunVersion) {
        if (leftCached != null && rightCached != null) {
            // R-12: size guard — refuse over-threshold files on the compare path.
            val limit = DocumentLimits.COMPARE_MAX_BYTES_PER_SIDE
            val leftSize = leftCached.sizeBytes
            val rightSize = rightCached.sizeBytes
            if ((leftSize > 0 && leftSize > limit) || (rightSize > 0 && rightSize > limit)) {
                return@LaunchedEffect
            }

            // R-34a: try to restore a cached result before recomputing,
            // but only when using default rules (cached results used default).
            // On re-run (rerunVersion > 0) or flip, skip the cache to get fresh content.
            val sessionId = "$effectiveLeftKey-$effectiveRightKey"
            val skipCache = rerunVersion > 0 || flipped
            if (!skipCache && currentRuleSet == RuleSet.DEFAULT) {
                val cached = resultStore.load(sessionId)
                if (cached != null && !cached.stale) {
                    val leftLines = leftCached.text.lines()
                    val rightLines = rightCached.text.lines()
                    compareState = CompareState(
                        cached, leftLines, rightLines,
                        leftDocument = leftDocument,
                        rightDocument = rightDocument,
                    )
                    return@LaunchedEffect
                }
            }

            // Show loading state while re-running compare
            compareState = null
            compareProgress = 0f

            // Re-run: reload document text fresh from registry (picks up external changes).
            val leftText = if (rerunVersion > 0) {
                DocumentRegistry.get(effectiveLeftKey)?.text ?: leftCached.text
            } else {
                leftCached.text
            }
            val rightText = if (rerunVersion > 0) {
                DocumentRegistry.get(effectiveRightKey)?.text ?: rightCached.text
            } else {
                rightCached.text
            }

            val leftLines = leftText.lines()
            val rightLines = rightText.lines()
            val baseCached = baseKey?.let { DocumentRegistry.get(it) }
            val job = scope.launch(Dispatchers.Default) {
                val result = if (baseCached != null) {
                    // 3-way compare: base → left, base → right, classify conflicts
                    val baseLines = baseCached.text.lines()
                    val diff3Result = com.omnieditor.core.diff.Diff3.diff3(
                        baseLines = baseLines,
                        leftLines = leftLines,
                        rightLines = rightLines,
                        rules = currentRuleSet,
                    )
                    com.omnieditor.core.diff.Diff3.toCompareResult(
                        diff3Result,
                        leftLines.size.toLong(),
                        rightLines.size.toLong(),
                    )
                } else {
                    // 2-way compare
                    com.omnieditor.core.diff.DiffEngine.compare(
                        leftLineCount = leftLines.size.toLong(),
                        rightLineCount = rightLines.size.toLong(),
                        leftLine = { leftLines[it.toInt()] },
                        rightLine = { rightLines[it.toInt()] },
                        rules = currentRuleSet,
                        progress = { p ->
                            val total = p.total ?: 1L
                            compareProgress = if (total > 0) p.done.toFloat() / total.toFloat() else 0f
                        },
                    )
                }
                compareState = CompareState(
                    result, leftLines, rightLines,
                    ruleSet = currentRuleSet,
                    leftDocument = leftDocument,
                    rightDocument = rightDocument,
                )
                compareProgress = null
                // R-34a: persist result so it survives process death (only for default rules).
                if (currentRuleSet == RuleSet.DEFAULT) {
                    resultStore.store(sessionId, result)
                }
            }
            compareJob = job
            job.join()
        }
    }

    // R-28: save function — checks fingerprint (external change detection), creates backup,
    // then writes the dirty document back to its URI.
    val saveFunction: (() -> Unit)? = if (leftDocument != null || rightDocument != null) {
        {
            scope.launch {
                if (compareState == null) return@launch
                val leftUri = leftCached?.uri?.let { android.net.Uri.parse(it) }
                val rightUri = rightCached?.uri?.let { android.net.Uri.parse(it) }
                executeMergeSave(
                    context = context,
                    leftDocument = leftDocument,
                    rightDocument = rightDocument,
                    leftUri = leftUri,
                    rightUri = rightUri,
                    leftCachedUri = leftCached?.uri,
                    rightCachedUri = rightCached?.uri,
                    leftInitialSize = leftInitialSize,
                    leftInitialModified = leftInitialModified,
                    rightInitialSize = rightInitialSize,
                    rightInitialModified = rightInitialModified,
                    backupDir = File(context.cacheDir, "backups"),
                    sessionId = "$effectiveLeftKey-$effectiveRightKey",
                    onLeftFingerprintUpdated = { sz, mod ->
                        leftInitialSize = sz; leftInitialModified = mod
                    },
                    onRightFingerprintUpdated = { sz, mod ->
                        rightInitialSize = sz; rightInitialModified = mod
                    },
                )
            }
        }
    } else {
        null
    }

    // R-30: export report — generate unified-diff patch via ReportGenerator and share.
    val exportReport: () -> Unit = {
        val state = compareState
        if (state != null) {
            scope.launch {
                shareCompareReport(
                    context = context,
                    state = state,
                    leftLabel = leftCached?.label ?: "left",
                    rightLabel = rightCached?.label ?: "right",
                    currentRuleSet = currentRuleSet,
                )
            }
        }
    }

    // R-30: open a single side in the viewer (read-only editor route).
    val openLeft: (() -> Unit)? = if (leftCached != null) {
        { navController.navigate("editor/$effectiveLeftKey") }
    } else null

    val openRight: (() -> Unit)? = if (rightCached != null) {
        { navController.navigate("editor/$effectiveRightKey") }
    } else null

    CompareScreen(
        state = compareState,
        ruleSet = currentRuleSet,
        onRuleSetChanged = { currentRuleSet = it },
        leftLabel = leftCached?.label ?: "left",
        rightLabel = rightCached?.label ?: "right",
        onNavigateBack = onNavigateBack,
        onSave = saveFunction,
        onOpenLeft = openLeft,
        onOpenRight = openRight,
        onFlipSides = { flipped = !flipped },
        onRerunCompare = { rerunVersion++ },
        onExportReport = exportReport,
        compareProgress = compareProgress,
        onCancelCompare = {
            compareJob?.cancel()
            compareJob = null
            compareProgress = null
        },
        tabStripContent = if (tabs.isNotEmpty()) {
            {
                com.omnieditor.feature.editor.TabStrip(
                    tabs = tabs,
                    selectedTabId = activeTabId,
                    onTabSelected = onTabSelected,
                    onTabClosed = onTabClosed,
                    onNewTab = onNewTab,
                )
            }
        } else null,
        settingsState = settingsState,
        // Override granularity callback to also update currentRuleSet directly,
        // so the re-compare triggers immediately without waiting for the DataStore
        // round-trip through LaunchedEffect.
        settingsCallbacks = settingsCallbacks.copy(
            onSetGranularity = { g ->
                settingsCallbacks.onSetGranularity(g)  // persist to DataStore
                val newGranularity = when (g) {
                    "line" -> Granularity.LINE
                    "char" -> Granularity.CHARACTER
                    else -> Granularity.WORD
                }
                currentRuleSet = currentRuleSet.copy(granularity = newGranularity)
            },
        ),
    )
}

/**
 * R-30: Generate and share a compare report as a plain-text unified diff.
 *
 * Extracted from [CompareDestination] to keep method length within budget.
 * Must be called from a coroutine (uses [withContext]).
 */
private suspend fun shareCompareReport(
    context: android.content.Context,
    state: com.omnieditor.feature.compare.CompareState,
    leftLabel: String,
    rightLabel: String,
    currentRuleSet: RuleSet,
) {
    val timestamp = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss z")
        .withZone(ZoneId.systemDefault())
        .format(Instant.now())
    val meta = ReportGenerator.ReportMeta(
        leftLabel = leftLabel,
        rightLabel = rightLabel,
        timestamp = timestamp,
        rules = currentRuleSet,
        engineMode = "histogram",
    )
    val reportText = withContext(Dispatchers.Default) {
        buildString {
            append(ReportGenerator.plainTextSummary(state.result, meta))
            appendLine()
            appendLine("--- Unified Diff Patch ---")
            append(ReportGenerator.unifiedDiffPatch(
                result = state.result,
                leftLines = state.leftLines,
                rightLines = state.rightLines,
                meta = meta,
            ))
        }
    }
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, reportText)
        putExtra(Intent.EXTRA_SUBJECT, "Compare report: $leftLabel vs $rightLabel")
    }
    context.startActivity(Intent.createChooser(shareIntent, "Export report"))
}

/**
 * R-28: Execute the merge save sequence:
 * 1. Re-fingerprint both files to detect external changes (abort if changed).
 * 2. Pre-write backup via MergeSafety before any byte is written.
 * 3. Write each dirty document back through its URI.
 * 4. Mark documents saved and refresh fingerprints.
 */
private suspend fun executeMergeSave(
    context: android.content.Context,
    leftDocument: PieceTableDocument?,
    rightDocument: PieceTableDocument?,
    leftUri: Uri?,
    rightUri: Uri?,
    leftCachedUri: String?,
    rightCachedUri: String?,
    leftInitialSize: Long,
    leftInitialModified: Long,
    rightInitialSize: Long,
    rightInitialModified: Long,
    backupDir: File,
    sessionId: String,
    onLeftFingerprintUpdated: (Long, Long) -> Unit,
    onRightFingerprintUpdated: (Long, Long) -> Unit,
) {
    val fingerprintCols = arrayOf(
        OpenableColumns.SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    // External change detection — abort if either file changed under us.
    val externalChangeDetected = withContext(Dispatchers.IO) {
        var changed = false
        if (leftUri != null && leftInitialSize >= 0) {
            context.contentResolver.query(leftUri, fingerprintCols, null, null, null)?.use { c ->
                if (c.moveToFirst() && (c.getLong(0) != leftInitialSize || c.getLong(1) != leftInitialModified))
                    changed = true
            }
        }
        if (rightUri != null && rightInitialSize >= 0) {
            context.contentResolver.query(rightUri, fingerprintCols, null, null, null)?.use { c ->
                if (c.moveToFirst() && (c.getLong(0) != rightInitialSize || c.getLong(1) != rightInitialModified))
                    changed = true
            }
        }
        changed
    }
    // Do not overwrite silently; spec §13 maps this to ExternalChangeDetected UI state.
    if (externalChangeDetected) return

    // Pre-write backup for each dirty document that has a local path (direct flavour).
    withContext(Dispatchers.IO) {
        leftCachedUri?.let { uriToFileOrNull(it) }?.let { path ->
            if (leftDocument?.dirty == true) MergeSafety.createBackup(path, backupDir, sessionId)
        }
        rightCachedUri?.let { uriToFileOrNull(it) }?.let { path ->
            if (rightDocument?.dirty == true) MergeSafety.createBackup(path, backupDir, sessionId)
        }
    }

    // Write dirty documents and refresh fingerprints.
    withContext(Dispatchers.IO) {
        if (leftDocument?.dirty == true && leftUri != null) {
            val leftBytes = leftDocument.text().toByteArray()
            context.contentResolver.openOutputStream(leftUri, "wt")?.use { it.write(leftBytes) }
            leftDocument.markSaved()
            context.contentResolver.query(leftUri, fingerprintCols, null, null, null)
                ?.use { c -> if (c.moveToFirst()) onLeftFingerprintUpdated(c.getLong(0), c.getLong(1)) }
        }
        if (rightDocument?.dirty == true && rightUri != null) {
            val rightBytes = rightDocument.text().toByteArray()
            context.contentResolver.openOutputStream(rightUri, "wt")?.use { it.write(rightBytes) }
            rightDocument.markSaved()
            context.contentResolver.query(rightUri, fingerprintCols, null, null, null)
                ?.use { c -> if (c.moveToFirst()) onRightFingerprintUpdated(c.getLong(0), c.getLong(1)) }
        }
    }
}

/**
 * Convert a URI string to a java.io.File if it is a plain file:// URI,
 * returning null for content:// URIs (which cannot be backed up via File API).
 */
private fun uriToFileOrNull(uriString: String): File? {
    return try {
        val uri = android.net.Uri.parse(uriString)
        if (uri.scheme == "file") File(uri.path ?: return null) else null
    } catch (_: Exception) {
        null
    }
}
