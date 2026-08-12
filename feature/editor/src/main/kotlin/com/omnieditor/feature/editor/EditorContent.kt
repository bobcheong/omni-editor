package com.omnieditor.feature.editor

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
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
import androidx.compose.ui.text.style.TextDecoration
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
import com.omnieditor.design.LocalReduceMotion
import com.omnieditor.design.LocalSyntaxColors
import com.omnieditor.design.SyntaxColorScheme
import com.omnieditor.design.horizontalDocumentScroll
import com.omnieditor.design.rememberHorizontalScrollController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.yield

/** Byte threshold above which a line is considered "long" and shown truncated. */
private const val TRUNCATION_CHAR_THRESHOLD = 2_000

/** Lines scanned per cooperative-yield chunk during the initial max-width scan. */
private const val WIDTH_SCAN_CHUNK = 2_048L

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun EditorContent(
    state: EditorState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    fileName: String = "",
    displaySettings: DisplaySettings = DisplaySettings(),
    /**
     * Invoked when the user taps the editing surface. EditorScreen wires this
     * to focus the IME bridge and show the soft keyboard, so the keyboard can
     * always be re-summoned after the user dismisses it (R-41).
     */
    onRequestIme: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val compareColors = LocalCompareColors.current
    val reduceMotion = LocalReduceMotion.current
    // Caret blink animation — suppressed when system reduce-motion is on (R-37, NFR-A1).
    val caretAlpha: Float = if (reduceMotion) {
        1f
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "caretBlink")
        val animatedAlpha by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 530),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "caretAlpha",
        )
        animatedAlpha
    }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val whitespaceColor = onSurface.copy(alpha = 0.35f)
    val clipboardManager = LocalClipboardManager.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val density = LocalDensity.current

    // Text measurer and layout cache for caret/selection positioning
    val textStyle = remember {
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
    }
    val textMeasurer = rememberTextMeasurer()
    val layoutCache = remember(textMeasurer, textStyle) {
        LineLayoutCache(textMeasurer, textStyle)
    }

    // ── Horizontal document scroll (R-43) ─────────────────────────────────
    // One offset for the whole document. Rows translate by -offset inside a
    // clipped content area; the gutter stays pinned. Bounds derive from the
    // widest display line (monospace → exact from column count × glyph width).
    val hScroll = rememberHorizontalScrollController()
    val charWidthPx = remember(textMeasurer, textStyle) {
        textMeasurer.measure(AnnotatedString("0"), style = textStyle).size.width.toFloat()
    }
    val maxDisplayCols = remember(state.document) { mutableIntStateOf(0) }

    // Initial full scan of the widest line, chunked with cooperative yields to
    // keep the main thread responsive. Runs once per loaded document; edits
    // update the running max via the change collector below. Known limitation
    // (documented in R-43): deleting the longest line does not shrink the max
    // until reload — harmless, it only leaves extra scroll room.
    LaunchedEffect(state.document) {
        var m = 0
        var i = 0L
        val n = state.document.lineCount
        while (i < n) {
            val len = displayLength(state.document.line(i))
            if (len > m) m = len
            i++
            if (i % WIDTH_SCAN_CHUNK == 0L) yield()
        }
        if (m > maxDisplayCols.intValue) maxDisplayCols.intValue = m
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
            // Invalidate wrapped-row info for edited lines so stale boundaries
            // are not used by key handlers before the next composition pass.
            if (state.wordWrap) {
                state.wrappedRowCache.invalidate(startLine)
            }
            // Keep the max display width current for the edited line (R-43).
            val editedLen = try {
                displayLength(state.document.line(startLine))
            } catch (_: Exception) {
                0
            }
            if (editedLen > maxDisplayCols.intValue) maxDisplayCols.intValue = editedLen
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
    val contentViewportPx = (containerSize.width - gutterWidthPx).coerceAtLeast(0f)

    // Re-clamp horizontal bounds when content width, viewport, or wrap changes
    // (rotation lands here via containerSize). Word wrap disables the axis.
    LaunchedEffect(maxDisplayCols.intValue, containerSize, displaySettings.wordWrap, charWidthPx) {
        val contentWidth = if (displaySettings.wordWrap) {
            0f
        } else {
            maxDisplayCols.intValue * charWidthPx + charWidthPx * 2 // caret slack
        }
        hScroll.updateBounds(contentWidth, contentViewportPx)
    }

    // Follow the caret horizontally while typing/navigating (R-43).
    LaunchedEffect(state.caretLine, state.caretColumn, state.document.editGeneration) {
        if (!state.wordWrap && charWidthPx > 0f && contentViewportPx > 0f) {
            val lineText = try {
                state.document.line(state.caretLine).toString()
            } catch (_: Exception) {
                ""
            }
            val col = state.caretColumn.coerceIn(0, lineText.length)
            val caretX = displayColumn(lineText, col) * charWidthPx
            hScroll.ensureVisible(caretX, contentViewportPx)
        }
    }

    // Keep the caret line vertically visible (typing Enter at the bottom of
    // the viewport must not leave the caret offscreen).
    LaunchedEffect(state.caretLine) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isNotEmpty()) {
            val line = state.caretLine.toInt().coerceAtLeast(0)
            val first = visible.first().index
            val last = visible.last().index
            if (line < first || line > last) {
                listState.scrollToItem(line)
            }
        }
    }

    // Sync displaySettings.wordWrap into state.wordWrap (R-18b).
    SideEffect {
        if (state.wordWrap != displaySettings.wordWrap) {
            state.wordWrap = displaySettings.wordWrap
            if (!displaySettings.wordWrap) {
                state.wrappedRowCache.clear()
            }
        }
    }

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

    /** Map a viewport tap/drag position to a (line, column) document position. */
    fun documentPositionAt(position: Offset): Pair<Long, Int>? {
        val lineIndex = resolveLineFromY(position.y, listState, state)
        if (lineIndex < 0 || lineIndex >= state.lineCount) return null
        val lineText = state.document.line(lineIndex).toString()
        // Add the horizontal scroll offset: the tap x is in viewport space but
        // column resolution needs content space (R-43).
        val xInContent = (position.x - gutterWidthPx + hScroll.offsetPx).coerceAtLeast(0f)
        val col = resolveColumnFromX(
            lineIndex, lineText, xInContent, layoutCache, state.document.editGeneration,
        )
        return lineIndex to col
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
                // Horizontal panning lives on the same container as vertical
                // list scrolling; Compose's axis disambiguation routes each
                // gesture to whichever axis crosses touch slop first (R-43).
                .horizontalDocumentScroll(hScroll, enabled = !displaySettings.wordWrap)
                .pointerInput(state) {
                    detectTapGestures(
                        onTap = { offset ->
                            documentPositionAt(offset)?.let { (line, col) ->
                                state.moveCaret(line, col)
                                showToolbar = false
                            }
                            // Re-summon the soft keyboard on every tap: focus
                            // may persist while the IME was dismissed (R-41).
                            onRequestIme()
                        },
                        onDoubleTap = { offset ->
                            documentPositionAt(offset)?.let { (line, col) ->
                                state.selectWordAt(line, col)
                                showToolbar = true
                                toolbarPosition = Offset(offset.x, offset.y - 48f)
                            }
                        },
                    )
                }
                // Selection follows the mobile convention: long-press selects
                // the word, then dragging extends. Plain drags are NOT consumed
                // here, so they remain available to vertical list scrolling and
                // horizontal document panning (R-42). The previous
                // detectDragGestures consumed every pan as a selection drag,
                // which starved the list's own scrollable.
                .pointerInput(state) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            documentPositionAt(offset)?.let { (line, col) ->
                                state.selectWordAt(line, col)
                                showToolbar = false
                                toolbarPosition = Offset(offset.x, offset.y - 48f)
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            documentPositionAt(change.position)?.let { (line, col) ->
                                state.moveCaretWithSelection(line, col)
                                toolbarPosition = Offset(change.position.x, change.position.y - 48f)
                            }
                        },
                        onDragEnd = {
                            showToolbar = state.hasSelection
                        },
                        onDragCancel = {
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

                // Apply composing region underline (R-16: CJK/predictive IME)
                val finalAnnotated = if (
                    state.isComposing &&
                    state.composingLine == index.toLong() &&
                    state.composingStart >= 0 &&
                    state.composingEnd > state.composingStart &&
                    state.composingEnd <= displayText.length
                ) {
                    buildAnnotatedString {
                        append(annotated)
                        addStyle(
                            SpanStyle(textDecoration = TextDecoration.Underline),
                            state.composingStart,
                            state.composingEnd,
                        )
                    }
                } else {
                    annotated
                }

                // Selection bounds for this line
                val selBounds = state.selectionBounds()
                val lineIdx = index.toLong()

                // Opportunistic max-width update from rendered rows (R-43).
                val rowDisplayLen = remember(displayText) { displayLength(displayText) }
                SideEffect {
                    if (rowDisplayLen > maxDisplayCols.intValue) {
                        maxDisplayCols.intValue = rowDisplayLen
                    }
                }

                // ── Populate wrappedRowCache during composition (R-18b) ────────
                if (displaySettings.wordWrap && containerSize.width > 0) {
                    val contentWidth = (containerSize.width - gutterWidthPx)
                        .toInt()
                        .coerceAtLeast(1)
                    val capturedLineIdx = lineIdx
                    val capturedText = displayText
                    val capturedEditGen = state.document.editGeneration
                    SideEffect {
                        val layout = layoutCache.measure(
                            lineIndex = capturedLineIdx,
                            lineText = capturedText,
                            constraints = Constraints(maxWidth = contentWidth),
                            editGeneration = capturedEditGen,
                        )
                        if (layout.lineCount > 1) {
                            val displayToChar = buildDisplayToCharMap(capturedText, 4)
                            val starts = IntArray(layout.lineCount) { vRow ->
                                val displayStart = layout.getLineStart(vRow)
                                if (displayStart < displayToChar.size) {
                                    displayToChar[displayStart]
                                } else {
                                    capturedText.length
                                }
                            }
                            state.wrappedRowCache.put(capturedLineIdx, starts)
                        } else {
                            state.wrappedRowCache.put(capturedLineIdx, intArrayOf(0))
                        }
                    }
                }

                // Layout constraints for caret/selection measurement: bounded
                // when wrapping (visual rows depend on width), unbounded when
                // not (the line lays out at full intrinsic width and pans).
                val measureConstraints = if (displaySettings.wordWrap) {
                    Constraints(
                        maxWidth = (containerSize.width - gutterWidthPx).toInt().coerceAtLeast(1),
                    )
                } else {
                    Constraints() // unbounded max width
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isCaretLine) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            else Color.Transparent,
                        )
                        .drawWithContent {
                            drawContent()

                            // Caret and selection overlays live in content
                            // space: translate by the shared horizontal offset
                            // and clip to the text area so nothing draws over
                            // the pinned gutter (R-43).
                            val hOffset = hScroll.offsetPx
                            clipRect(left = gutterWidthPx, top = 0f, right = size.width, bottom = size.height) {
                                // Selection highlight on this line
                                if (selBounds != null &&
                                    lineIdx >= selBounds.startLine &&
                                    lineIdx <= selBounds.endLine
                                ) {
                                    val layout = layoutCache.measure(
                                        lineIndex = lineIdx,
                                        lineText = displayText,
                                        constraints = measureConstraints,
                                        editGeneration = editGen,
                                    )
                                    val lh = layoutCache.lineHeight(layout)

                                    val selStartChar = when {
                                        lineIdx > selBounds.startLine -> 0
                                        else -> selBounds.startColumn
                                    }
                                    val selEndChar = when {
                                        lineIdx < selBounds.endLine -> displayText.length
                                        else -> selBounds.endColumn.coerceAtMost(displayText.length)
                                    }

                                    if (selEndChar > selStartChar) {
                                        // Cursor rects are indexed by display
                                        // (tab-expanded) offsets, not char
                                        // offsets — convert first.
                                        val startRect = layoutCache.cursorRect(
                                            layout, displayColumn(displayText, selStartChar),
                                        )
                                        val endRect = layoutCache.cursorRect(
                                            layout, displayColumn(displayText, selEndChar),
                                        )
                                        drawSelectionHighlight(
                                            startX = gutterWidthPx + startRect.left - hOffset,
                                            endX = gutterWidthPx + endRect.left - hOffset,
                                            lineTopY = 0f,
                                            lineHeight = lh,
                                            color = selectionColor,
                                        )
                                    }
                                }

                                // Caret on this line (blink respects reduce-motion, R-37).
                                if (isCaretLine) {
                                    val layout = layoutCache.measure(
                                        lineIndex = lineIdx,
                                        lineText = displayText,
                                        constraints = measureConstraints,
                                        editGeneration = editGen,
                                    )
                                    val caretCol = state.caretColumn.coerceAtMost(displayText.length)
                                    val caretRect = layoutCache.cursorRect(
                                        layout, displayColumn(displayText, caretCol),
                                    )
                                    val caretWidthPx = with(density) { 2.dp.toPx() }
                                    val lh = layoutCache.lineHeight(layout)
                                    drawRect(
                                        color = primaryColor.copy(alpha = caretAlpha),
                                        topLeft = Offset(
                                            gutterWidthPx + caretRect.left - hOffset,
                                            caretRect.top,
                                        ),
                                        size = Size(caretWidthPx, lh),
                                    )
                                }
                            }
                        },
                ) {
                    // Gutter (with bookmark indicator) — pinned; never translates.
                    val isBookmarked = lineIdx in state.bookmarks
                    Text(
                        text = if (isBookmarked) "\u25CF${index + 1}" else "${index + 1}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = if (isBookmarked) MaterialTheme.colorScheme.primary else compareColors.gutter,
                        modifier = Modifier
                            .width(gutterWidth)
                            .padding(end = 8.dp, top = 2.dp, bottom = 2.dp),
                        maxLines = 1,
                    )

                    // Content (uses finalAnnotated which includes composing underline)
                    if (displaySettings.wordWrap) {
                        Text(
                            text = finalAnnotated,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = onSurface,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 2.dp),
                            softWrap = true,
                        )
                    } else {
                        // Shared-offset horizontal pan (R-43): the text lays
                        // out at full intrinsic width (unbounded), is clipped
                        // to the content area, and translates by the single
                        // document offset at draw time — no per-row
                        // horizontalScroll, so there is no per-row maxValue to
                        // clobber and all rows move together as one page.
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clipToBounds(),
                        ) {
                            Text(
                                text = finalAnnotated,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                color = onSurface,
                                softWrap = false,
                                maxLines = 1,
                                modifier = Modifier
                                    .wrapContentWidth(Alignment.Start, unbounded = true)
                                    .graphicsLayer { translationX = -hScroll.offsetPx }
                                    .padding(vertical = 2.dp),
                            )
                        }
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

