package com.omnieditor.core.diff

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * C.3: Property test — applying all left->right merges to the left document
 * produces a document byte-identical to the right document.
 */
class MergeRoundTripPropertyTest {

    @Test
    fun `applying all hunks to left produces right`() = runTest {
        checkAll(50, Arb.list(Arb.string(1..40), 1..50)) { lines ->
            // Generate left and right by modifying some lines
            val left = lines.toMutableList()
            val right = lines.toMutableList()
            val rng = kotlin.random.Random(lines.hashCode().toLong())
            for (i in right.indices) {
                if (rng.nextDouble() < 0.3) {
                    right[i] = "MODIFIED_${rng.nextInt()}"
                }
            }
            // Occasionally add/remove lines
            if (right.size > 3 && rng.nextDouble() < 0.2) {
                right.removeAt(rng.nextInt(right.size))
            }
            if (rng.nextDouble() < 0.2) {
                right.add(rng.nextInt(right.size + 1), "INSERTED_${rng.nextInt()}")
            }

            val result = DiffEngine.compare(
                leftLineCount = left.size.toLong(),
                rightLineCount = right.size.toLong(),
                leftLine = { left[it.toInt()] },
                rightLine = { right[it.toInt()] },
            )

            // Apply all hunks: replace left ranges with right content
            val merged = left.toMutableList()
            var offset = 0L
            for (hunk in result.hunks) {
                val adjStart = (hunk.leftStart + offset).toInt()
                val leftCount = (hunk.leftEnd - hunk.leftStart).toInt()
                val rightContent = (hunk.rightStart until hunk.rightEnd).map { right[it.toInt()] }

                // Remove old lines, insert new
                repeat(leftCount) {
                    if (adjStart < merged.size) merged.removeAt(adjStart)
                }
                merged.addAll(adjStart, rightContent)

                offset += (hunk.rightEnd - hunk.rightStart) - (hunk.leftEnd - hunk.leftStart)
            }

            merged shouldBe right
        }
    }
}
