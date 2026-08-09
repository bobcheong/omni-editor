package com.omnieditor.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

/**
 * Reads file content immediately while SAF permission is active.
 * Queries ContentResolver for the actual display name (not the raw document ID).
 */
object ContentCache {

    private val cache = LinkedHashMap<String, CachedContent>()
    private const val MAX_ENTRIES = 10

    data class CachedContent(
        val label: String,
        val text: String,
        val uri: String,
    )

    fun readAndCache(context: Context, uri: Uri): String {
        val key = uri.toString().hashCode().toString(16)
        val label = queryDisplayName(context, uri)
        val text = try {
            context.contentResolver.openInputStream(uri)?.use {
                it.bufferedReader().readText()
            } ?: ""
        } catch (e: IOException) {
            "Error reading file: ${e.message}"
        }

        synchronized(cache) {
            cache[key] = CachedContent(label, text, uri.toString())
            while (cache.size > MAX_ENTRIES) {
                cache.remove(cache.keys.first())
            }
        }

        return key
    }

    fun get(key: String): CachedContent? = synchronized(cache) { cache[key] }

    fun remove(key: String) = synchronized(cache) { cache.remove(key) }

    /**
     * Query the actual display name from ContentResolver.
     * Falls back to parsing the URI if the query fails.
     */
    private fun queryDisplayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        } catch (_: Exception) { }

        // Fallback: try to extract something readable from the URI
        val path = uri.path ?: uri.toString()
        return path.substringAfterLast('/').substringAfterLast(':').ifBlank { "file" }
    }
}
