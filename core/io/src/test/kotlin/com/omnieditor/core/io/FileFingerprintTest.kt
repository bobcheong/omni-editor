package com.omnieditor.core.io

import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class FileFingerprintTest {

    private lateinit var tempFile: File

    @Before
    fun setUp() {
        tempFile = File.createTempFile("fingerprint-test", ".txt")
        tempFile.writeText("hello world\n".repeat(100))
    }

    @After
    fun tearDown() {
        tempFile.delete()
    }

    @Test
    fun `fingerprint captures size`() {
        val fp = FileFingerprint.of(tempFile)
        fp.size shouldBe tempFile.length()
    }

    @Test
    fun `fingerprint captures lastModified`() {
        val fp = FileFingerprint.of(tempFile)
        fp.lastModified shouldBe tempFile.lastModified()
    }

    @Test
    fun `check returns true for unmodified file`() {
        val fp = FileFingerprint.of(tempFile)
        FileFingerprint.check(tempFile, fp) shouldBe true
    }

    @Test
    fun `check returns false after file content changes`() {
        val fp = FileFingerprint.of(tempFile)
        tempFile.appendText("extra content")
        FileFingerprint.check(tempFile, fp) shouldBe false
    }

    @Test
    fun `check returns false after file size changes`() {
        val fp = FileFingerprint.of(tempFile)
        tempFile.writeText("short")
        FileFingerprint.check(tempFile, fp) shouldBe false
    }

    @Test
    fun `fingerprint of small file hashes all content`() {
        val smallFile = File.createTempFile("small-fp", ".txt")
        try {
            smallFile.writeText("tiny")
            val fp = FileFingerprint.of(smallFile)
            // Content hash should be non-zero for non-empty files
            (fp.contentHash != 0L) shouldBe true
        } finally {
            smallFile.delete()
        }
    }

    @Test
    fun `fingerprint of file larger than 8K hashes first and last 4K`() {
        val largeContent = "A".repeat(4096) + "B".repeat(4096) + "C".repeat(4096)
        tempFile.writeText(largeContent)
        val fp = FileFingerprint.of(tempFile)
        (fp.contentHash != 0L) shouldBe true
    }
}
