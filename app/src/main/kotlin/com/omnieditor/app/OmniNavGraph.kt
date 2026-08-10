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
import com.omnieditor.core.model.CompareMode
import com.omnieditor.core.model.DocumentLimits
import com.omnieditor.core.model.Session
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
import java.util.UUID

@Composable
fun OmniNavGraph(
    navController: NavHostController = rememberNavController(),
    initialAction: IntentRouter.IntentAction = IntentRouter.IntentAction.ShowHome,
) {
    val context = LocalContext.current
    val recentsStore = remember {
        RecentsStore(File(context.filesDir, "recents.json"))
    }

    NavHost(navController = navController, startDestination = "home") {

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
                    val key = ContentCache.readAndCache(context, uri)
                    val cached = ContentCache.get(key)
                    // Track in recents
                    scope.launch {
                        recentsStore.addRecent(SourceRef(
                            id = key,
                            kind = SourceKind.LOCAL,
                            uriGrant = uri.toString(),
                            label = cached?.label ?: "file",
                        ))
                    }
                    navController.navigate("editor/$key")
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
                    // Try cached content first
                    val cached = ContentCache.get(sessionId)
                    if (cached != null) {
                        navController.navigate("editor/$sessionId")
                    } else {
                        // Try to re-read from stored URI in recents
                        scope.launch {
                            val recents = recentsStore.getRecents()
                            val ref = recents.find { it.id == sessionId }
                            val uri = ref?.uriGrant
                            if (uri != null) {
                                try {
                                    val key = ContentCache.readAndCache(
                                        context, Uri.parse(uri)
                                    )
                                    navController.navigate("editor/$key")
                                } catch (_: Exception) {
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
            val prefilledLeft = prefilledLeftKey?.let { ContentCache.get(it) }

            var leftSource by remember {
                mutableStateOf(prefilledLeft?.let {
                    SourceRef(id = prefilledLeftKey!!, kind = SourceKind.LOCAL, uriGrant = it.uri, label = it.label)
                })
            }
            var rightSource by remember { mutableStateOf<SourceRef?>(null) }
            var leftKey by remember { mutableStateOf(prefilledLeftKey) }
            var rightKey by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            val context2 = LocalContext.current

            val leftPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    val key = ContentCache.readAndCache(context2, uri)
                    val cached = ContentCache.get(key)
                    leftKey = key
                    leftSource = SourceRef(
                        id = UUID.randomUUID().toString(),
                        kind = SourceKind.LOCAL,
                        uriGrant = uri.toString(),
                        label = cached?.label ?: "left",
                    )
                }
            }

            val rightPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    val key = ContentCache.readAndCache(context2, uri)
                    val cached = ContentCache.get(key)
                    rightKey = key
                    rightSource = SourceRef(
                        id = UUID.randomUUID().toString(),
                        kind = SourceKind.LOCAL,
                        uriGrant = uri.toString(),
                        label = cached?.label ?: "right",
                    )
                }
            }

            SourceSetupScreen(
                leftSource = leftSource,
                rightSource = rightSource,
                onPickLeft = { leftPicker.launch(arrayOf("*/*")) },
                onPickRight = { rightPicker.launch(arrayOf("*/*")) },
                onSwapSides = {
                    val tmpSrc = leftSource; leftSource = rightSource; rightSource = tmpSrc
                    val tmpKey = leftKey; leftKey = rightKey; rightKey = tmpKey
                },
                onCompare = {
                    val lk = leftKey
                    val rk = rightKey
                    if (lk != null && rk != null) {
                        // Track both in recents
                        scope.launch {
                            leftSource?.let { recentsStore.addRecent(it) }
                            rightSource?.let { recentsStore.addRecent(it) }
                        }
                        navController.navigate("compare/$lk/$rk")
                    }
                },
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // ── Compare ──
        composable("compare/{leftKey}/{rightKey}") { backStackEntry ->
            val leftKey = backStackEntry.arguments?.getString("leftKey") ?: ""
            val rightKey = backStackEntry.arguments?.getString("rightKey") ?: ""
            CompareDestination(
                leftKey = leftKey,
                rightKey = rightKey,
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
    val cached = ContentCache.get(contentKey)
    val context = LocalContext.current
    val viewModel: EditorViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()

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
                viewModel.openDocument(cached.text)
            }
        }

        // R-20: inject save function so the editor can write back to the source URI.
        // The lambda runs in the app layer where ContentResolver is available,
        // keeping feature:editor free of Android framework dependencies.
        val sourceUri = cached?.uri
        if (sourceUri != null) {
            viewModel.setSaveFunction { bytes ->
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(
                        Uri.parse(sourceUri), "wt"
                    )?.use { out ->
                        out.write(bytes)
                        out.flush()
                    } ?: throw java.io.IOException("Cannot open $sourceUri for writing")
                }
            }
        }
    }

    EditorScreen(
        fileName = cached?.label ?: "",
        onNavigateBack = onNavigateBack,
        onCompareWith = onCompareWith,
        viewModel = viewModel,
    )
}

/** Compare route body extracted to keep OmniNavGraph within complexity budget. */
@Composable
private fun CompareDestination(
    leftKey: String,
    rightKey: String,
    onNavigateBack: () -> Unit,
) {
    val leftCached = ContentCache.get(leftKey)
    val rightCached = ContentCache.get(rightKey)
    var compareState by remember { mutableStateOf<CompareState?>(null) }

    LaunchedEffect(leftKey, rightKey) {
        if (leftCached != null && rightCached != null) {
            // R-12: size guard — refuse over-threshold files on the compare path.
            // Full compare-path error UI is deferred to R-23.
            val limit = DocumentLimits.COMPARE_MAX_BYTES_PER_SIDE
            val leftSize = leftCached.sizeBytes
            val rightSize = rightCached.sizeBytes
            if ((leftSize > 0 && leftSize > limit) || (rightSize > 0 && rightSize > limit)) {
                // Stay at loading/null — content was never read for over-limit sides.
                return@LaunchedEffect
            }
            val leftLines = leftCached.text.lines()
            val rightLines = rightCached.text.lines()
            val result = withContext(Dispatchers.Default) {
                com.omnieditor.core.diff.DiffEngine.compare(
                    leftLineCount = leftLines.size.toLong(),
                    rightLineCount = rightLines.size.toLong(),
                    leftLine = { leftLines[it.toInt()] },
                    rightLine = { rightLines[it.toInt()] },
                )
            }
            compareState = CompareState(result, leftLines, rightLines)
        }
    }

    CompareScreen(
        state = compareState,
        leftLabel = leftCached?.label ?: "left",
        rightLabel = rightCached?.label ?: "right",
        onNavigateBack = onNavigateBack,
    )
}
