package com.omnieditor.feature.editor

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Unit tests for [WrappedRowCache] — visual-row navigation logic (R-18b).
 *
 * The cache is populated by EditorContent from TextLayoutResult; here we feed
 * it directly to test the navigation functions in isolation on the JVM.
 */
class WrappedRowCacheTest {

    // ── Helper ───────────────────────────────────────────────────────────

    /** Build a cache with a single line that wraps at [breakPoints]. */
    private fun cacheWith(lineIndex: Long = 0L, vararg breakPoints: Int): WrappedRowCache {
        val cache = WrappedRowCache()
        val starts = IntArray(breakPoints.size + 1)
        starts[0] = 0
        breakPoints.copyInto(starts, destinationOffset = 1)
        cache.put(lineIndex, starts)
        return cache
    }

    // ── visualRowOf ──────────────────────────────────────────────────────

    @Test
    fun `visualRowOf returns 0 for line with no entry`() {
        val cache = WrappedRowCache()
        cache.visualRowOf(0L, 50) shouldBe 0
    }

    @Test
    fun `visualRowOf returns 0 for column before first break`() {
        // Breaks at 40 and 80 → rows start at [0, 40, 80]
        val cache = cacheWith(0L, 40, 80)
        cache.visualRowOf(0L, 0) shouldBe 0
        cache.visualRowOf(0L, 39) shouldBe 0
    }

    @Test
    fun `visualRowOf returns 1 for column in second visual row`() {
        val cache = cacheWith(0L, 40, 80)
        cache.visualRowOf(0L, 40) shouldBe 1
        cache.visualRowOf(0L, 79) shouldBe 1
    }

    @Test
    fun `visualRowOf returns 2 for column in third visual row`() {
        val cache = cacheWith(0L, 40, 80)
        cache.visualRowOf(0L, 80) shouldBe 2
        cache.visualRowOf(0L, 120) shouldBe 2
    }

    // ── visualRowCount ───────────────────────────────────────────────────

    @Test
    fun `visualRowCount returns 1 for unknown line`() {
        WrappedRowCache().visualRowCount(0L) shouldBe 1
    }

    @Test
    fun `visualRowCount returns correct count for wrapped line`() {
        cacheWith(0L, 40, 80).visualRowCount(0L) shouldBe 3
    }

    // ── rowStart and rowEnd ──────────────────────────────────────────────

    @Test
    fun `rowStart returns 0 for first row`() {
        cacheWith(0L, 40, 80).rowStart(0L, 0) shouldBe 0
    }

    @Test
    fun `rowStart returns break point for second row`() {
        cacheWith(0L, 40, 80).rowStart(0L, 1) shouldBe 40
    }

    @Test
    fun `rowEnd for middle row returns next row start`() {
        cacheWith(0L, 40, 80).rowEnd(0L, 0, lineLength = 100) shouldBe 40
    }

    @Test
    fun `rowEnd for last row returns lineLength`() {
        cacheWith(0L, 40, 80).rowEnd(0L, 2, lineLength = 100) shouldBe 100
    }

    @Test
    fun `rowEnd for unknown line returns lineLength`() {
        WrappedRowCache().rowEnd(0L, 0, lineLength = 50) shouldBe 50
    }

    // ── moveUpVisualRow ──────────────────────────────────────────────────

    @Test
    fun `moveUpVisualRow returns null when on first visual row`() {
        val cache = cacheWith(0L, 40, 80)
        cache.moveUpVisualRow(0L, column = 10, lineLength = 100) shouldBe null
    }

    @Test
    fun `moveUpVisualRow moves to same column offset in previous visual row`() {
        // Rows: [0..39], [40..79], [80..99]
        // Caret at col 45 (row 1, offset 5 within row) → should land at col 5 (row 0)
        val cache = cacheWith(0L, 40, 80)
        cache.moveUpVisualRow(0L, column = 45, lineLength = 100) shouldBe 5
    }

    @Test
    fun `moveUpVisualRow clamps to previous row end when offset would overshoot`() {
        // Rows: [0..29], [30..99]  (first row only 30 chars, second row 70 chars)
        val cache = cacheWith(0L, 30)
        // Caret at col 60 (row 1, offset 30 within row) → row 0 only has 30 chars (0..29)
        // target = 0 + 30 = 30, but rowEnd(row 0) = 30 → clamp to 30
        cache.moveUpVisualRow(0L, column = 60, lineLength = 100) shouldBe 30
    }

    @Test
    fun `moveUpVisualRow moves from third to second visual row`() {
        // Rows: [0..39], [40..79], [80..99]
        // Caret at col 85 (row 2, offset 5) → should land at col 45 (row 1)
        val cache = cacheWith(0L, 40, 80)
        cache.moveUpVisualRow(0L, column = 85, lineLength = 100) shouldBe 45
    }

    // ── moveDownVisualRow ────────────────────────────────────────────────

    @Test
    fun `moveDownVisualRow returns null when on last visual row`() {
        val cache = cacheWith(0L, 40, 80)
        cache.moveDownVisualRow(0L, column = 85, lineLength = 100) shouldBe null
    }

    @Test
    fun `moveDownVisualRow moves to same column offset in next visual row`() {
        // Rows: [0..39], [40..79], [80..99]
        // Caret at col 5 (row 0, offset 5) → should land at col 45 (row 1)
        val cache = cacheWith(0L, 40, 80)
        cache.moveDownVisualRow(0L, column = 5, lineLength = 100) shouldBe 45
    }

    @Test
    fun `moveDownVisualRow clamps to next row end when offset would overshoot`() {
        // Rows: [0..79], [80..89]  (second row only 10 chars)
        val cache = cacheWith(0L, 80)
        // Caret at col 75 (row 0, offset 75) → row 1 only has 10 chars (80..89)
        // target = 80 + 75 = 155, clamp to rowEnd = 90
        cache.moveDownVisualRow(0L, column = 75, lineLength = 90) shouldBe 90
    }

    @Test
    fun `moveDownVisualRow returns null for single-visual-row line`() {
        val cache = WrappedRowCache()
        cache.put(0L, intArrayOf(0))  // one visual row
        cache.moveDownVisualRow(0L, column = 10, lineLength = 50) shouldBe null
    }

    // ── invalidate and clear ─────────────────────────────────────────────

    @Test
    fun `invalidate removes entry for specific line`() {
        val cache = cacheWith(0L, 40, 80)
        cache.visualRowCount(0L) shouldBe 3
        cache.invalidate(0L)
        cache.visualRowCount(0L) shouldBe 1   // falls back to default
    }

    @Test
    fun `clear removes all entries`() {
        val cache = WrappedRowCache()
        cache.put(0L, intArrayOf(0, 40, 80))
        cache.put(1L, intArrayOf(0, 50))
        cache.clear()
        cache.visualRowCount(0L) shouldBe 1
        cache.visualRowCount(1L) shouldBe 1
    }

    @Test
    fun `invalidate for unknown line is a no-op`() {
        val cache = WrappedRowCache()
        cache.invalidate(99L)   // should not throw
        cache.visualRowCount(99L) shouldBe 1
    }

    // ── Multiple logical lines ───────────────────────────────────────────

    @Test
    fun `cache tracks multiple lines independently`() {
        val cache = WrappedRowCache()
        cache.put(0L, intArrayOf(0, 40))
        cache.put(1L, intArrayOf(0, 30, 60))
        cache.visualRowCount(0L) shouldBe 2
        cache.visualRowCount(1L) shouldBe 3
        cache.visualRowOf(0L, 45) shouldBe 1
        cache.visualRowOf(1L, 35) shouldBe 1
    }
}
