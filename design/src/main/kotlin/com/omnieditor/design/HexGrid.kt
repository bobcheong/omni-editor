package com.omnieditor.design

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * F-07: Hex grid view for binary/hex display of file content.
 *
 * Shows a pinned address column (like line numbers in the text editor) and
 * horizontally scrollable hex bytes + ASCII columns. All rows scroll together
 * via [HorizontalScrollController] (same pattern as the text editor).
 *
 * Read-only. Bytes per row adapts to width (8/16/32).
 * Shared component — F-18 composes two of these for binary compare.
 */
@Composable
fun HexGrid(
    bytes: ByteArray,
    bytesPerRow: Int = 16,
    showAscii: Boolean = true,
    hexOffsets: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val rows = remember(bytes, bytesPerRow) {
        bytes.toList().chunked(bytesPerRow).mapIndexed { index, chunk ->
            HexRow(
                offset = index * bytesPerRow,
                bytes = chunk.toByteArray(),
                bytesPerRow = bytesPerRow,
            )
        }
    }

    val hScroll = remember { HorizontalScrollController() }

    // Estimate content width: hex string is (bytesPerRow * 3 - 1) chars + gap + ASCII (bytesPerRow chars)
    // At ~7.2px per monospace char at 12sp, approximate the width
    val charWidthEstimate = 7.2f
    val hexChars = bytesPerRow * 3 - 1
    val asciiChars = if (showAscii) bytesPerRow else 0
    val gapChars = if (showAscii) 2 else 0
    val totalContentChars = hexChars + gapChars + asciiChars
    val contentWidthPx = totalContentChars * charWidthEstimate

    val scrollableState = rememberScrollableState { delta ->
        hScroll.dispatchDelta(-delta)
        delta
    }

    LazyColumn(
        modifier = modifier
            .scrollable(scrollableState, Orientation.Horizontal)
            .onSizeChanged { size ->
                hScroll.updateBounds(contentWidthPx, size.width.toFloat())
            },
    ) {
        items(rows) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp),
            ) {
                // Pinned address/offset column (stays visible during horizontal scroll)
                Text(
                    text = if (hexOffsets) "%08X".format(row.offset) else "%08d".format(row.offset),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                )

                // Scrollable content area (hex bytes + ASCII)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clipToBounds(),
                ) {
                    Row(
                        modifier = Modifier.graphicsLayer {
                            translationX = -hScroll.offsetPx
                        },
                    ) {
                        // Hex bytes
                        Text(
                            text = row.hexString(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )

                        // ASCII column
                        if (showAscii) {
                            Text(
                                text = row.asciiString(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class HexRow(
    val offset: Int,
    val bytes: ByteArray,
    val bytesPerRow: Int,
) {
    fun hexString(): String = bytes.joinToString(" ") { "%02X".format(it) }
        .padEnd(bytesPerRow * 3 - 1)

    fun asciiString(): String = String(bytes.map { b ->
        if (b in 0x20..0x7E) b.toInt().toChar() else '.'
    }.toCharArray())
}
