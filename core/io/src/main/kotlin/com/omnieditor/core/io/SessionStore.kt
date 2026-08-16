package com.omnieditor.core.io

import com.omnieditor.core.model.Session
import com.omnieditor.core.model.SessionGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists sessions to disk (OE-SES-1..3).
 *
 * Sessions store: source references and grants, compare mode, ignore rules,
 * filters, display settings, bookmarks, and the last result summary.
 * Sessions are named, groupable into folders, searchable, and pinnable.
 *
 * Memory-pressure eviction: inactive session results are evicted to disk
 * via the ResultStore, but session definitions (metadata) are always kept.
 */
class SessionStore(private val sessionDir: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, Session>()

    /**
     * Save or update a session.
     */
    suspend fun save(session: Session) {
        mutex.withLock {
            cache[session.id] = session
            withContext(Dispatchers.IO) {
                sessionDir.mkdirs()
                val file = File(sessionDir, "${session.id}.json")
                val wrapper = VersionedSession(schemaVersion = SCHEMA_VERSION, data = session)
                file.writeText(json.encodeToString(VersionedSession.serializer(), wrapper))
            }
        }
    }

    /**
     * Load a session by ID.
     */
    suspend fun load(sessionId: String): Session? {
        return mutex.withLock {
            cache[sessionId] ?: withContext(Dispatchers.IO) {
                val file = File(sessionDir, "$sessionId.json")
                if (!file.exists()) return@withContext null
                try {
                    val wrapper = json.decodeFromString(VersionedSession.serializer(), file.readText())
                    if (wrapper.schemaVersion != SCHEMA_VERSION) return@withContext null
                    val session = wrapper.data
                    cache[sessionId] = session
                    session
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    /**
     * List all sessions, sorted by last run time (newest first).
     */
    suspend fun listAll(): List<Session> {
        return mutex.withLock {
            ensureLoaded()
            cache.values.sortedByDescending { it.lastRunAt ?: it.createdAt }
        }
    }

    /**
     * List pinned sessions.
     */
    suspend fun listPinned(): List<Session> {
        return listAll().filter { it.pinned }
    }

    /**
     * Search sessions by name.
     */
    suspend fun search(query: String): List<Session> {
        val q = query.lowercase()
        return listAll().filter { it.name.lowercase().contains(q) }
    }

    /**
     * Delete a session.
     */
    suspend fun delete(sessionId: String) {
        mutex.withLock {
            cache.remove(sessionId)
            withContext(Dispatchers.IO) {
                File(sessionDir, "$sessionId.json").delete()
            }
        }
    }

    /**
     * Pin or unpin a session.
     */
    suspend fun togglePin(sessionId: String) {
        val session = load(sessionId) ?: return
        save(session.copy(pinned = !session.pinned))
    }

    /**
     * Rename a session.
     */
    suspend fun rename(sessionId: String, newName: String) {
        val session = load(sessionId) ?: return
        save(session.copy(name = newName))
    }

    /**
     * Duplicate a session with a new ID and name.
     */
    suspend fun duplicate(sessionId: String, newId: String, newName: String): Session? {
        val session = load(sessionId) ?: return null
        val copy = session.copy(id = newId, name = newName, createdAt = System.currentTimeMillis())
        save(copy)
        return copy
    }

    /**
     * Count total sessions.
     */
    suspend fun count(): Int {
        return mutex.withLock {
            ensureLoaded()
            cache.size
        }
    }

    private suspend fun ensureLoaded() {
        if (cache.isNotEmpty()) return
        withContext(Dispatchers.IO) {
            if (!sessionDir.exists()) return@withContext
            sessionDir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
                try {
                    val wrapper = json.decodeFromString(VersionedSession.serializer(), file.readText())
                    if (wrapper.schemaVersion == SCHEMA_VERSION) {
                        cache[wrapper.data.id] = wrapper.data
                    }
                    // Unknown schema version: skip silently (graceful degradation)
                } catch (_: Exception) {
                    // Corrupted or unreadable file: skip silently
                }
            }
        }
    }

    // ── Session group CRUD (F-13) ──

    private val groupDir: File = File(sessionDir, "groups").apply { mkdirs() }

    /** Create a new named group and persist it. */
    fun createGroup(name: String): SessionGroup {
        val group = SessionGroup(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
        )
        saveGroupBlocking(group)
        return group
    }

    /** Delete a group by ID (sessions are NOT deleted). */
    fun deleteGroup(id: String) {
        File(groupDir, "$id.json").delete()
    }

    /** Add [sessionId] to [groupId] if not already present. */
    fun addToGroup(groupId: String, sessionId: String) {
        val group = loadGroupBlocking(groupId) ?: return
        if (sessionId !in group.sessionIds) {
            saveGroupBlocking(group.copy(sessionIds = group.sessionIds + sessionId))
        }
    }

    /** Remove [sessionId] from [groupId]. */
    fun removeFromGroup(groupId: String, sessionId: String) {
        val group = loadGroupBlocking(groupId) ?: return
        saveGroupBlocking(group.copy(sessionIds = group.sessionIds - sessionId))
    }

    /** List all groups sorted by name. */
    fun listGroups(): List<SessionGroup> {
        return groupDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    json.decodeFromString(SessionGroup.serializer(), file.readText())
                } catch (_: Exception) {
                    null
                }
            }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun saveGroupBlocking(group: SessionGroup) {
        groupDir.mkdirs()
        File(groupDir, "${group.id}.json")
            .writeText(json.encodeToString(SessionGroup.serializer(), group))
    }

    private fun loadGroupBlocking(id: String): SessionGroup? {
        val file = File(groupDir, "$id.json")
        if (!file.exists()) return null
        return try {
            json.decodeFromString(SessionGroup.serializer(), file.readText())
        } catch (_: Exception) {
            null
        }
    }

    // ── JSON export / import (F-12) ──

    // A separate Json instance that always encodes default values so schemaVersion
    // is always present in the exported JSON even when it equals the default.
    private val exportJson = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }

    @Serializable
    private data class SessionExport(
        val schemaVersion: Int = SCHEMA_VERSION,
        val session: Session,
    )

    /**
     * Export a single session as a self-contained JSON string including a schemaVersion field.
     * Throws [IllegalArgumentException] if the session is not found.
     */
    fun exportAsJson(sessionId: String): String {
        val file = File(sessionDir, "$sessionId.json")
        if (!file.exists()) throw IllegalArgumentException("Session $sessionId not found")
        val wrapper = try {
            json.decodeFromString(VersionedSession.serializer(), file.readText())
        } catch (e: Exception) {
            throw IllegalArgumentException("Session $sessionId could not be read: ${e.message}")
        }
        val export = SessionExport(session = wrapper.data)
        return exportJson.encodeToString(SessionExport.serializer(), export)
    }

    /**
     * Import a session from a JSON string produced by [exportAsJson].
     * A new ID is assigned to avoid collisions. The session is persisted and returned.
     */
    fun importFromJson(jsonString: String): Session {
        val export = json.decodeFromString(SessionExport.serializer(), jsonString)
        val session = export.session.copy(id = java.util.UUID.randomUUID().toString())
        val wrapper = VersionedSession(schemaVersion = SCHEMA_VERSION, data = session)
        sessionDir.mkdirs()
        File(sessionDir, "${session.id}.json")
            .writeText(json.encodeToString(VersionedSession.serializer(), wrapper))
        // Update in-memory cache
        cache[session.id] = session
        return session
    }

    @Serializable
    private data class VersionedSession(
        val schemaVersion: Int = 1,
        val data: Session,
    )

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
