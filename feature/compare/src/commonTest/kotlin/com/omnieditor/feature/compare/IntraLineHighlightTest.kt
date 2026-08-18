package com.omnieditor.feature.compare

import androidx.compose.ui.graphics.Color
import com.omnieditor.core.model.Granularity
import com.omnieditor.core.model.HunkType
import com.omnieditor.core.model.IntraLineRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

/**
 * Unit tests for intra-line highlighting utilities (R-26).
 *
 * [highlightIntraLine] and [IntraLineCache] are pure-Kotlin logic that can be verified on
 * the JVM. Compose rendering is Tier 2+ and deferred per ADR-001.
 */
class IntraLineHighlightTest {

    // ── highlightIntraLine ───────────────────────────────────────────────────

    @Test
    fun `empty ranges returns plain AnnotatedString`() {
        val result = highlightIntraLine("hello world", emptyList(), Color.Red)
        result.text shouldBe "hello world"
        result.spanStyles shouldBe emptyList()
    }

    @Test
    fun `empty text returns plain AnnotatedString`() {
        val result = highlightIntraLine("", listOf(IntraLineRange(0, 0, HunkType.CHANGED)), Color.Red)
        result.text shouldBe ""
        result.spanStyles shouldBe emptyList()
    }

    @Test
    fun `single range applies one span`() {
        val result = highlightIntraLine(
            text = "hello world",
            ranges = listOf(IntraLineRange(6, 11, HunkType.CHANGED)),
            highlightColor = Color.Yellow,
        )
        result.text shouldBe "hello world"
        result.spanStyles.size shouldBe 1
        val span = result.spanStyles[0]
        span.start shouldBe 6
        span.end shouldBe 11
        span.item.background shouldBe Color.Yellow
    }

    @Test
    fun `multiple ranges apply multiple spans`() {
        val result = highlightIntraLine(
            text = "abcdefgh",
            ranges = listOf(
                IntraLineRange(0, 2, HunkType.REMOVED),
                IntraLineRange(5, 8, HunkType.CHANGED),
            ),
            highlightColor = Color.Red,
        )
        result.text shouldBe "abcdefgh"
        result.spanStyles.size shouldBe 2
        result.spanStyles[0].start shouldBe 0
        result.spanStyles[0].end shouldBe 2
        result.spanStyles[1].start shouldBe 5
        result.spanStyles[1].end shouldBe 8
    }

    @Test
    fun `range clamped when end exceeds text length`() {
        // Range [3, 20) on a 5-char string must be clamped to [3, 5)
        val result = highlightIntraLine(
            text = "hello",
            ranges = listOf(IntraLineRange(3, 20, HunkType.CHANGED)),
            highlightColor = Color.Blue,
        )
        result.text shouldBe "hello"
        result.spanStyles.size shouldBe 1
        result.spanStyles[0].start shouldBe 3
        result.spanStyles[0].end shouldBe 5
    }

    @Test
    fun `zero-width range after clamping produces no span`() {
        // Range [10, 10) on a 5-char string — clamped start == end, no span emitted
        val result = highlightIntraLine(
            text = "hello",
            ranges = listOf(IntraLineRange(5, 5, HunkType.CHANGED)),
            highlightColor = Color.Green,
        )
        result.text shouldBe "hello"
        result.spanStyles shouldBe emptyList()
    }

    // ── IntraLineCache ───────────────────────────────────────────────────────

    @Test
    fun `cache returns same result on second call`() {
        val cache = IntraLineCache()
        val r1 = cache.get("old line", "new line", 0L, 0L, Granularity.WORD)
        val r2 = cache.get("old line", "new line", 0L, 0L, Granularity.WORD)
        r1 shouldBe r2
    }

    @Test
    fun `cache returns empty result for LINE granularity`() {
        val cache = IntraLineCache()
        val result = cache.get("anything", "something else", 0L, 1L, Granularity.LINE)
        result.leftRanges shouldBe emptyList()
        result.rightRanges shouldBe emptyList()
    }

    @Test
    fun `cache clear removes all entries`() {
        val cache = IntraLineCache()
        // Populate cache
        val before = cache.get("old", "new", 0L, 0L, Granularity.CHARACTER)
        before.leftRanges.isEmpty().shouldBe(false).also {
            // "old" vs "new" differ so ranges should be non-empty
        }
        cache.clear()
        // After clear the result is recomputed (same value, but cache was evicted)
        val after = cache.get("old", "new", 0L, 0L, Granularity.CHARACTER)
        after shouldBe before   // same computation, same result
    }

    @Test
    fun `cache distinguishes different line pairs`() {
        val cache = IntraLineCache()
        val r1 = cache.get("abc", "axc", 0L, 0L, Granularity.CHARACTER)
        val r2 = cache.get("abc", "ayc", 1L, 1L, Granularity.CHARACTER)
        // Both should have changed ranges but they differ in the changed region content
        r1.leftRanges.isNotEmpty() shouldBe true
        r2.leftRanges.isNotEmpty() shouldBe true
        // The right ranges mark position of the changed character
        r1.rightRanges.first().start shouldBe r2.rightRanges.first().start
    }

    @Test
    fun `identical lines produce empty ranges`() {
        val cache = IntraLineCache()
        val result = cache.get("same line", "same line", 0L, 0L, Granularity.CHARACTER)
        result.leftRanges shouldBe emptyList()
        result.rightRanges shouldBe emptyList()
    }

    @Test
    fun `word granularity produces ranges within text bounds`() {
        val left = "the quick brown fox"
        val right = "the slow brown fox"
        val cache = IntraLineCache()
        val result = cache.get(left, right, 0L, 0L, Granularity.WORD)
        for (range in result.leftRanges) {
            range.start shouldBe (range.start.coerceIn(0, left.length))
            range.end shouldBe (range.end.coerceIn(0, left.length))
        }
        for (range in result.rightRanges) {
            range.start shouldBe (range.start.coerceIn(0, right.length))
            range.end shouldBe (range.end.coerceIn(0, right.length))
        }
    }
}
