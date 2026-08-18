package com.omnieditor.app

import android.content.Intent
import android.os.Build
import android.os.Environment
import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.omnieditor.core.model.SourceKind
import com.omnieditor.core.model.SourceRef
import java.io.File
import java.util.UUID

/**
 * Direct flavour: the start destination is "permission" when
 * MANAGE_EXTERNAL_STORAGE has not been granted, "home" otherwise.
 */
fun flavourStartDestination(): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        Environment.isExternalStorageManager()
    ) "home" else "permission"
}

/**
 * Returns true when the direct flavour has a file browser available.
 * Used by the setup screen to offer an alternative to the SAF picker.
 */
fun hasFlavourFileBrowser(): Boolean = true

/**
 * Registers flavour-specific navigation destinations (permission screen,
 * file browser) into the shared [NavGraphBuilder].
 */
fun NavGraphBuilder.flavourDestinations(
    navController: NavHostController,
    onFilePicked: (slot: String, SourceRef) -> Unit,
) {
    composable("permission") {
        PermissionScreen(
            onGranted = {
                navController.navigate("home") {
                    popUpTo("permission") { inclusive = true }
                }
            },
            onUseSafFallback = {
                // Skip permission screen and go to home — SAF picker will be
                // used instead of the file browser.
                navController.navigate("home") {
                    popUpTo("permission") { inclusive = true }
                }
            },
        )
    }

    composable("filebrowser/{slot}") { backStackEntry ->
        val slot = backStackEntry.arguments?.getString("slot") ?: "left"
        FileBrowserScreen(
            onFilePicked = { file: File ->
                val ref = SourceRef(
                    id = UUID.randomUUID().toString(),
                    kind = SourceKind.LOCAL,
                    path = file.absolutePath,
                    label = file.name,
                )
                onFilePicked(slot, ref)
                navController.popBackStack()
            },
            onNavigateBack = { navController.popBackStack() },
        )
    }
}

/**
 * Direct flavour: take no persistable URI permission (paths are durable).
 */
fun takePersistablePermission(
    @Suppress("UNUSED_PARAMETER") contentResolver: android.content.ContentResolver,
    @Suppress("UNUSED_PARAMETER") uri: android.net.Uri,
) {
    // No-op for direct flavour — filesystem paths don't need URI grants.
}

/**
 * Direct flavour: read a file from the local filesystem into [DocumentRegistry].
 * Returns the loaded document, or null if the file cannot be read.
 * Must be called from an IO dispatcher.
 */
fun readFileIntoRegistry(path: String, id: String): DocumentRegistry.LoadedDocument? {
    val file = File(path)
    val label = file.name
    val sizeBytes = file.length()
    val text = try {
        file.bufferedReader().readText()
    } catch (_: java.io.IOException) {
        return null
    }
    val doc = DocumentRegistry.LoadedDocument(
        id = id,
        text = text,
        label = label,
        uri = android.net.Uri.fromFile(file).toString(),
        sizeBytes = sizeBytes,
    )
    DocumentRegistry.put(doc)
    return doc
}
