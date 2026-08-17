package com.omnieditor.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * F-07: Hex grid view for binary/hex display of file content.
 *
 * Shows a pinned address column (like line numbers in the text editor) and
 * horizontally scrollable hex bytes + ASCII columns. All rows scroll together
 * via [HorizontalScrollController] — same pattern as the text editor and
 * compare views.
 *
 * Each text element uses `softWrap = false`, `maxLines = 1`, and
 * `wrapContentWidth(unbounded = true)` so content extends beyond the
 * clipped viewport for scrolling, rather than wrapping at the boundary.
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

    val hScroll = rememberHorizontalScrollController()

    // Measure actual content width from the longest hex+ASCII line
    val textStyle = remember { TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val contentWidthPx = remember(bytesPerRow, showAscii, density) {
        // Measure hex and ASCII parts separately, then add the actual padding
        val hexPart = (0 until bytesPerRow).joinToString(" ") { "FF" }
        val hexWidth = measurer.measure(hexPart, textStyle).size.width.toFloat()
        val asciiWidth = if (showAscii) {
            val asciiText = "X".repeat(bytesPerRow)
            val textWidth = measurer.measure(asciiText, textStyle).size.width.toFloat()
            val paddingPx = with(density) { 16.dp.toPx() }
            paddingPx + textWidth
        } else 0f
        hexWidth + asciiWidth
    }

    var viewportWidthPx by remember { mutableFloatStateOf(0f) }

    LazyColumn(
        modifier = modifier
            .horizontalDocumentScroll(hScroll),
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
                    softWrap = false,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                )

                // Scrollable content area (hex bytes + ASCII)
                // onSizeChanged here measures the Box (viewport), not the full row,
                // so updateBounds gets the correct scrollable area width.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clipToBounds()
                        .onSizeChanged { size ->
                            viewportWidthPx = size.width.toFloat()
                            hScroll.updateBounds(contentWidthPx, viewportWidthPx)
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .wrapContentWidth(Alignment.Start, unbounded = true)
                            .graphicsLayer { translationX = -hScroll.offsetPx },
                    ) {
                        // Hex bytes
                        Text(
                            text = row.hexString(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            softWrap = false,
                            maxLines = 1,
                        )

                        // ASCII column
                        if (showAscii) {
                            Text(
                                text = row.asciiString(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                softWrap = false,
                                maxLines = 1,
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
