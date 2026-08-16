package com.omnieditor.core.model

import kotlinx.serialization.Serializable

/**
 * F-07: Configuration for the hex/binary view.
 * Bytes per row adapts to screen width (8/16/32 per OE-BIN-2).
 */
@Serializable
data class HexViewConfig(
    val bytesPerRow: Int = 16,
    val showAscii: Boolean = true,
    val offsetBase: OffsetBase = OffsetBase.HEX,
)

@Serializable
enum class OffsetBase { HEX, DECIMAL }
