package com.omnieditor.core.io

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Lightweight fingerprint for detecting external file modifications (ADR-015).
 *
 * Records size, last-modified time, and a content hash (FNV-1a of first 4 KiB +
 * last 4 KiB). The content hash catches in-place overwrites that preserve size
 * and timestamp (rare but possible with buffered writes).
 */
data class FileFingerprint(
    val size: Long,
    val lastModified: Long,
    val contentHash: Long,
) {
    companion object {
        private const val SAMPLE_SIZE = 4096

        /** Compute a fingerprint for [file]. */
        fun of(file: File): FileFingerprint {
            val size = file.length()
            val lastModified = file.lastModified()
            val hash = hashFile(file, size)
            return FileFingerprint(size, lastModified, hash)
        }

        /**
         * Check whether [file] still matches [expected].
         * Returns true if the file appears unmodified.
         */
        fun check(file: File, expected: FileFingerprint): Boolean {
            if (!file.exists()) return false
            if (file.length() != expected.size) return false
            if (file.lastModified() != expected.lastModified) return false
            val currentHash = hashFile(file, file.length())
            return currentHash == expected.contentHash
        }

        private fun hashFile(file: File, size: Long): Long {
            if (size == 0L) return 0L
            RandomAccessFile(file, "r").use { raf ->
                val channel = raf.channel
                // FNV-1a 64-bit
                var hash = -3750763034362895579L // FNV offset basis
                val prime = 1099511628211L

                // Hash first SAMPLE_SIZE bytes (or all bytes if file is small)
                val firstLen = minOf(size, SAMPLE_SIZE.toLong()).toInt()
                val firstBuf = ByteBuffer.allocate(firstLen)
                channel.read(firstBuf, 0)
                firstBuf.flip()
                for (i in 0 until firstBuf.limit()) {
                    hash = hash xor (firstBuf.get(i).toLong() and 0xFF)
                    hash *= prime
                }

                // Hash last SAMPLE_SIZE bytes (skip if file fits in first sample)
                if (size > SAMPLE_SIZE) {
                    val lastStart = size - SAMPLE_SIZE
                    val lastLen = SAMPLE_SIZE
                    val lastBuf = ByteBuffer.allocate(lastLen)
                    channel.read(lastBuf, lastStart)
                    lastBuf.flip()
                    for (i in 0 until lastBuf.limit()) {
                        hash = hash xor (lastBuf.get(i).toLong() and 0xFF)
                        hash *= prime
                    }
                }

                return hash
            }
        }
    }
}
