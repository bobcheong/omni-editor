package com.omnieditor.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.omnieditor.core.io.Capabilities
import com.omnieditor.core.io.Entry
import com.omnieditor.core.io.Resolved
import com.omnieditor.core.io.SourceProvider
import com.omnieditor.core.model.OmniError
import com.omnieditor.core.model.OmniException
import com.omnieditor.core.model.SourceRef
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DIST-2: the store flavour uses the Storage Access Framework only.
 * No MANAGE_EXTERNAL_STORAGE permission. Sessions persist URI grants.
 *
 * Persistable URI grants survive app restarts but can be revoked by the user
 * or by the providing app. When a grant is revoked, the session enters the
 * degraded "unresolvable" state (OE-SRC-6) rather than crashing.
 */
@Singleton
class SafSourceProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : SourceProvider {

    private val resolver: ContentResolver get() = context.contentResolver

    override suspend fun resolve(ref: SourceRef): Resolved = withContext(Dispatchers.IO) {
        val uri = resolveUri(ref)
        try {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) throw OmniException(OmniError.AccessRevoked(ref))
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val flagsIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)

                val flags = if (flagsIdx >= 0) cursor.getInt(flagsIdx) else 0
                val writable = flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0

                Resolved(
                    label = if (nameIdx >= 0) cursor.getString(nameIdx) ?: ref.label else ref.label,
                    size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L,
                    lastModified = if (modifiedIdx >= 0) cursor.getLong(modifiedIdx) else 0L,
                    mimeType = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null,
                    readable = true,
                    writable = writable,
                )
            } ?: throw OmniException(OmniError.AccessRevoked(ref))
        } catch (e: SecurityException) {
            throw OmniException(OmniError.AccessRevoked(ref))
        } catch (e: FileNotFoundException) {
            throw OmniException(OmniError.AccessRevoked(ref))
        }
    }

    override suspend fun open(ref: SourceRef): ReadableByteChannel = withContext(Dispatchers.IO) {
        val uri = resolveUri(ref)
        try {
            val stream = resolver.openInputStream(uri)
                ?: throw OmniException(OmniError.AccessRevoked(ref))
            Channels.newChannel(stream)
        } catch (e: SecurityException) {
            throw OmniException(OmniError.AccessRevoked(ref))
        } catch (e: FileNotFoundException) {
            throw OmniException(OmniError.AccessRevoked(ref))
        }
    }

    override suspend fun write(
        ref: SourceRef,
        from: ReadableByteChannel,
        atomic: Boolean,
    ) = withContext(Dispatchers.IO) {
        val uri = resolveUri(ref)
        // SAF does not support atomic rename; write directly.
        // The caller (merge safety, T-22) handles backup before write.
        try {
            resolver.openOutputStream(uri, "wt")?.use { out ->
                val outChannel = Channels.newChannel(out)
                val buf = java.nio.ByteBuffer.allocate(8192)
                while (from.read(buf) != -1) {
                    buf.flip()
                    outChannel.write(buf)
                    buf.clear()
                }
            } ?: throw OmniException(OmniError.WriteFailed(ref, partial = false))
        } catch (e: OmniException) {
            throw e
        } catch (e: SecurityException) {
            throw OmniException(OmniError.AccessRevoked(ref))
        } catch (e: Exception) {
            throw OmniException(OmniError.WriteFailed(ref, partial = true))
        }
    }

    override suspend fun list(ref: SourceRef, recursive: Boolean): Flow<Entry> = flow {
        // SAF tree listing — requires a tree URI, not a document URI
        val uri = resolveUri(ref)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            uri, DocumentsContract.getDocumentId(uri)
        )
        resolver.query(childrenUri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "" else ""
                val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                emit(
                    Entry(
                        name = name,
                        path = name, // SAF does not expose real paths
                        size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L,
                        lastModified = if (modifiedIdx >= 0) cursor.getLong(modifiedIdx) else 0L,
                        isDirectory = isDir,
                    )
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun capabilities(ref: SourceRef): Capabilities {
        return Capabilities(
            canRead = true,
            canWrite = true,
            canList = ref.uriGrant != null, // Only tree URIs support listing
            canSetModifiedTime = false, // SAF providers rarely support this
            canAtomicRename = false, // SAF does not expose rename-in-place
            supportsResume = false,
        )
    }

    override suspend fun isAccessible(ref: SourceRef): Boolean = withContext(Dispatchers.IO) {
        val uriString = ref.uriGrant ?: ref.path ?: return@withContext false
        try {
            val uri = Uri.parse(uriString)
            // Check if we still have a persistable grant
            val grants = resolver.persistedUriPermissions
            val hasGrant = grants.any { it.uri == uri && it.isReadPermission }
            if (!hasGrant) return@withContext false
            // Also verify the document still exists
            resolver.query(uri, null, null, null, null)?.use { it.moveToFirst() } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveUri(ref: SourceRef): Uri {
        val uriString = ref.uriGrant
            ?: throw OmniException(OmniError.AccessRevoked(ref))
        return Uri.parse(uriString)
    }
}
