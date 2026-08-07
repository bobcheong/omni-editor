package com.omnieditor.core.io

import com.omnieditor.core.model.SourceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Recents and favourites storage (OE-SRC-3).
 *
 * Recently used files, folders and connections appear first.
 * Favourites are pinnable and survive reinstall via backup.
 */
class RecentsStore(private val storeFile: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val mutex = Mutex()
    private var cache: RecentsData? = null

    /**
     * Record a source as recently used.
     */
    suspend fun addRecent(ref: SourceRef) {
        mutex.withLock {
            val data = loadData()
            // Remove existing entry for this source (by id)
            data.recents.removeAll { it.id == ref.id }
            // Add to front
            data.recents.add(0, ref)
            // Cap at 100
            while (data.recents.size > MAX_RECENTS) data.recents.removeAt(data.recents.lastIndex)
            saveData(data)
        }
    }

    /**
     * Get recent sources, newest first.
     */
    suspend fun getRecents(): List<SourceRef> {
        return mutex.withLock { loadData().recents.toList() }
    }

    /**
     * Toggle a source as favourite.
     */
    suspend fun toggleFavourite(refId: String) {
        mutex.withLock {
            val data = loadData()
            if (data.favouriteIds.contains(refId)) {
                data.favouriteIds.remove(refId)
            } else {
                data.favouriteIds.add(refId)
            }
            saveData(data)
        }
    }

    /**
     * Check if a source is a favourite.
     */
    suspend fun isFavourite(refId: String): Boolean {
        return mutex.withLock { loadData().favouriteIds.contains(refId) }
    }

    /**
     * Get all favourite source IDs.
     */
    suspend fun getFavouriteIds(): Set<String> {
        return mutex.withLock { loadData().favouriteIds.toSet() }
    }

    /**
     * Remove a source from recents.
     */
    suspend fun removeRecent(refId: String) {
        mutex.withLock {
            val data = loadData()
            data.recents.removeAll { it.id == refId }
            saveData(data)
        }
    }

    /**
     * Clear all recents (favourites preserved).
     */
    suspend fun clearRecents() {
        mutex.withLock {
            val data = loadData()
            data.recents.clear()
            saveData(data)
        }
    }

    private fun loadData(): RecentsData {
        cache?.let { return it }
        val data = if (storeFile.exists()) {
            try {
                json.decodeFromString(RecentsData.serializer(), storeFile.readText())
            } catch (_: Exception) {
                RecentsData()
            }
        } else {
            RecentsData()
        }
        cache = data
        return data
    }

    private suspend fun saveData(data: RecentsData) {
        cache = data
        withContext(Dispatchers.IO) {
            storeFile.parentFile?.mkdirs()
            storeFile.writeText(json.encodeToString(RecentsData.serializer(), data))
        }
    }

    @Serializable
    private data class RecentsData(
        val recents: MutableList<SourceRef> = mutableListOf(),
        val favouriteIds: MutableList<String> = mutableListOf(),
    )

    companion object {
        const val MAX_RECENTS = 100
    }
}
