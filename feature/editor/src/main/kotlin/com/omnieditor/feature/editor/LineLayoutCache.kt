package com.omnieditor.feature.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints

/**
 * Per-line text measurement cache backed by Compose [TextMeasurer].
 *
 * Produces a [TextLayoutResult] for each visible line, expanding tabs
 * to spaces before measurement. The layout result provides correct
 * offset-to-position and position-to-offset mapping for bidi, CJK,
 * grapheme clusters and combining marks — all handled by the platform.
 *
 * Cache entries are keyed by `(lineIndex, editGeneration)` packed into
 * a single [Long]. Capacity defaults to 200 entries (~200 visible lines).
 */
class LineLayoutCache(
    private val textMeasurer: TextMeasurer,
    private val style: TextStyle,
    private val tabWidth: Int = 4,
    private val cacheCapacity: Int = 200,
) {
    /**
     * LRU cache implemented via access-ordered [LinkedHashMap].
     * Key packing: upper 32 bits = lineIndex (truncated), lower 32 bits = editGeneration.
     * Collisions are harmless — they just cause a re-measure.
     */
    private val cache = object : LinkedHashMap<Long, TextLayoutResult>(
        cacheCapacity, 0.75f, true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, TextLayoutResult>?): Boolean {
            return size > cacheCapacity
        }
    }

    /** Measure (or retrieve from cache) the layout for a single line. */
    fun measure(
        lineIndex: Long,
        lineText: CharSequence,
        constraints: Constraints,
        editGeneration: Long = 0L,
    ): TextLayoutResult {
        val key = packKey(lineIndex, editGeneration)
        cache[key]?.let { return it }

        val expandedText = expandTabs(lineText, tabWidth)
        val result = textMeasurer.measure(
            text = AnnotatedString(expandedText),
            style = style,
            constraints = constraints,
        )
        cache[key] = result
        return result
    }

    /**
     * Measure with an [AnnotatedString] (for syntax-highlighted text).
     * Tabs should already be expanded in the annotated string.
     */
    fun measure(
        lineIndex: Long,
        annotatedText: AnnotatedString,
        constraints: Constraints,
        editGeneration: Long = 0L,
    ): TextLayoutResult {
        val key = packKey(lineIndex, editGeneration)
        cache[key]?.let { return it }

        val result = textMeasurer.measure(
            text = annotatedText,
            style = style,
            constraints = constraints,
        )
        cache[key] = result
        return result
    }

    /** Character offset in the laid-out text closest to the given pixel position. */
    fun offsetForPosition(layout: TextLayoutResult, position: Offset): Int {
        return layout.getOffsetForPosition(position)
    }

    /** Cursor rectangle for the given character offset. */
    fun cursorRect(layout: TextLayoutResult, offset: Int): Rect {
        return layout.getCursorRect(offset.coerceIn(0, layout.layoutInput.text.length))
    }

    /** Line height of the given layout (first line). */
    fun lineHeight(layout: TextLayoutResult): Float {
        return if (layout.lineCount > 0) {
            layout.getLineBottom(0) - layout.getLineTop(0)
        } else {
            0f
        }
    }

    /** Evict all cached entries. Call when style or tab-width changes. */
    fun evictAll() {
        cache.clear()
    }

    private fun packKey(lineIndex: Long, editGeneration: Long): Long {
        return (lineIndex shl 32) or (editGeneration and 0xFFFFFFFFL)
    }
}
