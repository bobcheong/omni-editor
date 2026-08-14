package com.omnieditor.core.model

import kotlinx.serialization.Serializable

/**
 * A saved compare or edit definition: sources, mode, rules, display settings,
 * bookmarks, and the last result summary (OE-SES-1).
 *
 * Sessions are named, groupable into folders, searchable and pinnable (S-01).
 * The home screen IS the session list (OE-SES-2).
 */
@Serializable
data class Session(
    val id: String,
    val name: String,
    val mode: CompareMode,
    val groupId: String? = null,
    val pinned: Boolean = false,
    val createdAt: Long,
    val lastRunAt: Long? = null,
    val lastSummary: SessionSummary? = null,
    val sources: List<SourceRef> = emptyList(),
    val ruleSetId: String? = null,
    val displaySettings: DisplaySettings = DisplaySettings(),
    val bookmarks: List<Bookmark> = emptyList(),
)

@Serializable
enum class CompareMode { TEXT, FOLDER, TABLE, DOCUMENT, BINARY, EDITOR }

/**
 * Cached summary shown on the home screen without re-running the compare.
 */
@Serializable
data class SessionSummary(
    val hunkCount: Int = 0,
    val linesAdded: Long = 0,
    val linesRemoved: Long = 0,
    val linesChanged: Long = 0,
    val engineMode: EngineMode = EngineMode.FULL_INDEX,
    val stale: Boolean = false,
)

@Serializable
data class DisplaySettings(
    val layout: Layout = Layout.AUTO,
    val wordWrap: Boolean = false,
    val showLineNumbers: Boolean = true,
    val showWhitespace: Boolean = false,
    val tabWidth: Int = 4,
    val fontSize: Int = 14,
    val syncScroll: Boolean = true,
    val granularity: Granularity = Granularity.WORD,
    /**
     * When true, lines exceeding [DocumentLimits.MAX_LINE_BYTES] are rendered truncated
     * with an expand control. When false they are still truncated (to protect heap) but
     * without an explicit affordance for the user to expand them.
     */
    val truncateLongLines: Boolean = true,
)

@Serializable
enum class Layout { AUTO, UNIFIED, SPLIT }

@Serializable
data class Bookmark(
    val line: Long,
    val slot: Slot,
    val label: String? = null,
)
