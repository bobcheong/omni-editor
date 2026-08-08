package com.omnieditor.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders a single line of the editor with gutter (line number) and content.
 *
 * This is a low-level composable used by [EditorContent] in the lazy list.
 * It uses Canvas for text drawing instead of Text composables, which is
 * critical for performance with 500k+ lines — Compose's Text widget
 * performs layout per instance and becomes a bottleneck at scale.
 */
@Composable
fun LineRenderer(
    lineNumber: Long,
    lineText: CharSequence,
    isCaretLine: Boolean,
    gutterWidth: Dp,
    textStyle: TextStyle,
    gutterColor: Color,
    caretLineBackground: Color,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val lineHeight = with(density) { (textStyle.fontSize.value * 1.5f).sp.toPx() }

    Row(modifier = modifier.fillMaxWidth().height(with(density) { lineHeight.toDp() })) {
        // Gutter: line number
        Canvas(modifier = Modifier.width(gutterWidth).height(with(density) { lineHeight.toDp() })) {
            drawGutter(measurer, lineNumber, textStyle, gutterColor, gutterWidth)
        }

        // Content
        Canvas(modifier = Modifier.weight(1f).height(with(density) { lineHeight.toDp() })) {
            if (isCaretLine) {
                drawRect(caretLineBackground)
            }
            drawContent(measurer, lineText, textStyle)
        }
    }
}

private fun DrawScope.drawGutter(
    measurer: TextMeasurer,
    lineNumber: Long,
    style: TextStyle,
    color: Color,
    gutterWidth: Dp,
) {
    val text = (lineNumber + 1).toString()
    val gutterStyle = style.copy(color = color, fontSize = style.fontSize * 0.85f)
    val layoutResult = measurer.measure(text, gutterStyle)
    val x = gutterWidth.toPx() - layoutResult.size.width - 8.dp.toPx()
    val y = (size.height - layoutResult.size.height) / 2f
    drawText(layoutResult, topLeft = Offset(x.coerceAtLeast(4.dp.toPx()), y))
}

private fun DrawScope.drawContent(
    measurer: TextMeasurer,
    text: CharSequence,
    style: TextStyle,
) {
    if (text.isEmpty()) return
    val visibleChars = ((size.width / (style.fontSize.toPx() * 0.6f)).toInt() + 10)
        .coerceAtMost(text.length)
    val displayText = if (visibleChars < text.length) text.substring(0, visibleChars) else text.toString()
    // Ensure color is explicitly set — some devices ignore TextStyle.color in Canvas
    val effectiveStyle = if (style.color == Color.Unspecified) {
        style.copy(color = Color(0xFF000000))
    } else style
    val layoutResult = measurer.measure(displayText, effectiveStyle)
    val y = (size.height - layoutResult.size.height) / 2f
    drawText(layoutResult, topLeft = Offset(8.dp.toPx(), y))
}
