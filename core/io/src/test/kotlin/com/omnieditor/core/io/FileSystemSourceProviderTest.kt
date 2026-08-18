package com.omnieditor.core.io

import com.omnieditor.core.model.OmniError
import com.omnieditor.core.model.OmniException
import com.omnieditor.core.model.SourceKind
import com.omnieditor.core.model.SourceRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.Channels

/**
 * Unit tests for [FileSystemSourceProvider] (Move 1 extraction, ADR-017).
 *
 * Tier 1 (JVM-only). Verifies resolve, open, write (atomic + non-atomic),
 * list (flat + recursive), capabilities, and isAccessible.
 */
class FileSystemSourceProviderTest {

    private lateinit var testDir: File
    private lateinit var provider: FileSystemSourceProvider

    @Before
    fun setUp() {
        testDir = File(System.getProperty("java.io.tmpdir"), "omni-fs-test-${System.nanoTime()}")
        testDir.mkdirs()
        provider = FileSystemSourceProvider(rootDir = null)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun fileRef(file: File) = SourceRef(
        id = file.name,
        kind = SourceKind.LOCAL,
        path = file.absolutePath,
        label = file.name,
    )

    private fun createFile(name: String, content: String): File {
        val file = File(testDir, name)
        file.writeText(content)
        return file
    }

    // ── resolve ────────────────────────────────────────────────────────────────

    @Test
    fun `resolve returns correct metadata for a readable file`() = runTest {
        val file = createFile("hello.txt", "hello world")
        val ref = fileRef(file)

        val resolved = provider.resolve(ref)

        resolved.label shouldBe "hello.txt"
        resolved.size shouldBe file.length()
        resolved.lastModified shouldBe file.lastModified()
        resolved.mimeType shouldBe "text/plain"
        resolved.readable shouldBe true
        resolved.writable shouldBe true
    }

    @Test
    fun `resolve throws AccessRevoked for missing file`() = runTest {
        val ref = SourceRef(
            id = "missing",
            kind = SourceKind.LOCAL,
            path = File(testDir, "does-not-exist.txt").absolutePath,
            label = "does-not-exist.txt",
        )
        val ex = shouldThrow<OmniException> { provider.resolve(ref) }
        (ex.error is OmniError.AccessRevoked) shouldBe true
    }

    @Test
    fun `resolve throws AccessRevoked when path is null (snippet kind)`() = runTest {
        // SourceRef requires a path, uriGrant, or SNIPPET kind.
        // Use SNIPPET to construct a ref with no path, simulating a case where
        // FileSystemSourceProvider receives a non-filesystem reference.
        val ref = SourceRef(id = "nill", kind = SourceKind.SNIPPET, label = "x")
        val ex = shouldThrow<OmniException> { provider.resolve(ref) }
        (ex.error is OmniError.AccessRevoked) shouldBe true
    }

    // ── open ───────────────────────────────────────────────────────────────────

    @Test
    fun `open returns channel with correct content`() = runTest {
        val content = "line one\nline two\n"
        val file = createFile("read-me.txt", content)
        val channel = provider.open(fileRef(file))
        val buf = ByteBuffer.allocate(256)
        val read = channel.read(buf)
        buf.flip()
        val bytes = ByteArray(read)
        buf.get(bytes)
        channel.close()
        String(bytes) shouldBe content
    }

    @Test
    fun `open throws AccessRevoked for missing file`() = runTest {
        val ref = SourceRef(
            id = "gone",
            kind = SourceKind.LOCAL,
            path = File(testDir, "gone.txt").absolutePath,
            label = "gone.txt",
        )
        val ex = shouldThrow<OmniException> { provider.open(ref) }
        (ex.error is OmniError.AccessRevoked) shouldBe true
    }

    // ── write (atomic) ─────────────────────────────────────────────────────────

    @Test
    fun `write atomic replaces file content`() = runTest {
        val file = createFile("target.txt", "original")
        val newContent = "updated content"
        provider.write(
            ref = fileRef(file),
            from = Channels.newChannel(ByteArrayInputStream(newContent.toByteArray())),
            atomic = true,
        )
        file.readText() shouldBe newContent
    }

    @Test
    fun `write atomic leaves no temp file on success`() = runTest {
        val file = createFile("clean.txt", "before")
        provider.write(
            ref = fileRef(file),
            from = Channels.newChannel(ByteArrayInputStream("after".toByteArray())),
            atomic = true,
        )
        val tmp = File(testDir, ".omni-tmp-clean.txt")
        tmp.exists() shouldBe false
    }

    @Test
    fun `write non-atomic replaces file content`() = runTest {
        val file = createFile("direct.txt", "old")
        provider.write(
            ref = fileRef(file),
            from = Channels.newChannel(ByteArrayInputStream("new".toByteArray())),
            atomic = false,
        )
        file.readText() shouldBe "new"
    }

    // ── list ───────────────────────────────────────────────────────────────────

    @Test
    fun `list returns direct children of a directory`() = runTest {
        val dir = File(testDir, "mydir").also { it.mkdir() }
        createFile("mydir/a.txt", "a")
        createFile("mydir/b.txt", "b")
        val ref = SourceRef(id = "dir", kind = SourceKind.LOCAL, path = dir.absolutePath, label = "mydir")

        val entries = provider.list(ref, recursive = false).toList()

        entries.size shouldBe 2
        entries.map { it.name }.toSet() shouldBe setOf("a.txt", "b.txt")
        entries.all { !it.isDirectory } shouldBe true
    }

    @Test
    fun `list recursive returns all descendants`() = runTest {
        val dir = File(testDir, "tree").also { it.mkdir() }
        val sub = File(dir, "sub").also { it.mkdir() }
        File(dir, "root.txt").writeText("root")
        File(sub, "child.txt").writeText("child")
        val ref = SourceRef(id = "tree", kind = SourceKind.LOCAL, path = dir.absolutePath, label = "tree")

        val entries = provider.list(ref, recursive = true).toList()
        val names = entries.map { it.name }.toSet()

        names.contains("root.txt") shouldBe true
        names.contains("child.txt") shouldBe true
    }

    @Test
    fun `list on non-directory emits nothing`() = runTest {
        val file = createFile("file.txt", "not a dir")
        val entries = provider.list(fileRef(file), recursive = false).toList()
        entries shouldBe emptyList()
    }

    // ── capabilities ───────────────────────────────────────────────────────────

    @Test
    fun `capabilities shows canRead, canWrite, canList, canAtomicRename for LOCAL`() {
        val ref = SourceRef(id = "x", kind = SourceKind.LOCAL, path = "/any", label = "x")
        val caps = provider.capabilities(ref)
        caps.canRead shouldBe true
        caps.canWrite shouldBe true
        caps.canList shouldBe true
        caps.canAtomicRename shouldBe true
        caps.supportsResume shouldBe false
    }

    @Test
    fun `capabilities shows canWrite false for URL kind`() {
        val ref = SourceRef(id = "u", kind = SourceKind.URL, path = "https://example.com", label = "url")
        val caps = provider.capabilities(ref)
        caps.canWrite shouldBe false
    }

    // ── isAccessible ───────────────────────────────────────────────────────────

    @Test
    fun `isAccessible returns true for existing readable file`() = runTest {
        val file = createFile("exists.txt", "yes")
        provider.isAccessible(fileRef(file)) shouldBe true
    }

    @Test
    fun `isAccessible returns false for missing file`() = runTest {
        val ref = SourceRef(
            id = "missing",
            kind = SourceKind.LOCAL,
            path = File(testDir, "missing.txt").absolutePath,
            label = "missing.txt",
        )
        provider.isAccessible(ref) shouldBe false
    }

    @Test
    fun `isAccessible returns false when path is null (snippet kind)`() = runTest {
        // SNIPPET is the only kind that allows path=null — isAccessible must return false
        // rather than crash when given a path-less ref.
        val ref = SourceRef(id = "null-path", kind = SourceKind.SNIPPET, label = "x")
        provider.isAccessible(ref) shouldBe false
    }

    // ── guessMimeType (companion) ───────────────────────────────────────────────

    @Test
    fun `guessMimeType returns correct MIME for known extensions`() {
        FileSystemSourceProvider.guessMimeType("file.txt") shouldBe "text/plain"
        FileSystemSourceProvider.guessMimeType("Main.kt") shouldBe "text/x-kotlin"
        FileSystemSourceProvider.guessMimeType("script.py") shouldBe "text/x-python"
        FileSystemSourceProvider.guessMimeType("data.json") shouldBe "application/json"
        FileSystemSourceProvider.guessMimeType("page.html") shouldBe "text/html"
        FileSystemSourceProvider.guessMimeType("style.css") shouldBe "text/css"
    }

    @Test
    fun `guessMimeType returns null for unknown extension`() {
        FileSystemSourceProvider.guessMimeType("archive.xyz") shouldBe null
        FileSystemSourceProvider.guessMimeType("noextension") shouldBe null
    }
}
