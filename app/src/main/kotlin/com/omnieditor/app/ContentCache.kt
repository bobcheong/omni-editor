package com.omnieditor.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/**
 * Reads file content immediately while SAF permission is active.
 * Queries ContentResolver for the actual display name and size before reading.
 * Size is stored so callers can enforce DocumentLimits without re-reading.
 */
object ContentCache {

    private val cache = LinkedHashMap<String, CachedContent>()
    private const val MAX_ENTRIES = 10

    data class CachedContent(
        val label: String,
        val text: String,
        val uri: String,
        /** Raw byte size as reported by ContentResolver. -1 if unavailable. */
        val sizeBytes: Long,
    )

    fun readAndCache(context: Context, uri: Uri): String {
        val key = uri.toString().hashCode().toString(16)
        return readAndCache(context, uri, id = key)
    }

    /**
     * Read and cache a URI using [id] as the cache key.
     * Use this overload when a [SourceRef] has already been created so that
     * SourceRef.id and the ContentCache key are identical.
     */
    fun readAndCache(context: Context, uri: Uri, id: String): String {
        val (label, sizeBytes) = queryMeta(context, uri)
        val text = try {
            context.contentResolver.openInputStream(uri)?.use {
                it.bufferedReader().readText()
            } ?: ""
        } catch (e: IOException) {
            "Error reading file: ${e.message}"
        }

        synchronized(cache) {
            cache[id] = CachedContent(label, text, uri.toString(), sizeBytes)
            while (cache.size > MAX_ENTRIES) {
                cache.remove(cache.keys.first())
            }
        }

        return id
    }

    /**
     * R-23a: read a file directly from the filesystem (direct flavour).
     * No ContentResolver needed — the path is durable.
     */
    fun readAndCache(@Suppress("UNUSED_PARAMETER") context: Context, file: File): String {
        val key = file.absolutePath.hashCode().toString(16)
        return readAndCache(context, file, id = key)
    }

    /**
     * R-23a: read a file directly from the filesystem (direct flavour), using [id]
     * as the cache key so that SourceRef.id and ContentCache key are identical.
     */
    fun readAndCache(
        @Suppress("UNUSED_PARAMETER") context: Context,
        file: File,
        id: String,
    ): String {
        val label = file.name
        val sizeBytes = file.length()
        val text = try {
            file.bufferedReader().readText()
        } catch (e: IOException) {
            "Error reading file: ${e.message}"
        }

        synchronized(cache) {
            cache[id] = CachedContent(label, text, Uri.fromFile(file).toString(), sizeBytes)
            while (cache.size > MAX_ENTRIES) {
                cache.remove(cache.keys.first())
            }
        }

        return id
    }

    fun get(key: String): CachedContent? = synchronized(cache) { cache[key] }

    fun remove(key: String) = synchronized(cache) { cache.remove(key) }

    /**
     * Query display name and byte size from ContentResolver in a single pass.
     * Returns (label, sizeBytes) — sizeBytes is -1 if the provider does not report it.
     */
    private fun queryMeta(context: Context, uri: Uri): Pair<String, Long> {
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
                    val label = if (!name.isNullOrBlank()) name else fallbackLabel(uri)
                    return Pair(label, size)
                }
            }
        } catch (_: Exception) { }

        return Pair(fallbackLabel(uri), -1L)
    }

    private fun fallbackLabel(uri: Uri): String {
        val path = uri.path ?: uri.toString()
        return path.substringAfterLast('/').substringAfterLast(':').ifBlank { "file" }
    }
}
