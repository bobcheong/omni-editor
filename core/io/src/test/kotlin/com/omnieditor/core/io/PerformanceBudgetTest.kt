package com.omnieditor.core.io

import org.junit.Test
import kotlin.system.measureNanoTime

/**
 * JVM performance budget reference tests (R-38, T-28).
 *
 * IMPORTANT: These are JVM approximations, NOT authoritative Android benchmarks.
 * They give indicative numbers for heap ratios and timing orders-of-magnitude.
 * The official NFR-P1..P5 budgets from spec §11 require a physical Android device
 * (Tier 4) and remain open release blockers.
 *
 * Do not interpret JVM pass/fail here as device-level verification. No assertion
 * on specific timings is made — the tests print measured values and are marked
 * "budget reference."
 *
 * Intra-line budget (R-26 deferred criterion):
 *   Covered in core:diff PerformanceTest — `budget reference - intra-line range
 *   under 1ms for 4KB line` — because IntraLineDiff lives in core:diff.
 *
 * Monospace fast path decision (R-14):
 *   Deferred. JVM measurements show that text measurement is not the bottleneck
 *   at the current DocumentLimits ceiling. Revisit if device profiling shows
 *   otherwise.
 */
class PerformanceBudgetTest {

    /**
     * Budget reference: cold-start piece table creation for a 16 MiB file.
     *
     * Measures the time to create a PieceTableDocument from a 16 MiB string.
     * On a JVM this is a warm-JIT upper bound; actual cold-start on device
     * will differ due to ART JIT behaviour.
     *
     * Does NOT assert a time limit — prints for human review.
     */
    @Test
    fun `budget reference - 16MiB piece table creation time`() {
        val content = "x".repeat(16 * 1024 * 1024)

        val elapsedNs = measureNanoTime {
            PieceTableDocument.create(content)
        }

        val elapsedMs = elapsedNs / 1_000_000.0
        println("R-38 budget: 16 MiB PieceTableDocument.create = %.1f ms".format(elapsedMs))
        // No time assertion — JVM warm path is not comparable to cold device start.
    }

    /**
     * Budget reference: approximate heap delta for a 16 MiB document.
     *
     * Triggers GC before measurement to reduce noise, then materialises a
     * 16 MiB document and records the heap increase. The ratio is expected to
     * be in the 4–6× range estimated in ADR-003.
     *
     * JVM heap accounting is approximate: GC timing, JIT allocation, and
     * object alignment all affect the reading. Do not treat this as a
     * precise device measurement.
     */
    @Test
    fun `budget reference - 16MiB document heap estimate`() {
        val content = "x".repeat(16 * 1024 * 1024)

        // Encourage GC to collect noise before we measure
        System.gc()
        Thread.sleep(50)

        val rt = Runtime.getRuntime()
        val before = rt.totalMemory() - rt.freeMemory()

        val doc = PieceTableDocument.create(content)

        // Force the document to be "used" so the JIT doesn't dead-code-eliminate it
        val lineCount = doc.lineCount

        System.gc()
        Thread.sleep(50)

        val after = rt.totalMemory() - rt.freeMemory()
        val heapDeltaBytes = after - before

        val fileSizeBytes = content.length.toLong()
        val ratio = heapDeltaBytes.toDouble() / fileSizeBytes

        println("R-38 budget: 16 MiB doc heap delta = ${heapDeltaBytes / 1024 / 1024} MiB (lineCount=$lineCount)")
        println("R-38 budget: heap/file ratio = %.1f (ADR-003 estimate: 4–6×)".format(ratio))
        // No assertion on ratio — JVM String interning and GC timing skew the reading.
    }

