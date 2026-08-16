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
}
