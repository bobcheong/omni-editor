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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.omnieditor.core.io.RecentsStore
import com.omnieditor.core.io.ResultStore
import com.omnieditor.core.io.SessionStore
import com.omnieditor.core.model.CompareMode
import com.omnieditor.core.model.DocumentLimits
import com.omnieditor.core.model.Session
import com.omnieditor.core.model.RuleSet
import com.omnieditor.core.model.SourceKind
import com.omnieditor.core.model.SourceRef
import com.omnieditor.feature.compare.CompareScreen
import com.omnieditor.feature.compare.CompareState
import com.omnieditor.feature.editor.EditorScreen
import com.omnieditor.feature.editor.EditorUiState
import com.omnieditor.feature.editor.EditorViewModel
import com.omnieditor.feature.setup.SourceSetupScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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

    // R-23a: file browser picked sources, keyed by slot ("left" / "right").
    // Updated by flavourDestinations() callback, read by the setup screen.
    var fileBrowserLeftRef by remember { mutableStateOf<SourceRef?>(null) }
    var fileBrowserRightRef by remember { mutableStateOf<SourceRef?>(null) }

    NavHost(navController = navController, startDestination = flavourStartDestination()) {

        // ── Flavour-specific routes (permission screen, file browser) ──
        flavourDestinations(navController) { slot, ref ->
            when (slot) {
                "left" -> fileBrowserLeftRef = ref
                "right" -> fileBrowserRightRef = ref
            }
        }

        // ── Home ──
        composable("home") {
            val scope = rememberCoroutineScope()
            val recentSessions = remember { mutableStateListOf<Session>() }

            // Load recents
            LaunchedEffect(Unit) {
                val recents = recentsStore.getRecents()
                recentSessions.clear()
                recentSessions.addAll(recents.map { ref ->
                    Session(
                        id = ref.id,
                        name = ref.label,
                        mode = CompareMode.EDITOR,
                        createdAt = System.currentTimeMillis(),
                    )
                })
            }

            val openFileLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    // R-23a: take persistable permission so URI survives app restart.
                    takePersistablePermission(context.contentResolver, uri)
                    // R-34a: generate SourceRef.id first and use it as the registry key
                    // so both systems share a single authoritative identity.
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
                        // Track in recents and persist as a session
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
                recentSessions = recentSessions,
                onOpenFile = {
                    openFileLauncher.launch(arrayOf("*/*"))
                },
                onNewCompare = {
                    navController.navigate("setup")
                },
                onSessionTap = { sessionId ->
                    // Try registry first (document already loaded in this session)
                    val cached = DocumentRegistry.get(sessionId)
                    if (cached != null) {
                        navController.navigate("editor/$sessionId")
                    } else {
                        // Try to re-read from stored URI in recents
                        scope.launch {
                            val recents = recentsStore.getRecents()
                            val ref = recents.find { it.id == sessionId }
                            val uriString = ref?.uriGrant
                            if (uriString != null) {
                                val doc = withContext(Dispatchers.IO) {
                                    runCatching {
                                        readUriIntoRegistry(context, Uri.parse(uriString), id = sessionId)
                                    }.getOrNull()
                                }
                                if (doc != null) {
                                    navController.navigate("editor/$sessionId")
                                } else {
                                    // Permission expired — open picker instead
                                    openFileLauncher.launch(arrayOf("*/*"))
                                }
                            } else {
                                openFileLauncher.launch(arrayOf("*/*"))
                            }
                        }
                    }
                },
                onSettings = {
                    navController.navigate("settings")
                },
            )
        }

        // ── Editor ──
        composable("editor/{contentKey}") { backStackEntry ->
            val contentKey = backStackEntry.arguments?.getString("contentKey") ?: ""
            EditorDestination(
                contentKey = contentKey,
                onNavigateBack = { navController.popBackStack() },
                onCompareWith = { navController.navigate("setup?leftKey=$contentKey") },
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
            SetupDestination(
                prefilledLeftKey = prefilledLeftKey,
                recentsStore = recentsStore,
                sessionStore = sessionStore,
                navController = navController,
                fileBrowserLeftRef = fileBrowserLeftRef,
                fileBrowserRightRef = fileBrowserRightRef,
                onConsumeLeftRef = { fileBrowserLeftRef = null },
                onConsumeRightRef = { fileBrowserRightRef = null },
            )
        }

        // ── Compare ──
        composable("compare/{leftKey}/{rightKey}") { backStackEntry ->
            val leftKey = backStackEntry.arguments?.getString("leftKey") ?: ""
            val rightKey = backStackEntry.arguments?.getString("rightKey") ?: ""
            CompareDestination(
                leftKey = leftKey,
                rightKey = rightKey,
                resultStore = resultStore,
                onNavigateBack = { navController.popBackStack() },
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

/** Editor route body extracted to keep OmniNavGraph within complexity budget. */
@Composable
private fun EditorDestination(
    contentKey: String,
    onNavigateBack: () -> Unit,
    onCompareWith: () -> Unit,
) {
    val cached = DocumentRegistry.get(contentKey)
    val context = LocalContext.current
    val viewModel: EditorViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()

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
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(bytes)
                        out.flush()
                    } ?: throw java.io.IOException("Cannot open $sourceUri for writing")
                    // Update fingerprint snapshot after a successful save so the next
                    // resume check doesn't false-positive on our own write.
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
        onNavigateBack = onNavigateBack,
        onCompareWith = onCompareWith,
        viewModel = viewModel,
    )
}

/** Setup route body extracted to keep OmniNavGraph within complexity budget. */
@Composable
private fun SetupDestination(
    prefilledLeftKey: String?,
    recentsStore: RecentsStore,
    sessionStore: SessionStore,
    navController: NavHostController,
    fileBrowserLeftRef: SourceRef?,
    fileBrowserRightRef: SourceRef?,
    onConsumeLeftRef: () -> Unit,
    onConsumeRightRef: () -> Unit,
) {
    val prefilledLeft = prefilledLeftKey?.let { DocumentRegistry.get(it) }

    var leftSource by remember {
        mutableStateOf(prefilledLeft?.let {
            SourceRef(id = prefilledLeftKey, kind = SourceKind.LOCAL, uriGrant = it.uri, label = it.label)
        })
    }
    var rightSource by remember { mutableStateOf<SourceRef?>(null) }
    var leftKey by remember { mutableStateOf(prefilledLeftKey) }
    var rightKey by remember { mutableStateOf<String?>(null) }
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
                leftKey = sourceId
                leftSource = SourceRef(
                    id = sourceId,
                    kind = SourceKind.LOCAL,
                    uriGrant = uri.toString(),
                    label = doc?.label ?: "left",
                )
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
                rightKey = sourceId
                rightSource = SourceRef(
                    id = sourceId,
                    kind = SourceKind.LOCAL,
                    uriGrant = uri.toString(),
                    label = doc?.label ?: "right",
                )
            }
        }
    }

    // R-23a: consume file browser picks (direct flavour).
    // R-34a: use ref.id as the registry key so SourceRef.id is authoritative.
    LaunchedEffect(fileBrowserLeftRef) {
        val ref = fileBrowserLeftRef ?: return@LaunchedEffect
        onConsumeLeftRef()
        val path = ref.path ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { readFileIntoRegistry(path, id = ref.id) }
        leftKey = ref.id
        leftSource = ref
    }
    LaunchedEffect(fileBrowserRightRef) {
        val ref = fileBrowserRightRef ?: return@LaunchedEffect
        onConsumeRightRef()
        val path = ref.path ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { readFileIntoRegistry(path, id = ref.id) }
        rightKey = ref.id
        rightSource = ref
    }

    SourceSetupScreen(
        leftSource = leftSource,
        rightSource = rightSource,
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
        onSwapSides = {
            val tmpSrc = leftSource; leftSource = rightSource; rightSource = tmpSrc
            val tmpKey = leftKey; leftKey = rightKey; rightKey = tmpKey
        },
        onCompare = {
            val lk = leftKey
            val rk = rightKey
            val ls = leftSource
            val rs = rightSource
            if (lk != null && rk != null) {
                // Track both in recents and persist as a compare session (R-34a)
                scope.launch {
                    ls?.let { recentsStore.addRecent(it) }
                    rs?.let { recentsStore.addRecent(it) }
                    val sources = listOfNotNull(ls, rs)
                    val sessionId = "$lk-$rk"
                    sessionStore.save(Session(
                        id = sessionId,
                        name = "${ls?.label ?: "left"} ↔ ${rs?.label ?: "right"}",
                        mode = CompareMode.TEXT,
                        createdAt = System.currentTimeMillis(),
                        sources = sources,
                    ))
                }
                navController.navigate("compare/$lk/$rk")
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
    resultStore: ResultStore,
    onNavigateBack: () -> Unit,
) {
    val leftCached = DocumentRegistry.get(leftKey)
    val rightCached = DocumentRegistry.get(rightKey)
    var compareState by remember { mutableStateOf<CompareState?>(null) }
    var currentRuleSet by remember { mutableStateOf(RuleSet.DEFAULT) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(leftKey, rightKey, currentRuleSet) {
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
            val sessionId = "$leftKey-$rightKey"
            if (currentRuleSet == RuleSet.DEFAULT) {
                val cached = resultStore.load(sessionId)
                if (cached != null && !cached.stale) {
                    val leftLines = leftCached.text.lines()
                    val rightLines = rightCached.text.lines()
                    compareState = CompareState(cached, leftLines, rightLines)
                    return@LaunchedEffect
                }
            }

            // Show loading state while re-running compare
            compareState = null

            val leftLines = leftCached.text.lines()
            val rightLines = rightCached.text.lines()
            val result = withContext(Dispatchers.Default) {
                com.omnieditor.core.diff.DiffEngine.compare(
                    leftLineCount = leftLines.size.toLong(),
                    rightLineCount = rightLines.size.toLong(),
                    leftLine = { leftLines[it.toInt()] },
                    rightLine = { rightLines[it.toInt()] },
                    rules = currentRuleSet,
                )
            }
            compareState = CompareState(result, leftLines, rightLines)
            // R-34a: persist result so it survives process death (only for default rules).
            if (currentRuleSet == RuleSet.DEFAULT) {
                scope.launch { resultStore.store(sessionId, result) }
            }
        }
    }

    CompareScreen(
        state = compareState,
        ruleSet = currentRuleSet,
        onRuleSetChanged = { currentRuleSet = it },
        leftLabel = leftCached?.label ?: "left",
        rightLabel = rightCached?.label ?: "right",
        onNavigateBack = onNavigateBack,
    )
}
