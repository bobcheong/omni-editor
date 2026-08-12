package com.omnieditor.feature.editor

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// ── Caret ────────────────────────────────────────────────────────────────────

/**
 * Draws a blinking caret at the given [rect] position on a Canvas overlay.
 *
 * @param rect       Cursor rectangle from [LineLayoutCache.cursorRect].
 * @param lineTopY   The Y offset of the line containing the caret in the viewport.
 * @param color      Caret color (typically primary).
 * @param caretWidth Width in dp.
 * @param reduceMotion When true (system animation scale == 0), the caret does not blink.
 */
@Composable
fun BlinkingCaret(
    rect: Rect,
    lineTopY: Float,
    color: Color = MaterialTheme.colorScheme.primary,
    caretWidth: Dp = 2.dp,
    reduceMotion: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val alpha: Float = if (reduceMotion) {
        1f
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "caretBlink")
        val animatedAlpha by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 500),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "caretAlpha",
        )
        animatedAlpha
    }

    val widthPx = with(LocalDensity.current) { caretWidth.toPx() }

    Canvas(modifier = modifier) {
        drawRect(
            color = color.copy(alpha = alpha),
            topLeft = Offset(rect.left, lineTopY + rect.top),
            size = Size(widthPx, rect.height),
        )
    }
}

// ── Selection Handle ─────────────────────────────────────────────────────────

/**
 * A teardrop-shaped selection handle with a >= 48dp touch target.
 *
 * @param isStart True for the start handle (teardrop points right),
 *                false for the end handle (teardrop points left).
 * @param position Pixel position of the handle's anchor point.
 * @param onDrag  Called with drag delta during handle movement.
 */
@Composable
fun SelectionHandle(
    isStart: Boolean,
    position: Offset,
    onDrag: (Offset) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    handleSize: Dp = 24.dp,
    touchTarget: Dp = 48.dp,
) {
    val handleSizePx = with(LocalDensity.current) { handleSize.toPx() }
    val touchTargetPx = with(LocalDensity.current) { touchTarget.toPx() }

    // Offset so the handle appears below the selection boundary
    val xOffset = if (isStart) {
        position.x - handleSizePx
    } else {
        position.x
    }

    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    (xOffset - (touchTargetPx - handleSizePx) / 2).roundToInt(),
                    position.y.roundToInt(),
                )
            }
            .size(touchTarget)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(Offset(dragAmount.x, dragAmount.y))
                }
            },
    ) {
        Canvas(
            modifier = Modifier
                .size(handleSize)
                .offset {
                    IntOffset(
                        ((touchTargetPx - handleSizePx) / 2).roundToInt(),
                        0,
                    )
                },
        ) {
            val r = size.minDimension / 2
            val path = Path().apply {
                // Circle at bottom
                addOval(
                    Rect(
                        center = Offset(
                            if (isStart) size.width - r else r,
                            size.height - r,
                        ),
                        radius = r,
                    ),
                )
                // Triangle pointing up to the text
                if (isStart) {
                    moveTo(size.width, size.height - r * 2)
                    lineTo(size.width, 0f)
                    lineTo(size.width - r, size.height - r)
                } else {
                    moveTo(0f, size.height - r * 2)
                    lineTo(0f, 0f)
                    lineTo(r, size.height - r)
                }
                close()
            }
            drawPath(path, color)
        }
    }
}

// ── Floating Action Toolbar ──────────────────────────────────────────────────

/**
 * Floating toolbar shown above the selection with Cut / Copy / Paste / Select All / Share.
 *
 * @param position  Pixel offset for the toolbar (top-left corner).
 * @param onCut     Called when Cut is tapped.
 * @param onCopy    Called when Copy is tapped.
 * @param onPaste   Called when Paste is tapped.
 * @param onSelectAll Called when Select All is tapped.
 * @param onShare   Called when Share is tapped.
 */
@Composable
fun FloatingActionToolbar(
    position: Offset,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) },
        shadowElevation = 4.dp,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Row {
            IconButton(onClick = onCut, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.ContentCut, contentDescription = "Cut", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onPaste, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.ContentPaste, contentDescription = "Paste", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onSelectAll, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.SelectAll, contentDescription = "Select All", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onShare, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ── Selection highlight drawing ──────────────────────────────────────────────

/**
 * Draws a selection highlight rectangle for a single line.
 *
 * @param startX    Left edge of the selection on this line (px).
 * @param endX      Right edge of the selection on this line (px).
 * @param lineTopY  Y offset of the line top in the viewport.
 * @param lineHeight Height of the line.
 * @param color     Selection highlight color.
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSelectionHighlight(
    startX: Float,
    endX: Float,
    lineTopY: Float,
    lineHeight: Float,
    color: Color,
) {
    if (endX <= startX) return
    drawRect(
        color = color,
        topLeft = Offset(startX, lineTopY),
        size = Size(endX - startX, lineHeight),
    )
}
