package com.omnieditor.core.io

import com.omnieditor.core.model.Fingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Merge safety layer (OE-MRG-5, OE-MRG-6, OE-MRG-7).
 *
 * - Pre-write backup: timestamped copy before the first byte is written
 * - Restore original: available for 30 days (configurable, size-capped)
 * - External change detection: re-fingerprint on resume
 * - Dirty-state tracking: unsaved changes survive process death
 */
object MergeSafety {

    /** Default backup retention in days. */
    const val DEFAULT_RETENTION_DAYS = 30

    /** Default max backup storage in bytes (500 MB). */
    const val DEFAULT_MAX_STORAGE_BYTES = 500L * 1024 * 1024

    /**
     * Create a timestamped backup before writing.
     * Returns the backup file path, or null if backup failed.
     */
    suspend fun createBackup(
        sourceFile: File,
        backupDir: File,
        sessionId: String,
    ): File? = withContext(Dispatchers.IO) {
        if (!sourceFile.exists()) return@withContext null

        backupDir.mkdirs()
        val timestamp = System.currentTimeMillis()
        val backupName = "${sessionId}_${timestamp}_${sourceFile.name}"
        val backupFile = File(backupDir, backupName)

        try {
            sourceFile.copyTo(backupFile, overwrite = false)
            backupFile
        } catch (e: IOException) {
            null
        }
    }

    /**
     * List available backups for a session, newest first.
     */
    fun listBackups(backupDir: File, sessionId: String): List<BackupInfo> {
        if (!backupDir.exists()) return emptyList()

        return backupDir.listFiles()
            ?.filter { it.name.startsWith("${sessionId}_") }
            ?.map { file ->
                val parts = file.name.split("_", limit = 3)
                val timestamp = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                val originalName = parts.getOrNull(2) ?: file.name
                BackupInfo(file, timestamp, originalName, file.length())
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    /**
     * Restore a backup to the original location.
     */
    suspend fun restoreBackup(
        backup: BackupInfo,
        targetFile: File,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            backup.file.copyTo(targetFile, overwrite = true)
            true
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Clean up old backups beyond the retention period and size cap.
     */
    suspend fun cleanBackups(
        backupDir: File,
        retentionDays: Int = DEFAULT_RETENTION_DAYS,
        maxStorageBytes: Long = DEFAULT_MAX_STORAGE_BYTES,
    ): Int = withContext(Dispatchers.IO) {
        if (!backupDir.exists()) return@withContext 0

        val cutoff = System.currentTimeMillis() - (retentionDays.toLong() * 24 * 60 * 60 * 1000)
        var deleted = 0

        // Delete expired backups
        val allBackups = backupDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return@withContext 0
        for (file in allBackups) {
            if (file.lastModified() < cutoff) {
                if (file.delete()) deleted++
            }
        }

        // Enforce size cap (delete oldest first)
        val remaining = backupDir.listFiles()?.sortedBy { it.lastModified() } ?: return@withContext deleted
        var totalSize = remaining.sumOf { it.length() }
        for (file in remaining) {
            if (totalSize <= maxStorageBytes) break
            totalSize -= file.length()
            if (file.delete()) deleted++
        }

        deleted
    }

    /**
     * Compute a fingerprint for a file (OE-MRG-7).
     * Uses size + modified time + edge hash (first/last 4KB).
     */
    suspend fun fingerprint(file: File): Fingerprint? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null

        val size = file.length()
        val modified = file.lastModified()

        // Edge hash: first 4KB + last 4KB
        val edgeHash = try {
            val md = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(4096)

            file.inputStream().use { stream ->
                val firstRead = stream.read(buffer)
                if (firstRead > 0) md.update(buffer, 0, firstRead)
            }

            if (size > 4096) {
                file.inputStream().use { stream ->
                    stream.skip(maxOf(0, size - 4096))
                    val lastRead = stream.read(buffer)
                    if (lastRead > 0) md.update(buffer, 0, lastRead)
                }
            }

            md.digest().joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: IOException) {
            "error"
        }

        Fingerprint(size, modified, edgeHash)
    }

    /**
     * Check if a file has been modified externally since it was opened.
     * Returns the change detection result.
     */
    fun detectExternalChange(
        savedFingerprint: Fingerprint,
        currentFingerprint: Fingerprint,
    ): ExternalChangeResult {
        if (savedFingerprint == currentFingerprint) {
            return ExternalChangeResult.UNCHANGED
        }
        if (savedFingerprint.size != currentFingerprint.size) {
            return ExternalChangeResult.CHANGED
        }
        if (savedFingerprint.modifiedAt != currentFingerprint.modifiedAt) {
            return ExternalChangeResult.CHANGED
        }
        if (savedFingerprint.edgeHash != currentFingerprint.edgeHash) {
            return ExternalChangeResult.CHANGED
        }
        return ExternalChangeResult.UNCHANGED
    }
}

data class BackupInfo(
    val file: File,
    val timestamp: Long,
    val originalName: String,
    val size: Long,
)

enum class ExternalChangeResult {
    /** File has not changed since it was opened. */
    UNCHANGED,
    /** File has been modified externally — banner needed. */
    CHANGED,
}
