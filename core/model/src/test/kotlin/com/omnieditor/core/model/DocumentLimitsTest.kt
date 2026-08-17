package com.omnieditor.core.model

import io.kotest.matchers.shouldBe
import org.junit.Test

class DocumentLimitsTest {

    @Test
    fun `editorTier FULL_MEMORY for files at or below 16 MiB`() {
        DocumentLimits.editorTier(0) shouldBe DocumentLimits.SizeTier.FULL_MEMORY
        DocumentLimits.editorTier(16L * 1024 * 1024) shouldBe DocumentLimits.SizeTier.FULL_MEMORY
    }

    @Test
    fun `editorTier INDEXED_READ_ONLY for files above 16 MiB up to 256 MiB`() {
        DocumentLimits.editorTier(16L * 1024 * 1024 + 1) shouldBe DocumentLimits.SizeTier.INDEXED_READ_ONLY
        DocumentLimits.editorTier(256L * 1024 * 1024) shouldBe DocumentLimits.SizeTier.INDEXED_READ_ONLY
    }

    @Test
    fun `editorTier REFUSED for files above 256 MiB`() {
        DocumentLimits.editorTier(256L * 1024 * 1024 + 1) shouldBe DocumentLimits.SizeTier.REFUSED
    }

    @Test
    fun `compareTier FULL_MEMORY for files at or below 16 MiB`() {
        DocumentLimits.compareTier(16L * 1024 * 1024) shouldBe DocumentLimits.SizeTier.FULL_MEMORY
    }

    @Test
    fun `compareTier INDEXED_READ_ONLY for files above 16 MiB up to 256 MiB`() {
        DocumentLimits.compareTier(64L * 1024 * 1024) shouldBe DocumentLimits.SizeTier.INDEXED_READ_ONLY
    }

    @Test
    fun `compareTier REFUSED for files above 256 MiB`() {
        DocumentLimits.compareTier(256L * 1024 * 1024 + 1) shouldBe DocumentLimits.SizeTier.REFUSED
    }

    @Test
    fun `editorTier with encoding - UTF-8 large file is INDEXED_EDITABLE`() {
        val size = 20L * 1024 * 1024 // 20 MiB
        DocumentLimits.editorTier(size, "UTF-8") shouldBe DocumentLimits.SizeTier.INDEXED_EDITABLE
    }

    @Test
    fun `editorTier with encoding - US-ASCII large file is INDEXED_EDITABLE`() {
        val size = 20L * 1024 * 1024
        DocumentLimits.editorTier(size, "US-ASCII") shouldBe DocumentLimits.SizeTier.INDEXED_EDITABLE
    }

    @Test
    fun `editorTier with encoding - case insensitive utf-8`() {
        val size = 20L * 1024 * 1024
        DocumentLimits.editorTier(size, "utf-8") shouldBe DocumentLimits.SizeTier.INDEXED_EDITABLE
    }

    @Test
    fun `editorTier with encoding - UTF-16 large file is INDEXED_READ_ONLY`() {
        val size = 20L * 1024 * 1024
        DocumentLimits.editorTier(size, "UTF-16") shouldBe DocumentLimits.SizeTier.INDEXED_READ_ONLY
    }

    @Test
    fun `editorTier with encoding - small file is FULL_MEMORY regardless of encoding`() {
        val size = 1L * 1024 * 1024 // 1 MiB
        DocumentLimits.editorTier(size, "UTF-16") shouldBe DocumentLimits.SizeTier.FULL_MEMORY
    }

    @Test
    fun `editorTier with encoding - huge file is REFUSED regardless of encoding`() {
        val size = 300L * 1024 * 1024 // 300 MiB
        DocumentLimits.editorTier(size, "UTF-8") shouldBe DocumentLimits.SizeTier.REFUSED
    }

    @Test
    fun `editorTier without encoding - large file is INDEXED_READ_ONLY`() {
        val size = 20L * 1024 * 1024
        DocumentLimits.editorTier(size) shouldBe DocumentLimits.SizeTier.INDEXED_READ_ONLY
    }
}
