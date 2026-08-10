package com.omnieditor.core.model

/**
 * Single source of truth for document size limits (D-2).
 *
 * Starting values, to be confirmed or lowered by R-38's memory test.
 * See docs/adr/003-size-ceiling.md.
 */
object DocumentLimits {
    /** Maximum file size for the editor. Files above this are refused with OmniError.TooLarge. */
    const val EDITOR_MAX_BYTES: Long = 16L * 1024 * 1024  // 16 MiB

    /** Maximum file size per side for compare. */
    const val COMPARE_MAX_BYTES_PER_SIDE: Long = 8L * 1024 * 1024  // 8 MiB

    /** Fraction of max at which a warning (not block) is shown. */
    const val WARN_FRACTION: Double = 0.5

    /** Maximum line length in bytes. Lines above this are rendered truncated with an expand action. */
    const val MAX_LINE_BYTES: Long = 1L * 1024 * 1024  // 1 MiB
}
