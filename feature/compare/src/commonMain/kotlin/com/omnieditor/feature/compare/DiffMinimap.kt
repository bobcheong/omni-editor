package com.omnieditor.feature.compare

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.omnieditor.core.model.Hunk
import com.omnieditor.core.model.HunkType
import com.omnieditor.design.LocalCompareColors

/**
 * Minimap rail on the right edge of the compare view (OE-TXT-3).
 *
 * Full-height, 6dp wide, showing difference density.
 * Draggable to seek; tap to jump.
 * Proportionally maps file lines to screen height.
 */
@Composable
fun DiffMinimap(
    hunks: List<Hunk>,
    totalLines: Long,
    visibleStart: Long,
    visibleEnd: Long,
    onSeek: (fraction: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCompareColors.current

    val hunkPositions = remember(hunks, totalLines) {
        if (totalLines <= 0) emptyList()
        else hunks.map { hunk ->
            MinimapHunk(
                startFraction = hunk.leftStart.toFloat() / totalLines,
                endFraction = hunk.leftEnd.toFloat() / totalLines,
                type = hunk.type,
            )
        }
    }

    Canvas(
        modifier = modifier
            .width(6.dp)
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onSeek(offset.y / size.height)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onSeek(change.position.y / size.height)
                }
            },
    ) {
        val w = size.width
        val h = size.height

        // Background
        drawRect(Color.Gray.copy(alpha = 0.15f))

        // Draw hunk markers
        for (hunk in hunkPositions) {
            val y = hunk.startFraction * h
            val markerH = maxOf((hunk.endFraction - hunk.startFraction) * h, 2f)
            val color = when (hunk.type) {
                HunkType.ADDED -> colors.addedFg
                HunkType.REMOVED -> colors.removedFg
                HunkType.CHANGED -> colors.changedFg
                HunkType.CONFLICT -> colors.conflictFg
            }
            drawRect(color, topLeft = Offset(0f, y), size = Size(w, markerH))
        }

        // Viewport indicator
        if (totalLines > 0) {
            val vpTop = (visibleStart.toFloat() / totalLines) * h
            val vpBot = (visibleEnd.toFloat() / totalLines) * h
            val vpH = maxOf(vpBot - vpTop, 8f)
            drawRect(
                Color.White.copy(alpha = 0.4f),
                topLeft = Offset(0f, vpTop),
                size = Size(w, vpH),
            )
        }
    }
}

private data class MinimapHunk(
    val startFraction: Float,
    val endFraction: Float,
    val type: HunkType,
)
