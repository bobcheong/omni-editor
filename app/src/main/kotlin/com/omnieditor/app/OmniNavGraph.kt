package com.omnieditor.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.omnieditor.app.home.HomeScreen
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
import java.util.UUID

/**
 * Top-level navigation graph.
 *
 * File content is read immediately when the SAF picker returns (while
 * the temporary permission is active) and cached via [ContentCache].
 * This solves permission denial for Google Drive and other cloud providers.
 */
@Composable
fun OmniNavGraph(
    navController: NavHostController = rememberNavController(),
    initialAction: IntentRouter.IntentAction = IntentRouter.IntentAction.ShowHome,
) {
    NavHost(navController = navController, startDestination = "home") {

        // ── Home ──
        composable("home") {
            val context = LocalContext.current

            val openFileLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    // Read immediately while permission is active
                    val key = ContentCache.readAndCache(context, uri)
                    navController.navigate("editor/$key")
                }
            }

            HomeScreen(
                onOpenFile = {
                    openFileLauncher.launch(arrayOf("*/*"))
                },
                onNewCompare = {
                    navController.navigate("setup")
                },
                onSessionTap = { },
            )
        }

        // ── Editor ──
        composable("editor/{contentKey}") { backStackEntry ->
            val contentKey = backStackEntry.arguments?.getString("contentKey") ?: ""
            val cached = ContentCache.get(contentKey)
            val viewModel: EditorViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsState()

            if (uiState is EditorUiState.Empty && cached != null) {
                viewModel.openDocument(cached.text)
            }

            EditorScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = viewModel,
            )
        }

        // ── Source Setup ──
        composable("setup") {
            val context = LocalContext.current
            var leftSource by remember { mutableStateOf<SourceRef?>(null) }
            var rightSource by remember { mutableStateOf<SourceRef?>(null) }
            var leftKey by remember { mutableStateOf<String?>(null) }
            var rightKey by remember { mutableStateOf<String?>(null) }

            val leftPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    val key = ContentCache.readAndCache(context, uri)
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
                    val key = ContentCache.readAndCache(context, uri)
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
            val scope = rememberCoroutineScope()

            val leftCached = ContentCache.get(leftKey)
            val rightCached = ContentCache.get(rightKey)

            var compareState by remember { mutableStateOf<CompareState?>(null) }

            if (compareState == null && leftCached != null && rightCached != null) {
                scope.launch {
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
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
