package com.omnieditor.app

import android.content.Context
import android.net.Uri

/**
 * Reads file content immediately while SAF permission is active.
 * Google Drive and other cloud providers grant temporary read access
 * through the picker — we must read before navigating away.
 */
object ContentCache {

    private val cache = LinkedHashMap<String, CachedContent>()
    private const val MAX_ENTRIES = 10

    data class CachedContent(
        val label: String,
        val text: String,
        val uri: String,
    )

    /**
     * Read content from a URI immediately (while picker permission is active)
     * and cache it for later retrieval by the editor/compare screens.
     * Returns a cache key.
     */
    fun readAndCache(context: Context, uri: Uri): String {
        val key = uri.toString().hashCode().toString(16)
        val label = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val text = try {
            context.contentResolver.openInputStream(uri)?.use {
                it.bufferedReader().readText()
            } ?: ""
        } catch (e: Exception) {
            "Error reading file: ${e.message}"
        }

        synchronized(cache) {
            cache[key] = CachedContent(label, text, uri.toString())
            // Evict oldest if over limit
            while (cache.size > MAX_ENTRIES) {
                cache.remove(cache.keys.first())
            }
        }

        return key
    }

    fun get(key: String): CachedContent? = synchronized(cache) { cache[key] }

    fun remove(key: String) = synchronized(cache) { cache.remove(key) }
}
