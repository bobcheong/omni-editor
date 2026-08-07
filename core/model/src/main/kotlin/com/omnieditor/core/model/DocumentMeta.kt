package com.omnieditor.core.model

import kotlinx.serialization.Serializable

/**
 * Serialisable metadata for an open document (M5, OE-EDT-2).
 * The piece table and undo stack are runtime state — this is the part
 * that survives process death and is shown in the tab strip.
 */
@Serializable
data class DocumentMeta(
    val id: String,
    val sourceRefId: String,
    val dirty: Boolean = false,
    val caretLine: Long = 0,
    val caretColumn: Int = 0,
    val scrollOffset: Long = 0,
    val encoding: String = "UTF-8",
    val lineEnding: LineEnding = LineEnding.LF,
    val readOnly: Boolean = false,
    val language: String? = null,
)

@Serializable
enum class LineEnding(val chars: String) {
    LF("\n"),
    CRLF("\r\n"),
    CR("\r"),
}
