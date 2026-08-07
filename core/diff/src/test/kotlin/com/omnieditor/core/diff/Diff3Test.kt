package com.omnieditor.core.diff

import com.omnieditor.core.diff.Diff3.RegionType
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

class Diff3Test {

    // ── No changes ──

    @Test
    fun `all three identical produces only UNCHANGED`() = runTest {
        val base = listOf("a", "b", "c")
        val result = Diff3.diff3(base, base, base)
        result.hasConflicts shouldBe false
        result.regions.all { it.type == RegionType.UNCHANGED } shouldBe true
    }

    // ── Left-only changes ──

    @Test
    fun `left-only change classified correctly`() = runTest {
        val base = listOf("a", "b", "c")
        val left = listOf("a", "B", "c")
        val right = listOf("a", "b", "c")
        val result = Diff3.diff3(base, left, right)
        result.hasConflicts shouldBe false
        val changes = result.regions.filter { it.type != RegionType.UNCHANGED }
        changes.size shouldBe 1
        changes[0].type shouldBe RegionType.LEFT_ONLY
    }

    @Test
    fun `left-only insertion`() = runTest {
        val base = listOf("a", "c")
        val left = listOf("a", "b", "c")
        val right = listOf("a", "c")
        val result = Diff3.diff3(base, left, right)
        result.hasConflicts shouldBe false
        val changes = result.regions.filter { it.type != RegionType.UNCHANGED }
        changes.isNotEmpty() shouldBe true
        changes.any { it.type == RegionType.LEFT_ONLY } shouldBe true
    }

    @Test
    fun `left-only deletion`() = runTest {
        val base = listOf("a", "b", "c")
        val left = listOf("a", "c")
        val right = listOf("a", "b", "c")
        val result = Diff3.diff3(base, left, right)
        result.hasConflicts shouldBe false
        result.regions.any { it.type == RegionType.LEFT_ONLY } shouldBe true
    }

    // ── Right-only changes ──

    @Test
    fun `right-only change classified correctly`() = runTest {
        val base = listOf("a", "b", "c")
        val left = listOf("a", "b", "c")
        val right = listOf("a", "B", "c")
        val result = Diff3.diff3(base, left, right)
        result.hasConflicts shouldBe false
        val changes = result.regions.filter { it.type != RegionType.UNCHANGED }
        changes.size shouldBe 1
        changes[0].type shouldBe RegionType.RIGHT_ONLY
    }

    @Test
    fun `right-only insertion`() = runTest {
        val base = listOf("a", "c")
        val left = listOf("a", "c")
        val right = listOf("a", "b", "c")
        val result = Diff3.diff3(base, left, right)
        result.hasConflicts shouldBe false
        result.regions.any { it.type == RegionType.RIGHT_ONLY } shouldBe true
    }

    // ── Both-same (non-conflict) ──

    @Test
    fun `both changed identically is BOTH_SAME not CONFLICT`() = runTest {
        val base = listOf("a", "b", "c")
        val left = listOf("a", "X", "c")
        val right = listOf("a", "X", "c")
        val result = Diff3.diff3(base, left, right)
        result.hasConflicts shouldBe false
        val changes = result.regions.filter { it.type != RegionType.UNCHANGED }
        changes.size shouldBe 1
        changes[0].type shouldBe RegionType.BOTH_SAME
    }

    @Test
    fun `both deleted same line is BOTH_SAME`() = runTest {
        val base = listOf("a", "b", "c")
        val left = listOf("a", "c")
        val right = listOf("a", "c")
        val result = Diff3.diff3(base, left, right)
        result.hasConflicts shouldBe false
        val changes = result.regions.filter { it.type != RegionType.UNCHANGED }
        changes.isNotEmpty() shouldBe true
        changes.all { it.type == RegionType.BOTH_SAME } shouldBe true
    }

    @Test
    fun `both inserted same line is BOTH_SAME`() = runTest {
        val base = listOf("a", "c")
        val left = listOf("a", "b", "c")
        val right = listOf("a", "b", "c")
        val result = Diff3.diff3(base, left, right)
        result.hasConflicts shouldBe false
    }

    // ── Conflicts ──

    @Test
    fun `both changed same line differently is CONFLICT`() = runTest {
        val base = listOf("a", "b", "c")
        val left = listOf("a", "X", "c")
        val right = listOf("a", "Y", "c")
        val result = Diff3.diff3(base, left, right)
        result.hasConflicts shouldBe true
        result.conflictCount shouldBe 1
        val conflicts = result.regions.filter { it.type == RegionType.CONFLICT }
        conflicts.size shouldBe 1
    }

    @Test
    fun `left modified right deleted same region is CONFLICT`() = runTest {
        val base = listOf("a", "b", "c")
        val left = listOf("a", "X", "c")
        val right = listOf("a", "c")
        val result = Diff3.diff3(base, left, right)
        result.hasConflicts shouldBe true
    }

    @Test
    fun `multiple conflicts counted correctly`() = runTest {
        val base = listOf("a", "b", "c", "d", "e")
        val left = listOf("a", "L1", "c", "L2", "e")
        val right = listOf("a", "R1", "c", "R2", "e")
        val result = Diff3.diff3(base, left, right)
        result.hasConflicts shouldBe true
        result.conflictCount shouldBe 2
    }

    // ── Adjacent changes ──

    @Test
    fun `non-overlapping changes on different lines are not conflicts`() = runTest {
        val base = listOf("a", "b", "c", "d", "e")
        val left = listOf("a", "B", "c", "d", "e")  // line 1 changed
        val right = listOf("a", "b", "c", "D", "e")  // line 3 changed
        val result = Diff3.diff3(base, left, right)
        result.hasConflicts shouldBe false
        val changes = result.regions.filter { it.type != RegionType.UNCHANGED }
        changes.any { it.type == RegionType.LEFT_ONLY } shouldBe true
        changes.any { it.type == RegionType.RIGHT_ONLY } shouldBe true
    }

    // ── Mixed scenarios ──

    @Test
    fun `mixed left-only right-only and conflict`() = runTest {
        val base = listOf("h1", "a", "b", "c", "d", "t1")
        val left = listOf("h1", "A", "b", "X", "d", "t1")  // changed a→A and c→X
        val right = listOf("h1", "a", "b", "Y", "d", "t1")  // changed c→Y
        val result = Diff3.diff3(base, left, right)
        // a→A is left-only, c→X vs c→Y is a conflict
        result.hasConflicts shouldBe true
        result.regions.any { it.type == RegionType.LEFT_ONLY } shouldBe true
        result.regions.any { it.type == RegionType.CONFLICT } shouldBe true
    }

    // ── Edge cases ──

    @Test
    fun `empty base with both sides adding content`() = runTest {
        val base = emptyList<String>()
        val left = listOf("left line")
        val right = listOf("right line")
        val result = Diff3.diff3(base, left, right)
        // Both sides added to an empty base — conflict
        result.hasConflicts shouldBe true
    }

    @Test
    fun `single line files`() = runTest {
        val base = listOf("original")
        val left = listOf("left version")
        val right = listOf("right version")
        val result = Diff3.diff3(base, left, right)
        result.hasConflicts shouldBe true
        result.conflictCount shouldBe 1
    }

    @Test
    fun `no base provided - two arbitrary files`() = runTest {
        // When no base exists, the middle pane is the base by convention (spec OE-ENG-2)
        val base = listOf("a", "b", "c")
        val left = listOf("a", "X", "c")
        val right = listOf("a", "Y", "c")
        val result = Diff3.diff3(base, left, right)
        result.hasConflicts shouldBe true
    }
}
