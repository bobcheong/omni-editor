package com.omnieditor.app

import com.omnieditor.core.io.Capabilities
import com.omnieditor.core.io.Entry
import com.omnieditor.core.io.Resolved
import com.omnieditor.core.io.SourceProvider
import com.omnieditor.core.model.OmniError
import com.omnieditor.core.model.OmniException
import com.omnieditor.core.model.SourceKind
import com.omnieditor.core.model.SourceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.ReadableByteChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DIST-2: the direct-install flavour works with real filesystem paths.
 * Requires MANAGE_EXTERNAL_STORAGE permission, requested at first file access
 * behind a rationale screen (T-05).
 *
 * Sessions store paths — they are durable because the permission is global.
 * This is why the direct flavour exists: SAF paths are not durable in the
 * same way, and folder compare needs stable references.
 */
@Singleton
class DirectSourceProvider @Inject constructor() : SourceProvider {

    override suspend fun resolve(ref: SourceRef): Resolved = withContext(Dispatchers.IO) {
        val file = resolveFile(ref)
        Resolved(
            label = file.name,
            size = file.length(),
            lastModified = file.lastModified(),
            mimeType = guessMimeType(file.name),
            readable = file.canRead(),
            writable = file.canWrite(),
        )
    }

    override suspend fun open(ref: SourceRef): ReadableByteChannel = withContext(Dispatchers.IO) {
        val file = resolveFile(ref)
        FileInputStream(file).channel
    }

    override suspend fun write(
        ref: SourceRef,
        from: ReadableByteChannel,
        atomic: Boolean,
    ) = withContext(Dispatchers.IO) {
        val file = resolveFile(ref)
        if (atomic) {
            val tmp = File(file.parent, ".omni-tmp-${file.name}")
            try {
                FileOutputStream(tmp).channel.use { out ->
                    val buf = java.nio.ByteBuffer.allocate(8192)
                    while (from.read(buf) != -1) {
                        buf.flip()
                        out.write(buf)
                        buf.clear()
                    }
                    out.force(true) // R-57: fsync before rename
                }
                if (!tmp.renameTo(file)) {
                    tmp.delete() // R-57: clean up on rename failure
                    throw OmniException(OmniError.WriteFailed(ref, partial = false))
                }
            } catch (e: OmniException) {
                tmp.delete() // ensure cleanup on all failure paths
                throw e
            } catch (e: Exception) {
                tmp.delete()
                throw OmniException(OmniError.WriteFailed(ref, partial = false))
            }
        } else {
            FileOutputStream(file).channel.use { out ->
                val buf = java.nio.ByteBuffer.allocate(8192)
                while (from.read(buf) != -1) {
                    buf.flip()
                    out.write(buf)
                    buf.clear()
                }
            }
        }
    }

    override suspend fun list(ref: SourceRef, recursive: Boolean): Flow<Entry> = flow {
        val file = resolveFile(ref)
        if (!file.isDirectory) return@flow
        val walker = if (recursive) file.walkTopDown() else file.listFiles()?.asSequence() ?: emptySequence()
        for (child in walker) {
            if (child == file) continue
            emit(
                Entry(
                    name = child.name,
                    path = child.absolutePath,
                    size = if (child.isFile) child.length() else 0,
                    lastModified = child.lastModified(),
                    isDirectory = child.isDirectory,
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    override fun capabilities(ref: SourceRef): Capabilities {
        return Capabilities(
            canRead = true,
            canWrite = ref.kind != SourceKind.URL,
            canList = true,
            canSetModifiedTime = true,
            canAtomicRename = true,
            supportsResume = false,
        )
    }

    override suspend fun isAccessible(ref: SourceRef): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(ref.path ?: return@withContext false)
            file.exists() && file.canRead()
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveFile(ref: SourceRef): File {
        val path = ref.path ?: throw OmniException(OmniError.AccessRevoked(ref))
        val file = File(path)
        if (!file.exists() || !file.canRead()) {
            throw OmniException(OmniError.AccessRevoked(ref))
        }
        return file
    }

    private fun guessMimeType(name: String): String? {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "txt" -> "text/plain"
            "kt", "kts" -> "text/x-kotlin"
            "java" -> "text/x-java"
            "py" -> "text/x-python"
            "js" -> "text/javascript"
            "ts" -> "text/typescript"
            "json" -> "application/json"
            "xml" -> "text/xml"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "md" -> "text/markdown"
            "yaml", "yml" -> "text/yaml"
            "csv" -> "text/csv"
            "sh" -> "text/x-shellscript"
            "sql" -> "text/x-sql"
            else -> null
        }
    }
}
