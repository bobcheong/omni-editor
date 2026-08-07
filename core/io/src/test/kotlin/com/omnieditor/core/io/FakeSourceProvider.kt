package com.omnieditor.core.io

import com.omnieditor.core.model.OmniError
import com.omnieditor.core.model.OmniException
import com.omnieditor.core.model.SourceKind
import com.omnieditor.core.model.SourceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.ByteArrayInputStream
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel

/**
 * In-memory SourceProvider for testing the interface contract.
 * Simulates files as byte arrays in a map, with configurable access revocation.
 */
class FakeSourceProvider : SourceProvider {

    /** id → content */
    private val files = mutableMapOf<String, ByteArray>()

    /** IDs whose access has been revoked. */
    private val revoked = mutableSetOf<String>()

    /** Written content, captured for assertions. */
    val written = mutableMapOf<String, ByteArray>()

    fun addFile(id: String, content: ByteArray) {
        files[id] = content
    }

    fun addFile(id: String, content: String) = addFile(id, content.toByteArray())

    fun revokeAccess(id: String) {
        revoked.add(id)
    }

    fun restoreAccess(id: String) {
        revoked.remove(id)
    }

    private fun ref(id: String) = SourceRef(
        id = id,
        kind = SourceKind.LOCAL,
        path = "/fake/$id",
        label = id,
    )

    private fun checkAccess(ref: SourceRef) {
        if (ref.id in revoked) {
            throw OmniException(OmniError.AccessRevoked(ref))
        }
        if (ref.id !in files) {
            throw OmniException(OmniError.AccessRevoked(ref))
        }
    }

    override suspend fun resolve(ref: SourceRef): Resolved {
        checkAccess(ref)
        val content = files[ref.id]!!
        return Resolved(
            label = ref.label,
            size = content.size.toLong(),
            lastModified = System.currentTimeMillis(),
            mimeType = "text/plain",
            readable = true,
            writable = ref.id !in revoked,
        )
    }

    override suspend fun open(ref: SourceRef): ReadableByteChannel {
        checkAccess(ref)
        return Channels.newChannel(ByteArrayInputStream(files[ref.id]!!))
    }

    override suspend fun write(ref: SourceRef, from: ReadableByteChannel, atomic: Boolean) {
        checkAccess(ref)
        val buf = java.io.ByteArrayOutputStream()
        val readBuf = java.nio.ByteBuffer.allocate(8192)
        while (from.read(readBuf) != -1) {
            readBuf.flip()
            buf.write(readBuf.array(), readBuf.position(), readBuf.remaining())
            readBuf.clear()
        }
        val bytes = buf.toByteArray()
        files[ref.id] = bytes
        written[ref.id] = bytes
    }

    override suspend fun list(ref: SourceRef, recursive: Boolean): Flow<Entry> {
        checkAccess(ref)
        return emptyFlow()
    }

    override fun capabilities(ref: SourceRef): Capabilities {
        return Capabilities(
            canRead = true,
            canWrite = true,
            canList = false,
            canAtomicRename = true,
        )
    }

    override suspend fun isAccessible(ref: SourceRef): Boolean {
        return ref.id in files && ref.id !in revoked
    }
}
