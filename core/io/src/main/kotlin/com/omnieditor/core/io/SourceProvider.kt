package com.omnieditor.core.io

import com.omnieditor.core.model.SourceRef
import kotlinx.coroutines.flow.Flow
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel

/**
 * One abstraction over every source kind (spec §10).
 *
 * The `direct` flavour resolves by path with all-files access (DIST-2).
 * The `store` flavour resolves by URI grant via the Storage Access Framework.
 * The rest of the app is unaware which implementation it has — this is the seam.
 *
 * All methods are cancellable coroutines. Failures surface as [com.omnieditor.core.model.OmniError]
 * variants via [com.omnieditor.core.model.OmniException], never generic exceptions.
 */
interface SourceProvider {

    /**
     * Resolve a source reference to its current metadata.
     * Returns null if the source is not accessible (revoked grant, missing file).
     * Throws [com.omnieditor.core.model.OmniException] with [com.omnieditor.core.model.OmniError.AccessRevoked]
     * if access was explicitly revoked.
     */
    suspend fun resolve(ref: SourceRef): Resolved

    /**
     * Open a readable byte channel to the source content.
     * The caller is responsible for closing the channel.
     */
    suspend fun open(ref: SourceRef): ReadableByteChannel

    /**
     * Write content to the source. If [atomic] is true, writes to a temporary
     * file first, then renames (OE-MRG-5 safety guarantee).
     */
    suspend fun write(ref: SourceRef, from: ReadableByteChannel, atomic: Boolean = true)

    /**
     * List entries in a directory-like source (folder compare, archive).
     * Not all source kinds support this — check [capabilities] first.
     */
    suspend fun list(ref: SourceRef, recursive: Boolean = false): Flow<Entry>

    /**
     * What this source kind can do — determines available UI actions.
     */
    fun capabilities(ref: SourceRef): Capabilities

    /**
     * Check whether a previously resolved source is still accessible.
     * Returns false if the grant was revoked or the file was deleted.
     */
    suspend fun isAccessible(ref: SourceRef): Boolean
}

/**
 * Resolved metadata for a source. Shown in the source setup screen (S-02)
 * and in session rows on Home (S-01).
 */
data class Resolved(
    val label: String,
    val size: Long,
    val lastModified: Long,
    val mimeType: String?,
    val readable: Boolean,
    val writable: Boolean,
)

/**
 * An entry in a directory listing (folder compare, archive browsing).
 */
data class Entry(
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
)

/**
 * What operations a source supports. Determined by the source kind and
 * the flavour's storage model.
 */
data class Capabilities(
    val canRead: Boolean = true,
    val canWrite: Boolean = false,
    val canList: Boolean = false,
    val canSetModifiedTime: Boolean = false,
    val canAtomicRename: Boolean = false,
    val supportsResume: Boolean = false,
)
