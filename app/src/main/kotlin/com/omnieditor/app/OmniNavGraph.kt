package com.omnieditor.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.omnieditor.feature.editor.TabInfo
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
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
import com.omnieditor.core.model.Session
import com.omnieditor.core.model.SourceKind
import com.omnieditor.core.model.SourceRef
import com.omnieditor.feature.compare.CompareSettingsCallbacks
import com.omnieditor.feature.compare.CompareSettingsState
import com.omnieditor.feature.editor.EditorSettingsState
import com.omnieditor.feature.setup.SourceSetupScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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
