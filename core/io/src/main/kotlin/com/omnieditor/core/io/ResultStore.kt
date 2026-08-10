package com.omnieditor.core.io

import com.omnieditor.core.model.CompareResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Caches [CompareResult] to disk by session ID so results survive process death.
 *
 * When a compare is interrupted (killed at 80%) and the app reopens, the last
 * stored result is restored without recomputing. Partial results are stored
 * incrementally as hunks stream in.
 *
 * Storage format: JSON in `{cacheDir}/{sessionId}.json`.
 * Thread-safe via per-session mutex.
 */
class ResultStore(private val cacheDir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private val locks = HashMap<String, Mutex>()

    private fun mutexFor(sessionId: String): Mutex {
        return synchronized(locks) {
            locks.getOrPut(sessionId) { Mutex() }
        }
    }

    /**
     * Store a complete compare result for a session.
     */
    suspend fun store(sessionId: String, result: CompareResult) {
        mutexFor(sessionId).withLock {
            withContext(Dispatchers.IO) {
                cacheDir.mkdirs()
                val file = File(cacheDir, "$sessionId.json")
                val tmpFile = File(cacheDir, "$sessionId.tmp")
                try {
                    val wrapper = VersionedResult(schemaVersion = SCHEMA_VERSION, data = result)
                    tmpFile.writeText(json.encodeToString(VersionedResult.serializer(), wrapper))
                    tmpFile.renameTo(file)
                } catch (e: IOException) {
                    tmpFile.delete()
                    throw e
                } catch (e: SerializationException) {
                    tmpFile.delete()
                    throw e
                }
            }
        }
    }

    /**
     * Load a cached compare result for a session.
     * Returns null if no cached result exists, the cache is corrupted, or the
     * schema version is unknown (graceful degradation — triggers recompute).
     */
    suspend fun load(sessionId: String): CompareResult? {
        return mutexFor(sessionId).withLock {
            withContext(Dispatchers.IO) {
                val file = File(cacheDir, "$sessionId.json")
                if (!file.exists()) return@withContext null
                try {
                    val wrapper = json.decodeFromString(VersionedResult.serializer(), file.readText())
                    if (wrapper.schemaVersion != SCHEMA_VERSION) {
                        // Unknown version: discard rather than crash
                        file.delete()
                        return@withContext null
                    }
                    wrapper.data
                } catch (e: IOException) {
                    // Corrupted or unreadable cache — delete and return null
                    file.delete()
                    null
                } catch (e: SerializationException) {
                    // Corrupted cache format — delete and return null
                    file.delete()
                    null
                }
            }
        }
    }

    /**
     * Check if a cached result exists for a session.
     */
    fun has(sessionId: String): Boolean {
        return File(cacheDir, "$sessionId.json").exists()
    }

    /**
     * Delete the cached result for a session.
     */
    suspend fun evict(sessionId: String) {
        mutexFor(sessionId).withLock {
            withContext(Dispatchers.IO) {
                File(cacheDir, "$sessionId.json").delete()
                File(cacheDir, "$sessionId.tmp").delete()
            }
        }
    }

    /**
     * Delete all cached results.
     */
    suspend fun evictAll() {
        withContext(Dispatchers.IO) {
            cacheDir.listFiles()?.forEach { it.delete() }
        }
    }

    /**
     * Total size of the cache in bytes.
     */
    fun cacheSize(): Long {
        return cacheDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.sumOf { it.length() }
            ?: 0L
    }

    @Serializable
    private data class VersionedResult(
        val schemaVersion: Int = 1,
        val data: CompareResult,
    )

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
