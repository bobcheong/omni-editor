package com.omnieditor.app

/**
 * In-memory registry of loaded documents, keyed by SourceRef.id.
 *
 * Replaces ContentCache. The key is always the authoritative SourceRef.id so
 * that navigation routes, the editor, and the compare screen all refer to the
 * same document by the same identity.
 *
 * Documents are read off the main thread by callers (LaunchedEffect / IO
 * dispatcher) before being stored here. This registry never reads from disk.
 *
 * LRU eviction: when more than [MAX_ENTRIES] documents are cached, the least
 * recently used entries that are NOT pinned are evicted. Callers pin a document
 * with [pin] when it is actively open and must not be evicted (e.g. it has
 * unsaved edits). Unpin with [unpin] when the screen closes.
 */
object DocumentRegistry {

    data class LoadedDocument(
        /** Matches SourceRef.id — the single authoritative identity. */
        val id: String,
        val text: String,
        val label: String,
        /** URI or filesystem path string used to reload the document if needed. */
        val uri: String,
        /** Raw byte size as reported by the source. -1 if unavailable. */
        val sizeBytes: Long = -1L,
    )

    private const val MAX_ENTRIES = 10

    // Insertion-order map; we evict from the front (oldest) when over capacity.
    private val documents = LinkedHashMap<String, LoadedDocument>()

    // IDs of documents that must never be evicted (actively open or dirty).
    private val pinnedIds = mutableSetOf<String>()

    /** Mark a document as active so it survives LRU eviction. */
    fun pin(id: String) { synchronized(documents) { pinnedIds.add(id) } }

    /** Release the eviction protection for [id]. */
    fun unpin(id: String) { synchronized(documents) { pinnedIds.remove(id) } }

    fun put(doc: LoadedDocument) {
        synchronized(documents) {
            // Move to end (most recently used) if already present.
            documents.remove(doc.id)
            documents[doc.id] = doc
            // Evict oldest unpinned entries until within capacity.
            if (documents.size > MAX_ENTRIES) {
                val iter = documents.keys.iterator()
                while (documents.size > MAX_ENTRIES && iter.hasNext()) {
                    val key = iter.next()
                    if (key !in pinnedIds) iter.remove()
                }
            }
        }
    }

    fun get(id: String): LoadedDocument? = synchronized(documents) {
        // Touch: move to end to record recent use.
        val doc = documents.remove(id) ?: return null
        documents[id] = doc
        doc
    }

    fun remove(id: String) {
        synchronized(documents) {
            documents.remove(id)
            pinnedIds.remove(id)
        }
    }
}
