package com.omnieditor.core.io

import com.omnieditor.core.model.LineEnding

/**
 * Encoding detection from raw bytes (OE-SRC-5).
 *
 * Strategy: BOM first, then UTF-8 validation, then heuristic for common 8-bit codepages.
 * This runs once per source at index time — it does not re-detect on every read.
 */
object EncodingDetector {

    data class Result(
        val charset: String,
        val bomLength: Int,
        val confidence: Confidence,
    )

    enum class Confidence { BOM, VALIDATED, HEURISTIC }

    /**
     * Detect encoding from the first [sampleSize] bytes.
     * The sample should be at least 4 bytes for BOM detection and ideally 8–64 KB
     * for reliable heuristic detection.
     */
    fun detect(sample: ByteArray, sampleSize: Int = sample.size): Result {
        val size = minOf(sampleSize, sample.size)
        if (size == 0) return Result("UTF-8", 0, Confidence.HEURISTIC)

        // 1. BOM detection (most specific first)
        detectBom(sample, size)?.let { return it }

        // 2. UTF-8 validation
        if (isValidUtf8(sample, size)) {
            return Result("UTF-8", 0, Confidence.VALIDATED)
        }

        // 3. Heuristic: high-byte frequency analysis for common 8-bit codepages
        return detectByHeuristic(sample, size)
    }

    private fun detectBom(bytes: ByteArray, size: Int): Result? {
        if (size >= 4) {
            // UTF-32 LE BOM: FF FE 00 00
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() &&
                bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()
            ) return Result("UTF-32LE", 4, Confidence.BOM)
            // UTF-32 BE BOM: 00 00 FE FF
            if (bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
                bytes[2] == 0xFE.toByte() && bytes[3] == 0xFF.toByte()
            ) return Result("UTF-32BE", 4, Confidence.BOM)
        }
        if (size >= 3) {
            // UTF-8 BOM: EF BB BF
            if (bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                return Result("UTF-8", 3, Confidence.BOM)
            }
        }
        if (size >= 2) {
            // UTF-16 LE BOM: FF FE (but not followed by 00 00, already checked)
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return Result("UTF-16LE", 2, Confidence.BOM)
            }
            // UTF-16 BE BOM: FE FF
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return Result("UTF-16BE", 2, Confidence.BOM)
            }
        }
        return null
    }

    /**
     * Validate that [bytes] is well-formed UTF-8.
     * Returns false if any invalid sequence is found.
     */
    internal fun isValidUtf8(bytes: ByteArray, size: Int): Boolean {
        var i = 0
        var hasHighBytes = false
        while (i < size) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b <= 0x7F -> i++
                b in 0xC2..0xDF -> {
                    if (i + 1 >= size) return true // truncated at boundary — assume valid
                    if ((bytes[i + 1].toInt() and 0xC0) != 0x80) return false
                    hasHighBytes = true
                    i += 2
                }
                b in 0xE0..0xEF -> {
                    if (i + 2 >= size) return true
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    if ((b1 and 0xC0) != 0x80) return false
                    if ((bytes[i + 2].toInt() and 0xC0) != 0x80) return false
                    // Overlong check
                    if (b == 0xE0 && b1 < 0xA0) return false
                    // Surrogate range
                    if (b == 0xED && b1 >= 0xA0) return false
                    hasHighBytes = true
                    i += 3
                }
                b in 0xF0..0xF4 -> {
                    if (i + 3 >= size) return true
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    if ((b1 and 0xC0) != 0x80) return false
                    if ((bytes[i + 2].toInt() and 0xC0) != 0x80) return false
                    if ((bytes[i + 3].toInt() and 0xC0) != 0x80) return false
                    if (b == 0xF0 && b1 < 0x90) return false
                    if (b == 0xF4 && b1 > 0x8F) return false
                    hasHighBytes = true
                    i += 4
                }
                else -> return false // 0x80..0xBF or 0xC0..0xC1 or 0xF5+
            }
        }
        return true
    }

    private fun detectByHeuristic(bytes: ByteArray, size: Int): Result {
        // Count bytes in various ranges to distinguish common codepages
        var nullCount = 0
        var highCount = 0
        var windows1252Likely = 0

        for (i in 0 until size) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b == 0 -> nullCount++
                b >= 0x80 -> {
                    highCount++
                    // Windows-1252 specific: printable characters in 0x80..0x9F
                    if (b in 0x80..0x9F && b != 0x81 && b != 0x8D && b != 0x8F && b != 0x90 && b != 0x9D) {
                        windows1252Likely++
                    }
                }
            }
        }

        // If lots of nulls, might be UTF-16 without BOM
        if (nullCount > size / 10) {
            // Check if every other byte is null → UTF-16
            var leNulls = 0
            var beNulls = 0
            for (i in 0 until size - 1 step 2) {
                if (bytes[i + 1] == 0.toByte()) leNulls++
                if (bytes[i] == 0.toByte()) beNulls++
            }
            val pairs = size / 2
            if (leNulls > pairs * 3 / 4) return Result("UTF-16LE", 0, Confidence.HEURISTIC)
            if (beNulls > pairs * 3 / 4) return Result("UTF-16BE", 0, Confidence.HEURISTIC)
        }

        // High bytes present but not valid UTF-8 → probably Windows-1252 or ISO-8859-1
        return if (windows1252Likely > 0) {
            Result("windows-1252", 0, Confidence.HEURISTIC)
        } else if (highCount > 0) {
            Result("ISO-8859-1", 0, Confidence.HEURISTIC)
        } else {
            // Pure ASCII — report as UTF-8 (superset)
            Result("UTF-8", 0, Confidence.VALIDATED)
        }
    }
}

/**
 * Detect the dominant line ending in a byte sample.
 */
object LineEndingDetector {

    fun detect(bytes: ByteArray, size: Int = bytes.size): LineEnding {
        val limit = minOf(size, bytes.size)
        var lf = 0
        var crlf = 0
        var cr = 0
        var i = 0
        while (i < limit) {
            when {
                bytes[i] == '\r'.code.toByte() -> {
                    if (i + 1 < limit && bytes[i + 1] == '\n'.code.toByte()) {
                        crlf++
                        i += 2
                    } else {
                        cr++
                        i++
                    }
                }
                bytes[i] == '\n'.code.toByte() -> {
                    lf++
                    i++
                }
                else -> i++
            }
        }
        return when {
            crlf >= lf && crlf >= cr && crlf > 0 -> LineEnding.CRLF
            cr > lf && cr > 0 -> LineEnding.CR
            else -> LineEnding.LF
        }
    }
}
