package com.omnieditor.core.io

import com.omnieditor.core.model.OmniError
import com.omnieditor.core.model.OmniException
import com.omnieditor.core.model.SourceKind
import com.omnieditor.core.model.SourceRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels

/**
 * Contract tests for SourceProvider (T-05).
 *
 * These verify the behavioural contract that both DirectSourceProvider and
 * SafSourceProvider must satisfy. Run against FakeSourceProvider here (Tier 1),
 * and against real implementations in instrumented tests (Tier 3).
 */
class SourceProviderContractTest {

    private lateinit var provider: FakeSourceProvider

    private val testRef = SourceRef(
        id = "test-file",
        kind = SourceKind.LOCAL,
        path = "/fake/test-file",
        label = "test-file.txt",
    )

    @Before
    fun setUp() {
        provider = FakeSourceProvider()
    }

    // ── Resolve ──

    @Test
    fun `resolve returns metadata for accessible file`() = runTest {
        provider.addFile("test-file", "hello world")
        val resolved = provider.resolve(testRef)
        resolved.label shouldBe "test-file.txt"
        resolved.size shouldBe 11L
        resolved.readable shouldBe true
        resolved.mimeType shouldNotBe null
    }

    @Test
    fun `resolve throws AccessRevoked for revoked file`() = runTest {
        provider.addFile("test-file", "content")
        provider.revokeAccess("test-file")
        val ex = shouldThrow<OmniException> { provider.resolve(testRef) }
        (ex.error is OmniError.AccessRevoked) shouldBe true
    }

    @Test
    fun `resolve throws AccessRevoked for missing file`() = runTest {
        val ex = shouldThrow<OmniException> { provider.resolve(testRef) }
        (ex.error is OmniError.AccessRevoked) shouldBe true
    }

    // ── Open and read ──

    @Test
    fun `open returns readable channel with correct content`() = runTest {
        val content = "hello world\nline two\n"
        provider.addFile("test-file", content)
        val channel = provider.open(testRef)
        val buf = ByteBuffer.allocate(1024)
        val bytesRead = channel.read(buf)
        buf.flip()
        val read = ByteArray(bytesRead)
        buf.get(read)
        String(read) shouldBe content
        channel.close()
    }

    @Test
    fun `open throws AccessRevoked for revoked file`() = runTest {
        provider.addFile("test-file", "content")
        provider.revokeAccess("test-file")
        val ex = shouldThrow<OmniException> { provider.open(testRef) }
        (ex.error is OmniError.AccessRevoked) shouldBe true
    }

    // ── Write ──

    @Test
    fun `write stores content and is readable back`() = runTest {
        provider.addFile("test-file", "original")
        val newContent = "updated content"
        val channel = Channels.newChannel(ByteArrayInputStream(newContent.toByteArray()))
        provider.write(testRef, channel)
        // Read back
        val readChannel = provider.open(testRef)
        val buf = ByteBuffer.allocate(1024)
        readChannel.read(buf)
        buf.flip()
        val read = ByteArray(buf.remaining())
        buf.get(read)
        String(read) shouldBe newContent
    }

    @Test
    fun `write throws AccessRevoked for revoked file`() = runTest {
        provider.addFile("test-file", "original")
        provider.revokeAccess("test-file")
        val channel = Channels.newChannel(ByteArrayInputStream("new".toByteArray()))
        val ex = shouldThrow<OmniException> { provider.write(testRef, channel) }
        (ex.error is OmniError.AccessRevoked) shouldBe true
    }

    // ── Accessibility ──

    @Test
    fun `isAccessible returns true for accessible file`() = runTest {
        provider.addFile("test-file", "content")
        provider.isAccessible(testRef) shouldBe true
    }

    @Test
    fun `isAccessible returns false for revoked file`() = runTest {
        provider.addFile("test-file", "content")
        provider.revokeAccess("test-file")
        provider.isAccessible(testRef) shouldBe false
    }

    @Test
    fun `isAccessible returns false for missing file`() = runTest {
        provider.isAccessible(testRef) shouldBe false
    }

    @Test
    fun `restored access makes file accessible again`() = runTest {
        provider.addFile("test-file", "content")
        provider.revokeAccess("test-file")
        provider.isAccessible(testRef) shouldBe false
        provider.restoreAccess("test-file")
        provider.isAccessible(testRef) shouldBe true
    }

    // ── Capabilities ──

    @Test
    fun `capabilities reports available operations`() {
        val caps = provider.capabilities(testRef)
        caps.canRead shouldBe true
        caps.canWrite shouldBe true
    }

    // ── Session reference survival ──

    @Test
    fun `source ref with path survives serialization round-trip`() {
        val ref = SourceRef(
            id = "s1",
            kind = SourceKind.LOCAL,
            path = "/storage/emulated/0/documents/config.yaml",
            label = "config.yaml",
        )
        val json = kotlinx.serialization.json.Json.encodeToString(SourceRef.serializer(), ref)
        val restored = kotlinx.serialization.json.Json.decodeFromString(SourceRef.serializer(), json)
        restored shouldBe ref
        restored.path shouldBe "/storage/emulated/0/documents/config.yaml"
    }

    @Test
    fun `source ref with URI grant survives serialization round-trip`() {
        val ref = SourceRef(
            id = "s2",
            kind = SourceKind.LOCAL,
            uriGrant = "content://com.android.externalstorage.documents/document/primary%3Aconfig.yaml",
            label = "config.yaml",
        )
        val json = kotlinx.serialization.json.Json.encodeToString(SourceRef.serializer(), ref)
        val restored = kotlinx.serialization.json.Json.decodeFromString(SourceRef.serializer(), json)
        restored shouldBe ref
        restored.uriGrant shouldBe "content://com.android.externalstorage.documents/document/primary%3Aconfig.yaml"
    }

    @Test
    fun `snippet source ref needs no path or grant`() {
        val ref = SourceRef(
            id = "snip1",
            kind = SourceKind.SNIPPET,
            label = "Pasted text",
        )
        ref.kind shouldBe SourceKind.SNIPPET
    }
}
