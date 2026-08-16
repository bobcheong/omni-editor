package com.omnieditor.core.model

/**
 * Size limits and tiering for documents (ADR-012, replacing ADR-003 cliff).
 *
 * The size ladder ensures files are never silently degraded (OE-ENG-4).
 * Each tier is disclosed in the UI header.
 */
object DocumentLimits {
    /** Maximum file size for full in-memory editing (PieceTable). */
    const val EDITOR_MAX_BYTES: Long = 16L * 1024 * 1024  // 16 MiB

    /** Maximum file size for indexed read-only mode (FileIndexer + mmap). */
    const val INDEXED_MAX_BYTES: Long = 256L * 1024 * 1024  // 256 MiB

    /** Fraction of max at which a warning (not block) is shown. */
    const val WARN_FRACTION: Double = 0.5

    /** Maximum line length in bytes. Lines above this are rendered truncated. */
    const val MAX_LINE_BYTES: Long = 1L * 1024 * 1024  // 1 MiB

    /** Backwards compatibility alias for existing code referencing compare limit. */
    const val COMPARE_MAX_BYTES_PER_SIDE: Long = INDEXED_MAX_BYTES

    enum class SizeTier {
        /** Full in-memory: PieceTableDocument, all editing features. */
        FULL_MEMORY,
        /** Indexed read-only: LargeFileDocument over mmap'd channel. */
        INDEXED_READ_ONLY,
        /** Refused with OmniError.TooLarge. */
        REFUSED,
    }

    /** Determine the editor tier for a file of [sizeBytes]. */
    fun editorTier(sizeBytes: Long): SizeTier = when {
        sizeBytes <= EDITOR_MAX_BYTES -> SizeTier.FULL_MEMORY
        sizeBytes <= INDEXED_MAX_BYTES -> SizeTier.INDEXED_READ_ONLY
        else -> SizeTier.REFUSED
    }

    /** Determine the compare tier for a file of [sizeBytes]. */
    fun compareTier(sizeBytes: Long): SizeTier = when {
        sizeBytes <= EDITOR_MAX_BYTES -> SizeTier.FULL_MEMORY
        sizeBytes <= INDEXED_MAX_BYTES -> SizeTier.INDEXED_READ_ONLY
        else -> SizeTier.REFUSED
    }
}
