package com.omnieditor.feature.editor

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.semantics.textSelectionRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnieditor.core.diff.syntax.SyntaxEngine
import com.omnieditor.core.diff.syntax.SyntaxToken
import com.omnieditor.core.diff.syntax.TokenType
import com.omnieditor.core.model.DisplaySettings
import com.omnieditor.design.LocalCompareColors
import com.omnieditor.design.LocalSyntaxColors
import com.omnieditor.design.SyntaxColorScheme
import kotlinx.coroutines.flow.distinctUntilChanged

/** Byte threshold above which a line is considered "long" and shown truncated. */
private const val TRUNCATION_CHAR_THRESHOLD = 2_000

@Composable
fun EditorContent(
    state: EditorState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    fileName: String = "",
    displaySettings: DisplaySettings = DisplaySettings(),
) {
    val listState = rememberLazyListState()
    val compareColors = LocalCompareColors.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val whitespaceColor = onSurface.copy(alpha = 0.35f)
    val horizontalScrollState = rememberScrollState()
    val clipboardManager = LocalClipboardManager.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val density = LocalDensity.current

    // Detect reduced motion for caret blink
    val context = LocalContext.current
    val reduceMotion = remember {
        try {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
            scale == 0f
        } catch (_: Exception) {
            false
        }
    }

    // Text measurer and layout cache for caret/selection positioning
    val textStyle = remember {
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
    }
    val textMeasurer = rememberTextMeasurer()
    val layoutCache = remember(textMeasurer, textStyle) {
        LineLayoutCache(textMeasurer, textStyle)
    }

    // Detect language for syntax highlighting
    val language = remember(fileName) { SyntaxEngine.detectLanguage(fileName) }
    val grammar = remember(language) { SyntaxEngine.grammarFor(language) }

    // Hoist syntax colours — resolved once per theme change, not per recomposition (R-19).
    val syntaxColors = LocalSyntaxColors.current

    // Stateful highlighter — carries lexer entry state across lines (R-19).
    val highlighter = remember(grammar) {
        grammar?.let { StatefulSyntaxHighlighter(it) }
    }

    val lastInvalidateLine = remember { mutableStateOf(0L) }
    LaunchedEffect(state.document) {
        state.document.changes.collect { change ->
            val startLine = change.startLine
            if (startLine < lastInvalidateLine.value || lastInvalidateLine.value == 0L) {
                lastInvalidateLine.value = startLine
            }
            highlighter?.invalidateFrom(startLine.toInt())
            highlighter?.notifyLineCountChanged(state.lineCount.toInt())
        }
    }

    val gutterWidth by remember(state.lineCount) {
        derivedStateOf {
            val digits = state.lineCount.toString().length
            (digits * 10 + 24).dp
        }
    }

    // Container size for layout constraints
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val gutterWidthPx = with(density) { gutterWidth.toPx() }

    // Floating toolbar state
    var showToolbar by remember { mutableStateOf(false) }
    var toolbarPosition by remember { mutableStateOf(Offset.Zero) }

    // Show/hide toolbar when selection changes
    LaunchedEffect(state.hasSelection) {
        showToolbar = state.hasSelection
    }

    // ── Scroll management ──────────────────────────────────────────────────
    val pendingScrollCommand = remember { mutableStateOf<Long?>(null) }
    val lastListFirstVisible = remember { mutableStateOf(listState.firstVisibleItemIndex.toLong()) }

    LaunchedEffect(state.firstVisibleLine) {
        val target = state.firstVisibleLine
        if (target != lastListFirstVisible.value) {
            pendingScrollCommand.value = target
        }
    }

    LaunchedEffect(pendingScrollCommand.value) {
        val target = pendingScrollCommand.value ?: return@LaunchedEffect
        if (target >= 0 && target < state.lineCount) {
            listState.scrollToItem(target.toInt())
            lastListFirstVisible.value = target
        }
        pendingScrollCommand.value = null
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                val line = index.toLong()
                lastListFirstVisible.value = line
                state.firstVisibleLine = line
            }
    }

    // ── Semantics for the editable text area ─────────────────────────────
    // Provide accessibility information for TalkBack.
    val semanticsText = remember(state.caretLine, state.document.editGeneration) {
        try {
            AnnotatedString(state.document.line(state.caretLine).toString())
        } catch (_: Exception) {
            AnnotatedString("")
        }
    }
    val semanticsSelectionRange = remember(state.caretColumn, state.selectionAnchorColumn) {
        val anchor = state.selectionAnchorColumn ?: state.caretColumn
        TextRange(anchor, state.caretColumn)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { containerSize = it.size }
            .semantics {
                editableText = semanticsText
                textSelectionRange = semanticsSelectionRange
                setText { text ->
                    state.insertAtCaret(text.text)
                    true
                }
            },
    ) {
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state) {
                    detectTapGestures(
                        onTap = { offset ->
                            // Convert tap position to line and column
                            val lineIndex = resolveLineFromY(
                                offset.y, listState, state,
                            )
                            if (lineIndex >= 0 && lineIndex < state.lineCount) {
                                val lineText = state.document.line(lineIndex).toString()
                                val xInContent = (offset.x - gutterWidthPx).coerceAtLeast(0f)
                                val col = resolveColumnFromX(
                                    lineText, xInContent, layoutCache, containerSize, textStyle,
                                )
                                state.moveCaret(lineIndex, col)
                                showToolbar = false
                            }
                        },
                        onDoubleTap = { offset ->
                            val lineIndex = resolveLineFromY(
                                offset.y, listState, state,
                            )
                            if (lineIndex >= 0 && lineIndex < state.lineCount) {
                                val lineText = state.document.line(lineIndex).toString()
                                val xInContent = (offset.x - gutterWidthPx).coerceAtLeast(0f)
                                val col = resolveColumnFromX(
                                    lineText, xInContent, layoutCache, containerSize, textStyle,
                                )
                                state.selectWordAt(lineIndex, col)
                                showToolbar = true
                                toolbarPosition = Offset(offset.x, offset.y - 48f)
                            }
                        },
                        onLongPress = { offset ->
                            val lineIndex = resolveLineFromY(
                                offset.y, listState, state,
                            )
                            if (lineIndex >= 0 && lineIndex < state.lineCount) {
                                val lineText = state.document.line(lineIndex).toString()
                                val xInContent = (offset.x - gutterWidthPx).coerceAtLeast(0f)
                                val col = resolveColumnFromX(
                                    lineText, xInContent, layoutCache, containerSize, textStyle,
                                )
                                state.selectWordAt(lineIndex, col)
                                showToolbar = true
                                toolbarPosition = Offset(offset.x, offset.y - 48f)
                            }
                        },
                    )
                }
                .pointerInput(state) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val lineIndex = resolveLineFromY(
                                offset.y, listState, state,
                            )
                            if (lineIndex >= 0 && lineIndex < state.lineCount) {
                                val lineText = state.document.line(lineIndex).toString()
                                val xInContent = (offset.x - gutterWidthPx).coerceAtLeast(0f)
                                val col = resolveColumnFromX(
                                    lineText, xInContent, layoutCache, containerSize, textStyle,
                                )
                                state.moveCaret(lineIndex, col)
                                state.startSelection()
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val pos = change.position
                            val lineIndex = resolveLineFromY(
                                pos.y, listState, state,
                            )
                            if (lineIndex >= 0 && lineIndex < state.lineCount) {
                                val lineText = state.document.line(lineIndex).toString()
                                val xInContent = (pos.x - gutterWidthPx).coerceAtLeast(0f)
                                val col = resolveColumnFromX(
                                    lineText, xInContent, layoutCache, containerSize, textStyle,
                                )
                                state.moveCaretWithSelection(lineIndex, col)
                            }
                        },
                        onDragEnd = {
                            showToolbar = state.hasSelection
                        },
                    )
                },
        ) {
            items(
                count = state.lineCount.toInt(),
                key = { it },
            ) { index ->
                val editGen = state.document.editGeneration
                val lineText = remember(index, editGen) {
                    state.document.line(index.toLong()).toString()
                }
                val isCaretLine = index.toLong() == state.caretLine

                val isExpanded = remember(index) { mutableStateOf(false) }

                val isLongLine = lineText.length > TRUNCATION_CHAR_THRESHOLD
                val displayText = if (isLongLine && !isExpanded.value) {
                    lineText.take(TRUNCATION_CHAR_THRESHOLD)
                } else {
                    lineText
                }

                val showWs = displaySettings.showWhitespace

                val annotated: AnnotatedString = remember(displayText, grammar, showWs, syntaxColors) {
                    when {
                        highlighter != null && showWs -> highlightLineWithWhitespace(
                            original = displayText,
                            tokens = highlighter.tokenizeLine(index, displayText),
                            colors = syntaxColors,
                            defaultColor = onSurface,
                            whitespaceColor = whitespaceColor,
                        )
                        highlighter != null -> highlightLine(
                            text = displayText,
                            tokens = highlighter.tokenizeLine(index, displayText),
                            colors = syntaxColors,
                            defaultColor = onSurface,
                        )
                        grammar != null && showWs -> highlightLineWithWhitespace(
                            original = displayText,
                            tokens = SyntaxEngine.tokenizeLine(displayText, grammar),
                            colors = syntaxColors,
                            defaultColor = onSurface,
                            whitespaceColor = whitespaceColor,
                        )
                        grammar != null -> highlightLine(
                            text = displayText,
                            tokens = SyntaxEngine.tokenizeLine(displayText, grammar),
                            colors = syntaxColors,
                            defaultColor = onSurface,
                        )
                        showWs -> buildAnnotatedString {
                            for (ch in displayText) {
                                when (ch) {
                                    ' ' -> withStyle(SpanStyle(color = whitespaceColor)) { append('\u00B7') }
                                    '\t' -> withStyle(SpanStyle(color = whitespaceColor)) { append('\u2192') }
                                    else -> withStyle(SpanStyle(color = onSurface)) { append(ch) }
                                }
                            }
                        }
                        else -> AnnotatedString(displayText)
                    }
                }

                // Selection bounds for this line
                val selBounds = state.selectionBounds()
                val lineIdx = index.toLong()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isCaretLine) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            else Color.Transparent,
                        )
                        .drawWithContent {
                            drawContent()

                            // Draw selection highlight on this line
                            if (selBounds != null &&
                                lineIdx >= selBounds.startLine &&
                                lineIdx <= selBounds.endLine
                            ) {
                                val layout = layoutCache.measure(
                                    lineIndex = lineIdx,
                                    lineText = expandTabs(displayText, 4),
                                    constraints = Constraints(
                                        maxWidth = (size.width - gutterWidthPx)
                                            .toInt()
                                            .coerceAtLeast(1),
                                    ),
                                )
                                val lh = layoutCache.lineHeight(layout)

                                val selStart = when {
                                    lineIdx > selBounds.startLine -> 0
                                    else -> selBounds.startColumn
                                }
                                val selEnd = when {
                                    lineIdx < selBounds.endLine -> displayText.length
                                    else -> selBounds.endColumn.coerceAtMost(displayText.length)
                                }

                                if (selEnd > selStart) {
                                    val startRect = layoutCache.cursorRect(layout, selStart)
                                    val endRect = layoutCache.cursorRect(layout, selEnd)
                                    drawSelectionHighlight(
                                        startX = gutterWidthPx + startRect.left,
                                        endX = gutterWidthPx + endRect.left,
                                        lineTopY = 0f,
                                        lineHeight = lh,
                                        color = selectionColor,
                                    )
                                }
                            }

                            // Draw caret on this line
                            if (isCaretLine) {
                                val layout = layoutCache.measure(
                                    lineIndex = lineIdx,
                                    lineText = expandTabs(displayText, 4),
                                    constraints = Constraints(
                                        maxWidth = (size.width - gutterWidthPx)
                                            .toInt()
                                            .coerceAtLeast(1),
                                    ),
                                )
                                val caretCol = state.caretColumn.coerceAtMost(displayText.length)
                                val caretRect = layoutCache.cursorRect(layout, caretCol)
                                val caretWidthPx = with(density) { 2.dp.toPx() }
                                val lh = layoutCache.lineHeight(layout)

                                // The caret alpha is handled by BlinkingCaret composable
                                // but for the drawWithContent approach we draw directly
                                drawRect(
                                    color = primaryColor,
                                    topLeft = Offset(gutterWidthPx + caretRect.left, caretRect.top),
                                    size = Size(caretWidthPx, lh),
                                )
                            }
                        },
                ) {
                    // Gutter
                    Text(
                        text = "${index + 1}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = compareColors.gutter,
                        modifier = Modifier
                            .width(gutterWidth)
                            .padding(end = 8.dp, top = 2.dp, bottom = 2.dp),
                        maxLines = 1,
                    )

                    // Content
                    if (displaySettings.wordWrap) {
                        Text(
                            text = annotated,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = onSurface,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 2.dp),
                            softWrap = true,
                        )
                    } else {
                        Text(
                            text = annotated,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = onSurface,
                            modifier = Modifier
                                .horizontalScroll(horizontalScrollState)
                                .padding(vertical = 2.dp),
                            softWrap = false,
                            maxLines = 1,
                        )
                    }

                    // Long-line expand affordance
                    if (isLongLine && !isExpanded.value && displaySettings.truncateLongLines) {
                        Text(
                            text = "\u2026expand",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .clickable { isExpanded.value = true },
                        )
                    }
                }
            }
        }

        // ── Selection handles overlay ────────────────────────────────────
        if (state.hasSelection) {
            val bounds = state.selectionBounds()
            if (bounds != null) {
                // Start handle
                SelectionHandle(
                    isStart = true,
                    position = Offset(gutterWidthPx, 0f), // Simplified position
                    onDrag = { delta ->
                        // Extend selection start
                        val newCol = (bounds.startColumn + (delta.x / 8f).toInt())
                            .coerceAtLeast(0)
                        state.setSelection(
                            bounds.startLine, newCol,
                            state.caretLine, state.caretColumn,
                        )
                    },
                )
                // End handle
                SelectionHandle(
                    isStart = false,
                    position = Offset(gutterWidthPx + 100f, 0f), // Simplified position
                    onDrag = { delta ->
                        val newCol = (bounds.endColumn + (delta.x / 8f).toInt())
                            .coerceAtLeast(0)
                        state.setSelection(
                            state.selectionAnchorLine ?: bounds.startLine,
                            state.selectionAnchorColumn ?: bounds.startColumn,
                            bounds.endLine, newCol,
                        )
                    },
                )
            }
        }

        // ── Floating toolbar ─────────────────────────────────────────────
        if (showToolbar && state.hasSelection) {
            FloatingActionToolbar(
                position = toolbarPosition.copy(
                    y = (toolbarPosition.y - 48f).coerceAtLeast(0f),
                ),
                onCut = {
                    clipboardManager.setText(AnnotatedString(state.selectedText()))
                    state.deleteSelection()
                    showToolbar = false
                },
                onCopy = {
                    clipboardManager.setText(AnnotatedString(state.selectedText()))
                    showToolbar = false
                },
                onPaste = {
                    val clip = clipboardManager.getText()?.text ?: ""
                    if (clip.isNotEmpty()) {
                        state.insertAtCaret(clip)
                    }
                    showToolbar = false
                },
                onSelectAll = {
                    val lastLine = state.lineCount - 1
                    val lastLineText = state.document.line(lastLine).toString()
                    state.setSelection(0L, 0, lastLine, lastLineText.length)
                },
                onShare = {
                    // Share is a platform action — in a real app this would fire an intent.
                    // For now, copy to clipboard as a fallback.
                    clipboardManager.setText(AnnotatedString(state.selectedText()))
                    showToolbar = false
                },
            )
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Resolve a line index from a Y position within the LazyColumn viewport.
 * Uses the list state's layout info to find which item the Y falls in.
 */
private fun resolveLineFromY(
    y: Float,
    listState: androidx.compose.foundation.lazy.LazyListState,
    state: EditorState,
): Long {
    val layoutInfo = listState.layoutInfo
    for (item in layoutInfo.visibleItemsInfo) {
        val top = item.offset.toFloat()
        val bottom = top + item.size
        if (y in top..bottom) {
            return item.index.toLong()
        }
    }
    // If beyond visible items, clamp to first or last
    val firstVisible = layoutInfo.visibleItemsInfo.firstOrNull()
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
    return when {
        firstVisible != null && y < firstVisible.offset -> firstVisible.index.toLong()
        lastVisible != null -> lastVisible.index.toLong()
        else -> state.caretLine
    }
}

/**
 * Resolve a column index from an X position within the text content area.
 */
private fun resolveColumnFromX(
    lineText: String,
    xInContent: Float,
    layoutCache: LineLayoutCache,
    containerSize: IntSize,
    textStyle: TextStyle,
): Int {
    if (lineText.isEmpty()) return 0
    val expandedText = expandTabs(lineText, 4)
    val layout = layoutCache.measure(
        lineIndex = 0, // Line index doesn't matter for offset calculation
        lineText = expandedText,
        constraints = Constraints(maxWidth = containerSize.width.coerceAtLeast(1)),
        editGeneration = -1, // Use special key so this doesn't pollute the main cache
    )
    val displayOffset = layoutCache.offsetForPosition(layout, Offset(xInContent, 0f))
    // Map display offset back to character offset in original text
    val displayToChar = buildDisplayToCharMap(lineText, 4)
    return if (displayOffset < displayToChar.size) {
        displayToChar[displayOffset]
    } else {
        lineText.length
    }
}

private fun highlightLine(
    text: String,
    tokens: List<SyntaxToken>,
    colors: SyntaxColorScheme,
    defaultColor: Color,
): AnnotatedString {
    if (tokens.isEmpty()) return AnnotatedString(text)

    return buildAnnotatedString {
        var pos = 0
        for (token in tokens) {
            if (token.start > pos) {
                withStyle(SpanStyle(color = defaultColor)) {
                    append(text.substring(pos, token.start))
                }
            }
            val color = tokenColor(token.type, colors)
            val end = (token.start + token.length).coerceAtMost(text.length)
            withStyle(SpanStyle(color = color)) {
                append(text.substring(token.start, end))
            }
            pos = end
        }
        if (pos < text.length) {
            withStyle(SpanStyle(color = defaultColor)) {
                append(text.substring(pos))
            }
        }
    }
}

private fun highlightLineWithWhitespace(
    original: String,
    tokens: List<SyntaxToken>,
    colors: SyntaxColorScheme,
    defaultColor: Color,
    whitespaceColor: Color,
): AnnotatedString {
    return buildAnnotatedString {
        var pos = 0

        fun appendWithWhitespace(text: String, color: Color) {
            for (ch in text) {
                when (ch) {
                    ' ' -> withStyle(SpanStyle(color = whitespaceColor)) { append('\u00B7') }
                    '\t' -> withStyle(SpanStyle(color = whitespaceColor)) { append('\u2192') }
                    else -> withStyle(SpanStyle(color = color)) { append(ch) }
                }
            }
        }

        for (token in tokens) {
            if (token.start > pos) {
                appendWithWhitespace(original.substring(pos, token.start), defaultColor)
            }
            val color = tokenColor(token.type, colors)
            val end = (token.start + token.length).coerceAtMost(original.length)
            appendWithWhitespace(original.substring(token.start, end), color)
            pos = end
        }
        if (pos < original.length) {
            appendWithWhitespace(original.substring(pos), defaultColor)
        }
    }
}

private fun tokenColor(type: TokenType, colors: SyntaxColorScheme): Color =
    when (type) {
        TokenType.KEYWORD -> colors.keyword
        TokenType.TYPE -> colors.type
        TokenType.STRING -> colors.string
        TokenType.NUMBER -> colors.number
        TokenType.COMMENT -> colors.comment
        TokenType.ANNOTATION -> colors.annotation
        TokenType.TAG -> colors.tag
        TokenType.ATTRIBUTE -> colors.attribute
        TokenType.HEADING -> colors.heading
        TokenType.OPERATOR -> colors.operator
        TokenType.PUNCTUATION -> colors.punctuation
        TokenType.CONSTANT -> colors.constant
        TokenType.FUNCTION -> colors.function
    }
