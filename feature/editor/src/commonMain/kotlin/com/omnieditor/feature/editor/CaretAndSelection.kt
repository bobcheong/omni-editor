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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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
 * The caller positions the handle so its tip touches the selection boundary
 * ([position] is the boundary point: x at the caret boundary, y at the bottom
 * of the boundary's visual row) and receives drag callbacks to move that
 * boundary. Drag *start/end* callbacks let the caller capture the fixed
 * opposite boundary once per gesture, so dragging one handle never disturbs
 * the other end of the selection (R-50).
 *
 * @param isStart True for the start handle (teardrop points right),
 *                false for the end handle (teardrop points left).
 * @param position Pixel position of the selection boundary this handle marks.
 * @param onDragStart Called once when a handle drag begins.
 * @param onDrag  Called with drag delta during handle movement.
 * @param onDragEnd Called when the drag gesture finishes or cancels.
 */
@Composable
fun SelectionHandle(
    isStart: Boolean,
    position: Offset,
    onDrag: (Offset) -> Unit,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    color: Color = MaterialTheme.colorScheme.primary,
    handleSize: Dp = 24.dp,
    touchTarget: Dp = 48.dp,
) {
    val handleSizePx = with(LocalDensity.current) { handleSize.toPx() }
    val touchTargetPx = with(LocalDensity.current) { touchTarget.toPx() }

    // The teardrop's tip is at the top corner of the drawn glyph: the right
    // edge for the start handle, the left edge for the end handle. Offset the
    // touch-target box so that tip lands exactly on [position].
    val glyphInset = (touchTargetPx - handleSizePx) / 2f
    val tipXInBox = if (isStart) glyphInset + handleSizePx else glyphInset

    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    (position.x - tipXInBox).roundToInt(),
                    (position.y - glyphInset).roundToInt(),
                )
            }
            .size(touchTarget)
            .pointerInput(isStart) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(Offset(dragAmount.x, dragAmount.y))
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                )
            },
    ) {
        Canvas(
            modifier = Modifier
                .size(handleSize)
                .offset {
                    IntOffset(glyphInset.roundToInt(), glyphInset.roundToInt())
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
 * Floating edit toolbar anchored to the selection (R-50).
 *
 * Positioning is delegated to a [Popup] with a custom [PopupPositionProvider]:
 * the popup measures its own content, prefers the space *above* the anchor
 * rectangle (so it never covers the selected text), flips below when there is
 * no room above, and clamps horizontally and vertically inside the window —
 * it can never render partially off screen.
 *
 * Primary row: Cut · Copy · Paste · Select all · ⋮. The overflow menu holds
 * the full editing set: Select line, Delete, Duplicate, UPPERCASE, lowercase,
 * Share.
 *
 * @param anchorRect Selection anchor area in the parent container's
 *                   coordinates. The toolbar avoids this rectangle.
 * @param hasSelection Whether a selection is active — selection-dependent
 *                   actions are disabled at the caret.
 */
@Suppress("LongParameterList")
@Composable
fun FloatingActionToolbar(
    anchorRect: Rect,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
    onSelectLine: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onUpperCase: () -> Unit,
    onLowerCase: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    hasSelection: Boolean = true,
) {
    val density = LocalDensity.current
    val marginPx = with(density) { 8.dp.roundToPx() }

    val positionProvider = remember(marginPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val maxX = (windowSize.width - popupContentSize.width - marginPx)
                    .coerceAtLeast(marginPx)
                val x = anchorBounds.left.coerceIn(marginPx, maxX)

                val above = anchorBounds.top - popupContentSize.height - marginPx
                val maxY = (windowSize.height - popupContentSize.height - marginPx)
                    .coerceAtLeast(marginPx)
                val y = if (above >= marginPx) {
                    above
                } else {
                    (anchorBounds.bottom + marginPx).coerceAtMost(maxY)
                }
                return IntOffset(x, y)
            }
        }
    }

    // Invisible, non-interactive anchor box covering the selection area. The
    // popup's anchorBounds are this box's window bounds. A plain Box with no
    // pointer input does not intercept touches over the selection.
    Box(
        modifier = modifier
            .offset {
                IntOffset(anchorRect.left.roundToInt(), anchorRect.top.roundToInt())
            }
            .size(
                width = with(density) { anchorRect.width.coerceAtLeast(1f).toDp() },
                height = with(density) { anchorRect.height.coerceAtLeast(1f).toDp() },
            ),
    ) {
        Popup(
            popupPositionProvider = positionProvider,
            properties = PopupProperties(focusable = false),
        ) {
            var overflowOpen by remember { mutableStateOf(false) }

            Surface(
                shadowElevation = 4.dp,
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ToolbarIcon(
                        onClick = onCut,
                        enabled = hasSelection,
                        icon = { Icon(Icons.Default.ContentCut, contentDescription = "Cut", modifier = Modifier.size(20.dp)) },
                    )
                    ToolbarIcon(
                        onClick = onCopy,
                        enabled = hasSelection,
                        icon = { Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(20.dp)) },
                    )
                    ToolbarIcon(
                        onClick = onPaste,
                        enabled = true,
                        icon = { Icon(Icons.Default.ContentPaste, contentDescription = "Paste", modifier = Modifier.size(20.dp)) },
                    )
                    ToolbarIcon(
                        onClick = onSelectAll,
                        enabled = true,
                        icon = { Icon(Icons.Default.SelectAll, contentDescription = "Select All", modifier = Modifier.size(20.dp)) },
                    )

                    // Overflow: the full editing set lives in a submenu so the
                    // primary row stays narrow enough for phone widths.
                    Box {
                        ToolbarIcon(
                            onClick = { overflowOpen = true },
                            enabled = true,
                            icon = { Icon(Icons.Default.MoreVert, contentDescription = "More editing options", modifier = Modifier.size(20.dp)) },
                        )
                        DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Select line") },
                                onClick = { overflowOpen = false; onSelectLine() },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                enabled = hasSelection,
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = { overflowOpen = false; onDelete() },
                            )
                            DropdownMenuItem(
                                text = { Text("Duplicate") },
                                onClick = { overflowOpen = false; onDuplicate() },
                            )
                            DropdownMenuItem(
                                text = { Text("UPPERCASE") },
                                enabled = hasSelection,
                                onClick = { overflowOpen = false; onUpperCase() },
                            )
                            DropdownMenuItem(
                                text = { Text("lowercase") },
                                enabled = hasSelection,
                                onClick = { overflowOpen = false; onLowerCase() },
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                enabled = hasSelection,
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = { overflowOpen = false; onShare() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarIcon(
    onClick: () -> Unit,
    enabled: Boolean,
    icon: @Composable () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        icon()
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
