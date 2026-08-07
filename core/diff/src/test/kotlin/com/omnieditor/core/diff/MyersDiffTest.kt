package com.omnieditor.core.diff

import io.kotest.matchers.shouldBe
import org.junit.Test

class MyersDiffTest {

    @Test
    fun `identical sequences produce no edits`() {
        val a = longArrayOf(1, 2, 3)
        val edits = MyersDiff.diff(a, 0, 3, a, 0, 3)
        edits shouldBe emptyList()
    }

    @Test
    fun `single element change`() {
        val a = longArrayOf(1, 2, 3)
        val b = longArrayOf(1, 9, 3)
        val edits = MyersDiff.diff(a, 0, 3, b, 0, 3)
        edits.size shouldBe 1
        edits[0].type shouldBe EditType.REPLACE
        edits[0].aStart shouldBe 1
        edits[0].aEnd shouldBe 2
        edits[0].bStart shouldBe 1
        edits[0].bEnd shouldBe 2
    }

    @Test
    fun `insertion at start`() {
        val a = longArrayOf(1, 2, 3)
        val b = longArrayOf(9, 1, 2, 3)
        val edits = MyersDiff.diff(a, 0, 3, b, 0, 4)
        edits.size shouldBe 1
        edits[0].type shouldBe EditType.INSERT
        edits[0].bStart shouldBe 0
        edits[0].bEnd shouldBe 1
    }

    @Test
    fun `deletion at start`() {
        val a = longArrayOf(9, 1, 2, 3)
        val b = longArrayOf(1, 2, 3)
        val edits = MyersDiff.diff(a, 0, 4, b, 0, 3)
        edits.size shouldBe 1
        edits[0].type shouldBe EditType.DELETE
        edits[0].aStart shouldBe 0
        edits[0].aEnd shouldBe 1
    }

    @Test
    fun `insertion at end`() {
        val a = longArrayOf(1, 2, 3)
        val b = longArrayOf(1, 2, 3, 9)
        val edits = MyersDiff.diff(a, 0, 3, b, 0, 4)
        edits.size shouldBe 1
        edits[0].type shouldBe EditType.INSERT
    }

    @Test
    fun `middle change with same surrounding context`() {
        val a = longArrayOf(1, 2, 3, 2, 4)
        val b = longArrayOf(1, 2, 5, 2, 4)
        val edits = MyersDiff.diff(a, 0, 5, b, 0, 5)
        edits.size shouldBe 1
        edits[0].type shouldBe EditType.REPLACE
        edits[0].aStart shouldBe 2
        edits[0].aEnd shouldBe 3
        edits[0].bStart shouldBe 2
        edits[0].bEnd shouldBe 3
    }

    @Test
    fun `empty left`() {
        val b = longArrayOf(1, 2)
        val edits = MyersDiff.diff(longArrayOf(), 0, 0, b, 0, 2)
        edits.size shouldBe 1
        edits[0].type shouldBe EditType.INSERT
    }

    @Test
    fun `empty right`() {
        val a = longArrayOf(1, 2)
        val edits = MyersDiff.diff(a, 0, 2, longArrayOf(), 0, 0)
        edits.size shouldBe 1
        edits[0].type shouldBe EditType.DELETE
    }

    @Test
    fun `completely different`() {
        val a = longArrayOf(1, 2, 3)
        val b = longArrayOf(4, 5, 6)
        val edits = MyersDiff.diff(a, 0, 3, b, 0, 3)
        edits.size shouldBe 1
        edits[0].type shouldBe EditType.REPLACE
    }

    @Test
    fun `subrange diff`() {
        val a = longArrayOf(0, 1, 2, 3, 0)
        val b = longArrayOf(0, 1, 9, 3, 0)
        // Diff only the middle portion [1..4)
        val edits = MyersDiff.diff(a, 1, 4, b, 1, 4)
        edits.size shouldBe 1
        edits[0].aStart shouldBe 2
        edits[0].aEnd shouldBe 3
    }
}
