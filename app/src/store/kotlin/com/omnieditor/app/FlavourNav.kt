package com.omnieditor.app

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import com.omnieditor.core.model.SourceRef

/**
 * Store flavour: always starts at "home" (no filesystem permission needed).
 */
fun flavourStartDestination(): String = "home"

/**
 * Store flavour: no built-in file browser (uses SAF picker).
 */
fun hasFlavourFileBrowser(): Boolean = false

/**
 * Store flavour: no flavour-specific destinations.
 */
fun NavGraphBuilder.flavourDestinations(
    @Suppress("UNUSED_PARAMETER") navController: NavHostController,
    @Suppress("UNUSED_PARAMETER") onFilePicked: (slot: String, SourceRef) -> Unit,
) {
    // No additional routes for the store flavour.
}

/**
 * Store flavour: take persistable URI permission so SAF URIs survive app restart.
 * Called after receiving a URI from [ActivityResultContracts.OpenDocument].
 */
fun takePersistablePermission(contentResolver: ContentResolver, uri: Uri) {
    try {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, flags)
    } catch (_: SecurityException) {
        // Some providers don't support persistable grants; degrade gracefully.
        // The recents entry will fail isAccessible() on next launch, which is
        // the expected degraded path (OE-SRC-6).
    }
}