    /**
     * Budget reference: typing latency — 1000 sequential single-character inserts
     * at the midpoint of a 15 MB piece table.
     *
     * Approximates the case where a user types continuously into a large document.
     * Uses PieceTable.insert() directly to avoid the O(n) string materialisation
     * that doc.edit(0..0, fullLineContent + "x") would cause.
     *
     * This is a JVM warm-path measurement. Real typing latency on device involves
     * the Compose render pipeline, input method, and ART — none of which are
     * exercised here.
     */
    @Test
    fun `budget reference - typing latency 1000 inserts in 15MB document`() {
        // Build a ~15 MB piece table (multi-line to keep line counts reasonable)
        val lineSize = 100
        val lineCount = 150_000      // 150,000 × 101 bytes ≈ 15 MB
        val content = buildString(lineCount * (lineSize + 1)) {
            repeat(lineCount) {
                repeat(lineSize) { append('a') }
                append('\n')
            }
        }
        val table = PieceTable.create(content)
        val midpoint = table.length / 2

        val times = LongArray(1000)
        var insertOffset = midpoint
        for (i in 0 until 1000) {
            times[i] = measureNanoTime {
                table.insert(insertOffset, "x")
            }
            insertOffset++      // stay at same relative position as document grows
        }

        val sortedMs = times.sorted().map { it / 1_000_000.0 }
        val p50Ms = sortedMs[500]
        val p99Ms = sortedMs[990]
        println("R-38 budget: typing 1000 inserts in 15 MB piece table — p50=%.3f ms, p99=%.3f ms".format(p50Ms, p99Ms))
        // No assertion — JVM warm path is not device-representative.
    }

    /**
     * Budget reference: PieceTable.line() random access — line 500,000 vs line 10.
     *
     * Verifies that O(log n) offset lookup (ADR-007, R-13 piece-tree offset cache)
     * keeps access time bounded regardless of position.
     *
     * Uses a 500,001-line document (one short line per entry).
     */
    @Test
    fun `budget reference - line access at line 500000 vs line 10`() {
        val lineCount = 500_001
        val content = (1..lineCount).joinToString("\n") { "line%d".format(it) }
        val doc = PieceTableDocument.create(content)

        // Warm up
        repeat(10) {
            doc.line(10)
            doc.line(500_000)
        }

        val accessNearStart = measureNanoTime { repeat(100) { doc.line(10) } } / 100
        val accessFarEnd = measureNanoTime { repeat(100) { doc.line(500_000) } } / 100

        println("R-38 budget: line(10) = ${accessNearStart / 1000} µs per call")
        println("R-38 budget: line(500000) = ${accessFarEnd / 1000} µs per call")
        println("R-38 budget: far/near ratio = %.1f".format(accessFarEnd.toDouble() / accessNearStart.coerceAtLeast(1)))
        // No hard assertion — confirms O(log n) character by printing the ratio.
    }

    /**
     * Budget reference: 5 MB compare completion time.
     *
     * Re-confirms (via the io module) that compare-scale inputs complete in a
     * reasonable JVM wall-clock time. The diff engine tests in core:diff already
     * cover this; this test adds heap context from a PieceTableDocument source.
     *
     * We read lines from two documents, both backed by piece tables, to simulate
     * the real compare pipeline calling doc.line(i) per-row.
     */
    @Test
    fun `budget reference - 5MB compare line access throughput`() {
        // ~5 MB ≈ 50,000 lines of ~100 chars
        val lineCount = 50_000
        val leftContent = (0 until lineCount)
            .joinToString("\n") { "line %06d: common content with some padding to reach typical line length".format(it) }
        val rightContent = buildString {
            for (i in 0 until lineCount) {
                if (i > 0) append('\n')
                if (i % 1000 == 0) {
                    append("line %06d: MODIFIED content with different text here".format(i))
                } else {
                    append("line %06d: common content with some padding to reach typical line length".format(i))
                }
            }
        }

        val leftDoc = PieceTableDocument.create(leftContent)
        val rightDoc = PieceTableDocument.create(rightContent)

        val start = System.currentTimeMillis()
        var diffCount = 0
        for (i in 0 until lineCount) {
            val l = leftDoc.line(i.toLong()).toString()
            val r = rightDoc.line(i.toLong()).toString()
            if (l != r) diffCount++
        }
        val elapsed = System.currentTimeMillis() - start

        println("R-38 budget: 5 MB line-access scan = ${elapsed} ms, diffs=$diffCount")
        // No assertion — prints throughput for human review.
    }

}