/** Tab-expanded display length of a line (tab width 4). */
internal fun displayLength(text: CharSequence): Int {
    var column = 0
    for (ch in text) {
        column += if (ch == '\t') 4 - (column % 4) else 1
    }
    return column
}

/**
 * Convert a character column into a display (tab-expanded) column.
 *
 * [LineLayoutCache] measures tab-expanded text, so cursor rects and
 * position-to-offset queries are indexed by display offsets. The previous
 * implementation passed character columns straight into cursorRect, which
 * misplaced the caret and selection edges on any line containing tabs.
 */
internal fun displayColumn(text: CharSequence, charColumn: Int): Int {
    val end = charColumn.coerceIn(0, text.length)
    var column = 0
    for (i in 0 until end) {
        column += if (text[i] == '\t') 4 - (column % 4) else 1
    }
    return column
}

/**
 * Resolve a line index from a Y position within the LazyColumn viewport.
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
    val firstVisible = layoutInfo.visibleItemsInfo.firstOrNull()
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
    return when {
        firstVisible != null && y < firstVisible.offset -> firstVisible.index.toLong()
        lastVisible != null -> lastVisible.index.toLong()
        else -> state.caretLine
    }
}

/**
 * Resolve a column index from an X position within the (unscrolled) content
 * area. Callers must add the horizontal scroll offset to viewport x first.
 *
 * Measures with unbounded width (no-wrap semantics) and keys the cache by the
 * real line index and edit generation. The previous implementation keyed every
 * lookup as `(lineIndex = 0, editGeneration = -1)`, so the LRU cache returned
 * the layout of whichever line was measured first for ALL subsequent taps —
 * every tap resolved columns against a stale, unrelated line.
 */
private fun resolveColumnFromX(
    lineIndex: Long,
    lineText: String,
    xInContent: Float,
    layoutCache: LineLayoutCache,
    editGeneration: Long,
): Int {
    if (lineText.isEmpty()) return 0
    val layout = layoutCache.measure(
        lineIndex = lineIndex,
        lineText = lineText, // LineLayoutCache expands tabs internally
        constraints = Constraints(), // unbounded max width
        editGeneration = editGeneration,
    )
    val displayOffset = layoutCache.offsetForPosition(layout, Offset(xInContent, 0f))
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
