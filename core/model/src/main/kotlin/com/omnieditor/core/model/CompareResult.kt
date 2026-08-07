package com.omnieditor.core.model

import kotlinx.serialization.Serializable

/** Line ranges are 0-based, half-open, in normalised line space. */
@Serializable
data class Hunk(
    val leftStart: Long, val leftEnd: Long,
    val rightStart: Long, val rightEnd: Long,
    val type: HunkType,
)

@Serializable
enum class HunkType { ADDED, REMOVED, CHANGED, CONFLICT }

@Serializable
data class CompareStats(
    val linesAdded: Long = 0,
    val linesRemoved: Long = 0,
    val linesChanged: Long = 0,
    val hunkCount: Int = 0,
)

/** OE-ENG-4: the mode is reported, never applied silently. */
@Serializable
enum class EngineMode { FULL_INDEX, BLOCK_MATCH }

@Serializable
data class CompareResult(
    val hunks: List<Hunk>,
    val stats: CompareStats,
    val engineMode: EngineMode,
    val generatedAt: Long,
    val stale: Boolean = false,
)

data class Progress(val done: Long, val total: Long?, val phase: Phase)
enum class Phase { INDEXING, NORMALISING, DIFFING, DONE }
