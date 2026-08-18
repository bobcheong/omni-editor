package com.omnieditor.core.io

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.Channels

/**
 * Save/backup orchestration extracted from CompareCoordinator (Move 2a).
 *
 * Navigation-agnostic, Compose-free, and Android-import-free — unit-testable
 * on the JVM. The coordinator keeps the ContentResolver-based write path for
 * content:// URIs; this object handles the file:// case where an atomic
 * temp-rename write is available.
 *
 * Sequence per OE-MRG-5:
 *  1. Create a pre-write backup via [MergeSafety.createBackup].
 *  2. Abort if backup fails — never write without a valid backup.
 *  3. Materialise the document to a temp file next to the target.
 *  4. fsync + atomic rename to the target path.
 *
 * The caller remains responsible for:
 *  - Fingerprint checking (external change detection, R-28).
 *  - Calling [TextDocument.markSaved] after a successful save (if the
 *    implementation exposes that method).
 *  - Updating UI fingerprint state.
 */
object SaveOrchestrator {

    /**
     * Result of a [saveWithBackup] call.
     *
     * @param success     Whether the save completed without error.
     * @param backupPath  The backup file created before writing, or null if backup
     *                    was skipped (source file did not exist) or failed.
     * @param error       Human-readable error message, or null on success.
     */
    data class SaveResult(
        val success: Boolean,
        val backupPath: File?,
        val error: String?,
    )

    /**
     * Save [document] to [targetFile], creating a pre-write backup in [backupDir].
     *
     * The write is atomic: the document is materialised to a temp file
     * `.omni-save-<name>.tmp` adjacent to [targetFile], then renamed.
     * If either backup or rename fails the original file is untouched.
     *
     * @param document   The document whose current state is to be written.
     * @param targetFile Destination on the local filesystem (file:// path).
     * @param backupDir  Directory for pre-write backups.
     * @param sessionId  Session identifier embedded in the backup filename.
     * @return [SaveResult] indicating success/failure and the backup path.
     */
    suspend fun saveWithBackup(
        document: TextDocument,
        targetFile: File,
        backupDir: File,
        sessionId: String,
    ): SaveResult = withContext(Dispatchers.IO) {
        // 1. Pre-write backup (R-51: abort if backup fails)
        val backupFile = if (targetFile.exists()) {
            MergeSafety.createBackup(targetFile, backupDir, sessionId)
                ?: return@withContext SaveResult(
                    success = false,
                    backupPath = null,
                    error = "Backup failed: could not copy ${targetFile.name} to backup directory",
                )
        } else {
            // Source does not exist yet (Save As to a new path) — no backup needed.
            null
        }

        // 2. Materialise to temp file + fsync + atomic rename (R-57)
        val tempFile = File(targetFile.parent, ".omni-save-${targetFile.name}.tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                val channel = fos.channel
                document.materialise(Channels.newChannel(fos))
                channel.force(true) // fsync before rename
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.delete()
                return@withContext SaveResult(
                    success = false,
                    backupPath = backupFile,
                    error = "Save failed: atomic rename of temp file failed for ${targetFile.name}",
                )
            }
        } catch (e: Exception) {
            tempFile.delete()
            return@withContext SaveResult(
                success = false,
                backupPath = backupFile,
                error = "Save failed: ${e.message}",
            )
        }

        SaveResult(success = true, backupPath = backupFile, error = null)
    }
}
