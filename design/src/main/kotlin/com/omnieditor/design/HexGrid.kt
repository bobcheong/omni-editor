package com.omnieditor.design

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * F-07: Hex grid view for binary/hex display of file content.
 *
 * Shows address column, hex bytes, and ASCII column.
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

    LazyColumn(modifier = modifier) {
        items(rows) { row ->
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 1.dp),
            ) {
                // Address column
                Text(
                    text = if (hexOffsets) "%08X".format(row.offset) else "%08d".format(row.offset),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 16.dp),
                )

                // Hex bytes
                Text(
                    text = row.hexString(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(end = 16.dp),
                )

                // ASCII column
                if (showAscii) {
                    Text(
                        text = row.asciiString(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
