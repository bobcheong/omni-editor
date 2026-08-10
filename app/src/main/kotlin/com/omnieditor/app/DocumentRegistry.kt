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
    private val documents = LinkedHashMap<String, LoadedDocument>()

    fun put(doc: LoadedDocument) {
        synchronized(documents) {
            documents[doc.id] = doc
            while (documents.size > MAX_ENTRIES) {
                documents.remove(documents.keys.first())
            }
        }
    }

    fun get(id: String): LoadedDocument? = synchronized(documents) { documents[id] }

    fun remove(id: String) { synchronized(documents) { documents.remove(id) } }
}
