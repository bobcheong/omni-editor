package com.omnieditor.core.io

import com.omnieditor.core.model.Fingerprint
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class MergeSafetyTest {

    private lateinit var testDir: File
    private lateinit var backupDir: File

    @Before
    fun setUp() {
        testDir = File(System.getProperty("java.io.tmpdir"), "omni-safety-test-${System.nanoTime()}")
        testDir.mkdirs()
        backupDir = File(testDir, "backups")
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private fun createTestFile(name: String, content: String): File {
        val file = File(testDir, name)
        file.writeText(content)
        return file
    }

    // ── Pre-write backup ──

    @Test
    fun `backup creates a copy before write`() = runTest {
        val source = createTestFile("original.txt", "original content")
        val backup = MergeSafety.createBackup(source, backupDir, "session-1")
        backup shouldNotBe null
        backup!!.exists() shouldBe true
        backup.readText() shouldBe "original content"
    }

    @Test
    fun `backup file has session and timestamp in name`() = runTest {
        val source = createTestFile("test.txt", "content")
        val backup = MergeSafety.createBackup(source, backupDir, "session-1")
        backup shouldNotBe null
        backup!!.name.startsWith("session-1_") shouldBe true
        backup.name.endsWith("_test.txt") shouldBe true
    }

    @Test
    fun `backup of nonexistent file returns null`() = runTest {
        val missing = File(testDir, "nonexistent.txt")
        MergeSafety.createBackup(missing, backupDir, "session-1") shouldBe null
    }

    @Test
    fun `backup exists before first byte is written`() = runTest {
        val source = createTestFile("data.txt", "important data")
        val backup = MergeSafety.createBackup(source, backupDir, "s1")
        backup shouldNotBe null

        // Now "write" to the source (simulate merge)
        source.writeText("modified data")

        // Backup still has the original
        backup!!.readText() shouldBe "important data"
    }

    // ── List backups ──

    @Test
    fun `list backups returns newest first`() = runTest {
        val source = createTestFile("file.txt", "v1")
        MergeSafety.createBackup(source, backupDir, "s1")
        Thread.sleep(10)
        source.writeText("v2")
        MergeSafety.createBackup(source, backupDir, "s1")

        val backups = MergeSafety.listBackups(backupDir, "s1")
        backups.size shouldBe 2
        (backups[0].timestamp >= backups[1].timestamp) shouldBe true
    }

    @Test
    fun `list backups filters by session`() = runTest {
        val f1 = createTestFile("a.txt", "a")
        val f2 = createTestFile("b.txt", "b")
        MergeSafety.createBackup(f1, backupDir, "session-A")
        MergeSafety.createBackup(f2, backupDir, "session-B")

        MergeSafety.listBackups(backupDir, "session-A").size shouldBe 1
        MergeSafety.listBackups(backupDir, "session-B").size shouldBe 1
    }

    @Test
    fun `list backups returns empty for nonexistent dir`() {
        MergeSafety.listBackups(File(testDir, "nope"), "s1") shouldBe emptyList()
    }

    // ── Restore ──

    @Test
    fun `restore backup overwrites current file`() = runTest {
        val source = createTestFile("file.txt", "original")
        val backup = MergeSafety.createBackup(source, backupDir, "s1")!!
        source.writeText("modified after merge")

        MergeSafety.restoreBackup(BackupInfo(backup, 0, "file.txt", backup.length()), source) shouldBe true
        source.readText() shouldBe "original"
    }

    // ── Cleanup ──

    @Test
    fun `clean removes expired backups`() = runTest {
        val source = createTestFile("file.txt", "data")
        val backup = MergeSafety.createBackup(source, backupDir, "s1")!!
        // Set modification time to 60 days ago
        backup.setLastModified(System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000)

        val deleted = MergeSafety.cleanBackups(backupDir, retentionDays = 30)
        deleted shouldBe 1
        backup.exists() shouldBe false
    }

    @Test
    fun `clean enforces size cap`() = runTest {
        // Create several backups
        for (i in 0 until 5) {
            val source = createTestFile("file$i.txt", "x".repeat(1000))
            MergeSafety.createBackup(source, backupDir, "s$i")
            Thread.sleep(10)
        }

        // Cap at 2KB — should delete some
        val deleted = MergeSafety.cleanBackups(backupDir, retentionDays = 365, maxStorageBytes = 2000)
        deleted shouldNotBe 0
    }

    // ── Fingerprint ──

    @Test
    fun `fingerprint captures size and modified time`() = runTest {
        val file = createTestFile("test.txt", "hello world")
        val fp = MergeSafety.fingerprint(file)
        fp shouldNotBe null
        fp!!.size shouldBe file.length()
        fp.modifiedAt shouldBe file.lastModified()
        fp.edgeHash.isNotEmpty() shouldBe true
    }

    @Test
    fun `fingerprint of nonexistent file returns null`() = runTest {
        MergeSafety.fingerprint(File(testDir, "missing")) shouldBe null
    }

    @Test
    fun `same file produces same fingerprint`() = runTest {
        val file = createTestFile("test.txt", "content")
        val fp1 = MergeSafety.fingerprint(file)!!
        val fp2 = MergeSafety.fingerprint(file)!!
        fp1 shouldBe fp2
    }

    @Test
    fun `modified file produces different fingerprint`() = runTest {
        val file = createTestFile("test.txt", "original")
        val fp1 = MergeSafety.fingerprint(file)!!
        Thread.sleep(10)
        file.writeText("modified")
        val fp2 = MergeSafety.fingerprint(file)!!
        (fp1 == fp2) shouldBe false
    }

    // ── External change detection ──

    @Test
    fun `unchanged file detected correctly`() {
        val fp = Fingerprint(100, 1700000000, "abc123")
        MergeSafety.detectExternalChange(fp, fp) shouldBe ExternalChangeResult.UNCHANGED
    }

    @Test
    fun `size change detected`() {
        val saved = Fingerprint(100, 1700000000, "abc123")
        val current = Fingerprint(200, 1700000000, "abc123")
        MergeSafety.detectExternalChange(saved, current) shouldBe ExternalChangeResult.CHANGED
    }

    @Test
    fun `modified time change detected`() {
        val saved = Fingerprint(100, 1700000000, "abc123")
        val current = Fingerprint(100, 1700001000, "abc123")
        MergeSafety.detectExternalChange(saved, current) shouldBe ExternalChangeResult.CHANGED
    }

    @Test
    fun `content change detected via edge hash`() {
        val saved = Fingerprint(100, 1700000000, "abc123")
        val current = Fingerprint(100, 1700000000, "xyz789")
        MergeSafety.detectExternalChange(saved, current) shouldBe ExternalChangeResult.CHANGED
    }

    @Test
    fun `file modified underneath produces CHANGED not silent overwrite`() = runTest {
        val file = createTestFile("config.yaml", "key: value")
        val fpAtOpen = MergeSafety.fingerprint(file)!!

        // Simulate external modification
        Thread.sleep(10)
        file.writeText("key: new_value")
        val fpOnResume = MergeSafety.fingerprint(file)!!

        MergeSafety.detectExternalChange(fpAtOpen, fpOnResume) shouldBe ExternalChangeResult.CHANGED
    }
}
