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
import com.omnieditor.core.model.CompareMode
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
 * Top-level navigation graph wiring Home, Editor, Source Setup, and Compare.
 */
@Composable
fun OmniNavGraph(
    navController: NavHostController = rememberNavController(),
    initialAction: IntentRouter.IntentAction = IntentRouter.IntentAction.ShowHome,
) {
    NavHost(navController = navController, startDestination = "home") {

        // ── Home ──
        composable("home") {
            val scope = rememberCoroutineScope()
            val context = LocalContext.current

            // File picker launcher for "Open file"
            val openFileLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    // Take persistable permission
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (_: SecurityException) {
                        // Read-only is fine
                        try {
                            context.contentResolver.takePersistableUriPermission(
                                uri,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (_: SecurityException) { }
                    }

                    val encodedUri = Uri.encode(uri.toString())
                    val label = uri.lastPathSegment ?: "file"
                    navController.navigate("editor?uri=$encodedUri&label=$label")
                }
            }

            HomeScreen(
                onOpenFile = {
                    openFileLauncher.launch(arrayOf("*/*"))
                },
                onNewCompare = {
                    navController.navigate("setup")
                },
                onSessionTap = { sessionId ->
                    // TODO: load session and navigate to compare/editor
                },
            )
        }

        // ── Editor ──
        composable("editor?uri={uri}&label={label}") { backStackEntry ->
            val encodedUri = backStackEntry.arguments?.getString("uri") ?: ""
            val label = backStackEntry.arguments?.getString("label") ?: "file"
            val uri = Uri.decode(encodedUri)
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val viewModel: EditorViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsState()

            // Load file content on first composition
            if (uiState is EditorUiState.Empty) {
                scope.launch {
                    val content = withContext(Dispatchers.IO) {
                        try {
                            context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                                it.bufferedReader().readText()
                            } ?: ""
                        } catch (e: Exception) {
                            "Error: ${e.message}"
                        }
                    }
                    viewModel.openDocument(content)
                }
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

            val leftPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    takePersistable(context, uri)
                    leftSource = SourceRef(
                        id = UUID.randomUUID().toString(),
                        kind = SourceKind.LOCAL,
                        uriGrant = uri.toString(),
                        label = uri.lastPathSegment ?: "left",
                    )
                }
            }

            val rightPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    takePersistable(context, uri)
                    rightSource = SourceRef(
                        id = UUID.randomUUID().toString(),
                        kind = SourceKind.LOCAL,
                        uriGrant = uri.toString(),
                        label = uri.lastPathSegment ?: "right",
                    )
                }
            }

            SourceSetupScreen(
                leftSource = leftSource,
                rightSource = rightSource,
                onPickLeft = { leftPicker.launch(arrayOf("*/*")) },
                onPickRight = { rightPicker.launch(arrayOf("*/*")) },
                onSwapSides = {
                    val tmp = leftSource
                    leftSource = rightSource
                    rightSource = tmp
                },
                onCompare = {
                    val l = leftSource
                    val r = rightSource
                    if (l != null && r != null) {
                        val leftUri = Uri.encode(l.uriGrant ?: "")
                        val rightUri = Uri.encode(r.uriGrant ?: "")
                        val leftLabel = Uri.encode(l.label)
                        val rightLabel = Uri.encode(r.label)
                        navController.navigate(
                            "compare?leftUri=$leftUri&rightUri=$rightUri&leftLabel=$leftLabel&rightLabel=$rightLabel"
                        )
                    }
                },
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // ── Compare ──
        composable("compare?leftUri={leftUri}&rightUri={rightUri}&leftLabel={leftLabel}&rightLabel={rightLabel}") { backStackEntry ->
            val leftUri = Uri.decode(backStackEntry.arguments?.getString("leftUri") ?: "")
            val rightUri = Uri.decode(backStackEntry.arguments?.getString("rightUri") ?: "")
            val leftLabel = Uri.decode(backStackEntry.arguments?.getString("leftLabel") ?: "left")
            val rightLabel = Uri.decode(backStackEntry.arguments?.getString("rightLabel") ?: "right")
            val context = LocalContext.current
            val scope = rememberCoroutineScope()

            var compareState by remember { mutableStateOf<CompareState?>(null) }

            // Run the compare
            if (compareState == null) {
                scope.launch {
                    val leftLines = withContext(Dispatchers.IO) {
                        readLinesFromUri(context, leftUri)
                    }
                    val rightLines = withContext(Dispatchers.IO) {
                        readLinesFromUri(context, rightUri)
                    }

                    val result = com.omnieditor.core.diff.DiffEngine.compare(
                        leftLineCount = leftLines.size.toLong(),
                        rightLineCount = rightLines.size.toLong(),
                        leftLine = { leftLines[it.toInt()] },
                        rightLine = { rightLines[it.toInt()] },
                    )

                    compareState = CompareState(result, leftLines, rightLines)
                }
            }

            CompareScreen(
                state = compareState,
                leftLabel = leftLabel,
                rightLabel = rightLabel,
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}

private fun takePersistable(context: android.content.Context, uri: Uri) {
    try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    } catch (_: SecurityException) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) { }
    }
}

private fun readLinesFromUri(context: android.content.Context, uriString: String): List<String> {
    return try {
        context.contentResolver.openInputStream(Uri.parse(uriString))?.use {
            it.bufferedReader().readLines()
        } ?: emptyList()
    } catch (e: Exception) {
        listOf("Error reading file: ${e.message}")
    }
}
