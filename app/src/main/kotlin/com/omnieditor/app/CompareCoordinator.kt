package com.omnieditor.app

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.omnieditor.core.diff.ReportGenerator
import com.omnieditor.core.io.MergeSafety
import com.omnieditor.core.io.PieceTableDocument
import com.omnieditor.core.io.ResultStore
import com.omnieditor.core.model.DocumentLimits
import com.omnieditor.core.model.Granularity
import com.omnieditor.core.model.RuleSet
import com.omnieditor.feature.compare.CompareScreen
import com.omnieditor.feature.compare.CompareSettingsCallbacks
import com.omnieditor.feature.compare.CompareSettingsState
import com.omnieditor.feature.compare.CompareState
import com.omnieditor.feature.editor.TabInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.Channels
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Compare route body extracted to keep OmniNavGraph within complexity budget. */
@Composable
internal fun CompareDestination(
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
    navController: androidx.navigation.NavHostController,
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
            // R-12: size guard — refuse over-threshold files on the compare path (F-01).
            val leftTier = DocumentLimits.compareTier(leftCached.sizeBytes)
            val rightTier = DocumentLimits.compareTier(rightCached.sizeBytes)
            if (leftTier == DocumentLimits.SizeTier.REFUSED || rightTier == DocumentLimits.SizeTier.REFUSED) {
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
                    // 2-way compare — compareAuto selects BlockDiff above 250k lines (F-02).
                    com.omnieditor.core.diff.DiffEngine.compareAuto(
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
internal suspend fun shareCompareReport(
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
internal suspend fun executeMergeSave(
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
    // R-51: Abort if backup fails — never write without a valid backup.
    val backupFailed = withContext(Dispatchers.IO) {
        var failed = false
        leftCachedUri?.let { uriToFileOrNull(it) }?.let { path ->
            if (leftDocument?.dirty == true) {
                val backup = MergeSafety.createBackup(path, backupDir, sessionId)
                if (backup == null) failed = true
            }
        }
        rightCachedUri?.let { uriToFileOrNull(it) }?.let { path ->
            if (rightDocument?.dirty == true) {
                val backup = MergeSafety.createBackup(path, backupDir, sessionId)
                if (backup == null) failed = true
            }
        }
        failed
    }
    if (backupFailed) return // Abort save — backup failure is a data-safety concern

    // Write dirty documents and refresh fingerprints.
    // R-52: Use materialise() to encode with the document's tracked charset, not toByteArray() (UTF-8 only).
    withContext(Dispatchers.IO) {
        if (leftDocument?.dirty == true && leftUri != null) {
            val baos = ByteArrayOutputStream()
            leftDocument.materialise(Channels.newChannel(baos))
            context.contentResolver.openOutputStream(leftUri, "wt")?.use { it.write(baos.toByteArray()) }
            leftDocument.markSaved()
            context.contentResolver.query(leftUri, fingerprintCols, null, null, null)
                ?.use { c -> if (c.moveToFirst()) onLeftFingerprintUpdated(c.getLong(0), c.getLong(1)) }
        }
        if (rightDocument?.dirty == true && rightUri != null) {
            val baos = ByteArrayOutputStream()
            rightDocument.materialise(Channels.newChannel(baos))
            context.contentResolver.openOutputStream(rightUri, "wt")?.use { it.write(baos.toByteArray()) }
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
internal fun uriToFileOrNull(uriString: String): File? {
    return try {
        val uri = android.net.Uri.parse(uriString)
        if (uri.scheme == "file") File(uri.path ?: return null) else null
    } catch (_: Exception) {
        null
    }
}
