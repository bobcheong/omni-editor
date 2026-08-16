package com.omnieditor.benchmark

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import kotlin.random.Random

/**
 * Deterministic fixture generator for benchmark runs.
 *
 * All generators use a fixed seed so output is byte-identical across runs.
 * Files are written to a specified output directory (typically benchmark/build/fixtures/).
 *
 * This class is used by on-device benchmark tests. For off-device fixture generation
 * the equivalent logic lives inline in the Gradle `generateFixtures` task.
 */
object FixtureGenerator {

    private const val SEED = 20260816L
    private const val CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .,-_"

    /**
     * Generate a 250 MB pair with ~80% shared content, ~20% differing blocks.
     * Each line is ~100 characters + newline. ~2.5M lines per file.
     */
    fun generateLargePair(outputDir: File) {
        outputDir.mkdirs()
        val leftFile = File(outputDir, "large-left.txt")
        val rightFile = File(outputDir, "large-right.txt")
        val rng = Random(SEED)

        BufferedWriter(FileWriter(leftFile)).use { left ->
            BufferedWriter(FileWriter(rightFile)).use { right ->
                for (i in 0 until 2_500_000) {
                    val prefix = "%06d ".format(i)
                    val body = buildSegment(rng, 93)
                    val line = prefix + body
                    if (rng.nextDouble() < 0.20) {
                        val modified = line.replaceRange(10, 30, buildSegment(rng, 20))
                        left.write(line); left.newLine()
                        right.write(modified); right.newLine()
                    } else {
                        left.write(line); left.newLine()
                        right.write(line); right.newLine()
                    }
                }
            }
        }
    }

    /**
     * Generate a 500k-line file for scroll benchmarks.
     * Each line: zero-padded line number + space + deterministic padding (~87 chars total).
     */
    fun generateScrollFile(outputDir: File) {
        outputDir.mkdirs()
        val file = File(outputDir, "scroll-500k.txt")
        val rng = Random(SEED + 1)

        BufferedWriter(FileWriter(file)).use { writer ->
            for (i in 0 until 500_000) {
                writer.write("%06d ".format(i) + buildSegment(rng, 80))
                writer.newLine()
            }
        }
    }

    private fun buildSegment(rng: Random, length: Int): String =
        buildString(length) { repeat(length) { append(CHARS[rng.nextInt(CHARS.length)]) } }
}
