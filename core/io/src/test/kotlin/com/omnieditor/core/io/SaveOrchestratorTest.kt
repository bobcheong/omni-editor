package com.omnieditor.core.io

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.channels.WritableByteChannel

/**
 * Unit tests for [SaveOrchestrator] (Move 2a extraction, ADR-017).
 *
 * Tier 1 (JVM-only). Verifies:
 *  - Successful save creates a backup and writes the file.
 *  - Saving to a new (non-existent) path skips backup and still writes.
 *  - Backup failure aborts the save without touching the target.
 *  - Write failure preserves both the original and the backup.
 */
class SaveOrchestratorTest {

    private lateinit var testDir: File
    private lateinit var backupDir: File

    @Before
    fun setUp() {
        testDir = File(System.getProperty("java.io.tmpdir"), "omni-save-test-${System.nanoTime()}")
        testDir.mkdirs()
        backupDir = File(testDir, "backups")
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Minimal [TextDocument] stub that materialises [content] as UTF-8 bytes.
     */
    private fun docWith(content: String): TextDocument = object : TextDocument {
        override val lineCount: Long get() = content.lines().size.toLong()
        override val index: LineIndex get() = throw UnsupportedOperationException()
        override fun line(index: Long): CharSequence = content.lines()[index.toInt()]
        override fun edit(range: LongRange, replacement: CharSequence): Long = 0
        override fun replaceAll(offset: Int, length: Int, replacement: String): Long = 0
        override val length: Int get() = content.length
        override fun text(): String = content
        override fun undo(): Long? = null
        override fun redo(): Long? = null
        override fun beginBatch() {}
        override fun commitBatch() {}
        override suspend fun materialise(into: WritableByteChannel) {
            val bytes = content.toByteArray(Charsets.UTF_8)
            val buf = java.nio.ByteBuffer.wrap(bytes)
            while (buf.hasRemaining()) into.write(buf)
        }
        override val changes: Flow<DocumentChange> get() = emptyFlow()
        override val dirty: Boolean get() = true
        override val editGeneration: Long get() = 1L
    }

    // ── Happy path ─────────────────────────────────────────────────────────────

    @Test
    fun `saveWithBackup writes document content to target file`() = runTest {
        val target = File(testDir, "output.txt").also { it.writeText("original") }
        val doc = docWith("saved content")

        val result = SaveOrchestrator.saveWithBackup(doc, target, backupDir, "session-1")

        result.success shouldBe true
        result.error shouldBe null
        target.readText() shouldBe "saved content"
    }

    @Test
    fun `saveWithBackup creates a pre-write backup for existing file`() = runTest {
        val target = File(testDir, "important.txt").also { it.writeText("original data") }
        val doc = docWith("new data")

        val result = SaveOrchestrator.saveWithBackup(doc, target, backupDir, "sess-42")

        result.success shouldBe true
        result.backupPath shouldNotBe null
        result.backupPath!!.exists() shouldBe true
        result.backupPath.readText() shouldBe "original data"
    }

    @Test
    fun `saveWithBackup backup name contains session id`() = runTest {
        val target = File(testDir, "data.txt").also { it.writeText("content") }
        val doc = docWith("updated")

        val result = SaveOrchestrator.saveWithBackup(doc, target, backupDir, "mysession")

        result.backupPath shouldNotBe null
        result.backupPath!!.name.startsWith("mysession_") shouldBe true
    }

    @Test
    fun `saveWithBackup to new file skips backup and still writes`() = runTest {
        val target = File(testDir, "new-file.txt") // does not exist
        val doc = docWith("brand new")

        val result = SaveOrchestrator.saveWithBackup(doc, target, backupDir, "s1")

        result.success shouldBe true
        result.backupPath shouldBe null    // no source to back up
        target.readText() shouldBe "brand new"
    }

    @Test
    fun `saveWithBackup leaves no temp file after successful save`() = runTest {
        val target = File(testDir, "clean.txt").also { it.writeText("before") }
        val doc = docWith("after")

        SaveOrchestrator.saveWithBackup(doc, target, backupDir, "s1")

        val tmp = File(testDir, ".omni-save-clean.txt.tmp")
        tmp.exists() shouldBe false
    }

    // ── Backup and backup directory creation ────────────────────────────────────

    @Test
    fun `saveWithBackup creates backup directory if absent`() = runTest {
        val nestedBackupDir = File(testDir, "deep/backups")
        // nestedBackupDir does NOT exist yet
        val target = File(testDir, "file.txt").also { it.writeText("x") }
        val doc = docWith("y")

        val result = SaveOrchestrator.saveWithBackup(doc, target, nestedBackupDir, "s1")

        result.success shouldBe true
        nestedBackupDir.exists() shouldBe true
    }

    // ── Write failure preserves backup ─────────────────────────────────────────

    @Test
    fun `saveWithBackup on write failure preserves original file content`() = runTest {
        val target = File(testDir, "precious.txt").also { it.writeText("precious original") }
        // A document whose materialise() always throws.
        val badDoc = object : TextDocument by docWith("x") {
            override suspend fun materialise(into: WritableByteChannel) {
                throw RuntimeException("Simulated disk full")
            }
        }

        val result = SaveOrchestrator.saveWithBackup(badDoc, target, backupDir, "s1")

        result.success shouldBe false
        result.error shouldNotBe null
        // The backup was created before write was attempted.
        result.backupPath shouldNotBe null
        result.backupPath!!.exists() shouldBe true
        // Original file must be untouched (atomic rename never happened).
        target.readText() shouldBe "precious original"
    }
}
