package com.omnieditor.feature.editor

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnieditor.design.LocalCompareColors

/**
 * The core editor content area: a lazy list of line renderers (T-13).
 *
 * NOT a TextField. Each line is rendered independently via [LineRenderer],
 * allowing smooth scrolling through 500k+ line files because only visible
 * lines are composed and measured.
 *
 * Key performance characteristics:
 * - Only visible lines are composed (LazyColumn)
 * - Line text is read from PieceTableDocument.line(index) on demand
 * - Canvas-based text drawing avoids Text widget layout overhead
 * - Caret line is highlighted without recomposing other lines
 */
@Composable
fun EditorContent(
    state: EditorState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val listState = rememberLazyListState()
    val compareColors = LocalCompareColors.current
    val textStyle = remember {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }

    // Calculate gutter width based on line count
    val gutterWidth by remember(state.lineCount) {
        derivedStateOf {
            val digits = state.lineCount.toString().length
            (digits * 10 + 24).dp
        }
    }

    // Sync scroll position from state
    LaunchedEffect(state.firstVisibleLine) {
        if (state.firstVisibleLine >= 0 && state.firstVisibleLine < state.lineCount) {
            listState.scrollToItem(state.firstVisibleLine.toInt())
        }
    }

    // Update state from scroll position
    LaunchedEffect(listState.firstVisibleItemIndex) {
        state.firstVisibleLine = listState.firstVisibleItemIndex.toLong()
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        // Calculate which line was tapped
                        val lineHeight = textStyle.fontSize.value * 1.5f * density
                        val tappedIndex = listState.firstVisibleItemIndex +
                            (offset.y / lineHeight).toInt()
                        if (tappedIndex in 0 until state.lineCount.toInt()) {
                            state.moveCaret(tappedIndex.toLong(), estimateColumn(offset.x, gutterWidth.value * density, textStyle))
                        }
                    }
                },
        ) {
            items(
                count = state.lineCount.toInt(),
                key = { it },
            ) { index ->
                val lineText = remember(index, state.document.dirty) {
                    state.document.line(index.toLong())
                }

                LineRenderer(
                    lineNumber = index.toLong(),
                    lineText = lineText,
                    isCaretLine = index.toLong() == state.caretLine,
                    gutterWidth = gutterWidth,
                    textStyle = textStyle,
                    gutterColor = compareColors.gutter,
                    caretLineBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Estimate the column from a tap x position.
 * Uses a fixed-width character approximation for monospace fonts.
 */
private fun estimateColumn(x: Float, gutterWidthPx: Float, textStyle: TextStyle): Int {
    val contentX = x - gutterWidthPx - 8f // 8dp padding
    if (contentX <= 0) return 0
    val charWidth = textStyle.fontSize.value * 0.6f // approximate monospace char width
    return (contentX / charWidth).toInt()
}
