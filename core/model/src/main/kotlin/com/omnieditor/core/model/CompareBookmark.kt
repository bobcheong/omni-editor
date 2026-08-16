package com.omnieditor.core.model

import kotlinx.serialization.Serializable

/**
 * F-08: A bookmark placed on a specific line in a compare view.
 * Persisted with the session.
 */
@Serializable
data class CompareBookmark(
    val lineIndex: Long,
    val side: CompareSide,
    val label: String? = null,
)

@Serializable
enum class CompareSide { LEFT, RIGHT }
