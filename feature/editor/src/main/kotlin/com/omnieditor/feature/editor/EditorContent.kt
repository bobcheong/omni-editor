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
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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

/** Vertical padding applied to every editor row's text. */
private val ROW_V_PADDING = 2.dp

/**
 * Selection geometry in container coordinates, derived from the current
 * selection, list layout, and horizontal scroll offset (R-50).
 *
 * Handle positions are null when the respective boundary line is scrolled out
 * of the viewport (the handle is simply not shown).
 */
private data class SelectionGeometry(
    val startHandle: Offset?,
    val endHandle: Offset?,
    val anchorRect: Rect,
)

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
     * always be re-summoned after the user dismisses it (R-46).
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
    val rowVPadPx = with(density) { ROW_V_PADDING.toPx() }

    // ── Text metrics ─────────────────────────────────────────────────────
    // ONE fully specified style shared by rendering AND measurement. Passing
    // fontFamily/fontSize as Text *parameters* merges them into
    // LocalTextStyle (Material 3 bodyLarge: letterSpacing 0.5.sp, lineHeight
    // 24.sp), while TextMeasurer measured a bare style with letterSpacing 0 —
    // so every on-screen glyph advanced ~0.5sp more than the hit-testing
    // math assumed and the caret drifted further right of the finger with
    // every column. Rendering with `style = textStyle` (no merge) makes the
    // measured layout pixel-identical to what is drawn.
    val textStyle = remember {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            letterSpacing = 0.sp,
        )
    }
    val textMeasurer = rememberTextMeasurer()
    val layoutCache = remember(textMeasurer, textStyle) {
        LineLayoutCache(textMeasurer, textStyle)
    }
    val zeroGlyph = remember(textMeasurer, textStyle) {
        textMeasurer.measure(AnnotatedString("0"), style = textStyle)
    }
    val charWidthPx = zeroGlyph.size.width.toFloat()
    val lineHeightPx = zeroGlyph.size.height.toFloat()

    // ── Horizontal document scroll (R-42) ─────────────────────────────────
    val hScroll = rememberHorizontalScrollController()
    val maxDisplayCols = remember(state.document) { mutableIntStateOf(0) }

    // Initial full scan of the widest line, chunked with cooperative yields to
    // keep the main thread responsive (R-42; shrink limitation in ADR-011).
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
            // Keep the max display width current for the edited line (R-42).
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

    // Layout constraints for ALL measure sites this frame: bounded when
    // wrapping (visual rows depend on width), unbounded when not. Using one
    // formula everywhere matters beyond correctness of each site —
    // LineLayoutCache keys entries by (line, editGeneration) only, so two
    // sites measuring the same line with different constraints would poison
    // each other's cache entries.
    val contentConstraints = if (displaySettings.wordWrap) {
        Constraints(maxWidth = (containerSize.width - gutterWidthPx).toInt().coerceAtLeast(1))
    } else {
        Constraints() // unbounded max width
    }

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

    // Follow the caret horizontally while typing/navigating (R-42).
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

    // Floating toolbar visibility
    var showToolbar by remember { mutableStateOf(false) }

    // Show/hide toolbar when selection changes
    LaunchedEffect(state.hasSelection) {
        showToolbar = state.hasSelection
    }

    // ── Unified tap/drag → document position resolver ────────────────────
    // One resolver for taps, long-press drags, and handle drags. It measures
    // with the SAME constraints as drawing, subtracts the row's vertical text
    // padding, and — critically for word wrap — feeds the *local y within the
    // row* into getOffsetForPosition, so taps land on the correct visual row
    // of a wrapped line instead of always the first. Wrapped in
    // rememberUpdatedState because the gesture pointerInput blocks are keyed
    // on `state` and would otherwise capture stale geometry (gutter width,
    // container size, wrap mode) from the composition they launched in.
    val positionResolver: State<(Offset) -> Pair<Long, Int>?> = rememberUpdatedState resolver@{ position: Offset ->
        val items = listState.layoutInfo.visibleItemsInfo
        if (items.isEmpty()) return@resolver null
        val item = items.firstOrNull { position.y >= it.offset && position.y < it.offset + it.size }
            ?: if (position.y < items.first().offset) items.first() else items.last()
        val line = item.index.toLong()
        if (line >= state.lineCount) return@resolver null
        val text = try {
            state.document.line(line).toString()
        } catch (_: Exception) {
            return@resolver null
        }
        val layout = layoutCache.measure(line, text, contentConstraints, state.document.editGeneration)
        val hOff = if (state.wordWrap) 0f else hScroll.offsetPx
        val x = (position.x - gutterWidthPx + hOff).coerceAtLeast(0f)
        val localY = (position.y - item.offset - rowVPadPx)
            .coerceIn(0f, (layout.size.height - 1).coerceAtLeast(0).toFloat())
        val displayOffset = layoutCache.offsetForPosition(layout, Offset(x, localY))
        val displayToChar = buildDisplayToCharMap(text, 4)
        val col = if (displayOffset < displayToChar.size) displayToChar[displayOffset] else text.length
        line to col
    }

    // ── Selection geometry: real handle + toolbar anchoring (R-50) ────────
    val selectionGeometry: SelectionGeometry? by remember(charWidthPx, lineHeightPx) {
        derivedStateOf {
            val b = state.selectionBounds() ?: return@derivedStateOf null
            val gen = state.document.editGeneration
            val items = listState.layoutInfo.visibleItemsInfo
            if (items.isEmpty()) return@derivedStateOf null
            val hOff = if (state.wordWrap) 0f else hScroll.offsetPx
            val cw = containerSize.width.toFloat().coerceAtLeast(gutterWidthPx + 1f)

            fun boundaryBottom(line: Long, col: Int): Offset? {
                val item = items.firstOrNull { it.index.toLong() == line } ?: return null
                val text = try {
                    state.document.line(line).toString()
                } catch (_: Exception) {
                    return null
                }
                val c = col.coerceIn(0, text.length)
                val layout = layoutCache.measure(line, text, contentConstraints, gen)
                val r = layoutCache.cursorRect(layout, displayColumn(text, c))
                val x = (gutterWidthPx + r.left - hOff).coerceIn(gutterWidthPx, cw)
                return Offset(x, item.offset + rowVPadPx + r.bottom)
            }

            val startHandle = boundaryBottom(b.startLine, b.startColumn)
            val endHandle = boundaryBottom(b.endLine, b.endColumn)

            val topRow = items.firstOrNull { it.index.toLong() == b.startLine }
            val bottomRow = items.firstOrNull { it.index.toLong() == b.endLine }
            val top = (topRow?.offset ?: 0).toFloat()
            val bottom = (bottomRow?.let { it.offset + it.size } ?: containerSize.height).toFloat()
            val xs = listOfNotNull(startHandle?.x, endHandle?.x)
            val left = xs.minOrNull() ?: gutterWidthPx
            val right = (xs.maxOrNull() ?: cw).coerceAtLeast(left + 1f)
            SelectionGeometry(startHandle, endHandle, Rect(left, top, right, bottom))
        }
    }

    // Handle-drag bookkeeping: the opposite (fixed) boundary is captured once
    // at drag start, and the handle's absolute position is integrated from
    // deltas so the selection follows the finger, not per-frame noise.
    var handleDragPos by remember { mutableStateOf(Offset.Zero) }
    var handleFixedEnd by remember { mutableStateOf<Pair<Long, Int>?>(null) }

    fun dragHandleTo(position: Offset) {
        val fixed = handleFixedEnd ?: return
        // Aim slightly above the handle tip — the tip sits at the row bottom.
        val target = position - Offset(0f, lineHeightPx * 0.5f)
        positionResolver.value(target)?.let { (line, col) ->
            state.setSelection(fixed.first, fixed.second, line, col)
        }
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
                // gesture to whichever axis crosses touch slop first (R-42).
                .horizontalDocumentScroll(hScroll, enabled = !displaySettings.wordWrap)
                .pointerInput(state) {
                    detectTapGestures(
                        onTap = { offset ->
                            positionResolver.value(offset)?.let { (line, col) ->
                                state.moveCaret(line, col)
                                showToolbar = false
                            }
                            // Re-summon the soft keyboard on every tap: focus
                            // may persist while the IME was dismissed (R-46).
                            onRequestIme()
                        },
                        onDoubleTap = { offset ->
                            positionResolver.value(offset)?.let { (line, col) ->
                                state.selectWordAt(line, col)
                                showToolbar = true
                            }
                        },
                    )
                }
                // Selection follows the mobile convention: long-press selects
                // the word, then dragging extends. Plain drags are NOT consumed
                // here, so they remain available to vertical list scrolling and
                // horizontal document panning (R-47).
                .pointerInput(state) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            positionResolver.value(offset)?.let { (line, col) ->
                                state.selectWordAt(line, col)
                                showToolbar = false
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            positionResolver.value(change.position)?.let { (line, col) ->
                                state.moveCaretWithSelection(line, col)
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

                // Opportunistic max-width update from rendered rows (R-42).
                val rowDisplayLen = remember(displayText) { displayLength(displayText) }
                SideEffect {
                    if (rowDisplayLen > maxDisplayCols.intValue) {
                        maxDisplayCols.intValue = rowDisplayLen
                    }
                }

                // ── Populate wrappedRowCache during composition (R-18b) ────────
                if (displaySettings.wordWrap && containerSize.width > 0) {
                    val capturedLineIdx = lineIdx
                    val capturedText = displayText
                    val capturedEditGen = state.document.editGeneration
                    val capturedConstraints = contentConstraints
                    SideEffect {
                        val layout = layoutCache.measure(
                            lineIndex = capturedLineIdx,
                            lineText = capturedText,
                            constraints = capturedConstraints,
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
                            // space: translate by the shared horizontal offset,
                            // shift down by the row's text padding (glyphs sit
                            // rowVPadPx below the row top), and clip to the
                            // text area so nothing draws over the gutter.
                            val hOffset = if (state.wordWrap) 0f else hScroll.offsetPx
                            clipRect(left = gutterWidthPx, top = 0f, right = size.width, bottom = size.height) {
                                // Selection highlight on this line
                                if (selBounds != null &&
                                    lineIdx >= selBounds.startLine &&
                                    lineIdx <= selBounds.endLine
                                ) {
                                    val layout = layoutCache.measure(
                                        lineIndex = lineIdx,
                                        lineText = displayText,
                                        constraints = contentConstraints,
                                        editGeneration = editGen,
                                    )

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
                                        // (tab-expanded) offsets — convert.
                                        val startRect = layoutCache.cursorRect(
                                            layout, displayColumn(displayText, selStartChar),
                                        )
                                        val endRect = layoutCache.cursorRect(
                                            layout, displayColumn(displayText, selEndChar),
                                        )
                                        if (startRect.top == endRect.top) {
                                            // Same visual row — one rect.
                                            drawSelectionHighlight(
                                                startX = gutterWidthPx + startRect.left - hOffset,
                                                endX = gutterWidthPx + endRect.left - hOffset,
                                                lineTopY = rowVPadPx + startRect.top,
                                                lineHeight = startRect.height,
                                                color = selectionColor,
                                            )
                                        } else {
                                            // Wrapped line: highlight from the
                                            // start boundary to the right edge,
                                            // full middle rows, then to the end
                                            // boundary.
                                            drawSelectionHighlight(
                                                startX = gutterWidthPx + startRect.left - hOffset,
                                                endX = size.width,
                                                lineTopY = rowVPadPx + startRect.top,
                                                lineHeight = startRect.height,
                                                color = selectionColor,
                                            )
                                            if (endRect.top > startRect.bottom) {
                                                drawSelectionHighlight(
                                                    startX = gutterWidthPx,
                                                    endX = size.width,
                                                    lineTopY = rowVPadPx + startRect.bottom,
                                                    lineHeight = endRect.top - startRect.bottom,
                                                    color = selectionColor,
                                                )
                                            }
                                            drawSelectionHighlight(
                                                startX = gutterWidthPx,
                                                endX = gutterWidthPx + endRect.left - hOffset,
                                                lineTopY = rowVPadPx + endRect.top,
                                                lineHeight = endRect.height,
                                                color = selectionColor,
                                            )
                                        }
                                    }
                                }

                                // Caret on this line (blink respects reduce-motion, R-37).
                                if (isCaretLine) {
                                    val layout = layoutCache.measure(
                                        lineIndex = lineIdx,
                                        lineText = displayText,
                                        constraints = contentConstraints,
                                        editGeneration = editGen,
                                    )
                                    val caretCol = state.caretColumn.coerceAtMost(displayText.length)
                                    val caretRect = layoutCache.cursorRect(
                                        layout, displayColumn(displayText, caretCol),
                                    )
                                    val caretWidthPx = with(density) { 2.dp.toPx() }
                                    drawRect(
                                        color = primaryColor.copy(alpha = caretAlpha),
                                        topLeft = Offset(
                                            gutterWidthPx + caretRect.left - hOffset,
                                            rowVPadPx + caretRect.top,
                                        ),
                                        size = Size(caretWidthPx, caretRect.height),
                                    )
                                }
                            }
                        },
                ) {
                    // Gutter (with bookmark indicator) — pinned; never translates.
                    val isBookmarked = lineIdx in state.bookmarks
                    Text(
                        text = if (isBookmarked) "\u25CF${index + 1}" else "${index + 1}",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 0.sp),
                        color = if (isBookmarked) MaterialTheme.colorScheme.primary else compareColors.gutter,
                        modifier = Modifier
                            .width(gutterWidth)
                            .padding(end = 8.dp, top = ROW_V_PADDING, bottom = ROW_V_PADDING),
                        maxLines = 1,
                    )

                    // Content (uses finalAnnotated which includes composing
                    // underline). MUST render with `style = textStyle` — see
                    // the metrics note at the top of this composable.
                    if (displaySettings.wordWrap) {
                        Text(
                            text = finalAnnotated,
                            style = textStyle,
                            color = onSurface,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = ROW_V_PADDING),
                            softWrap = true,
                        )
                    } else {
                        // Shared-offset horizontal pan (R-42): full intrinsic
                        // width, clipped, translated at draw time.
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clipToBounds(),
                        ) {
                            Text(
                                text = finalAnnotated,
                                style = textStyle,
                                color = onSurface,
                                softWrap = false,
                                maxLines = 1,
                                modifier = Modifier
                                    .wrapContentWidth(Alignment.Start, unbounded = true)
                                    .graphicsLayer { translationX = -hScroll.offsetPx }
                                    .padding(vertical = ROW_V_PADDING),
                            )
                        }
                    }

                    // Long-line expand affordance
                    if (isLongLine && !isExpanded.value && displaySettings.truncateLongLines) {
                        Text(
                            text = "\u2026expand",
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 0.sp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(horizontal = 4.dp, vertical = ROW_V_PADDING)
                                .clickable { isExpanded.value = true },
                        )
                    }
                }
            }
        }

        // ── Selection handles overlay (R-50) ─────────────────────────────
        val geometry = selectionGeometry
        if (state.hasSelection && geometry != null) {
            geometry.startHandle?.let { anchor ->
                SelectionHandle(
                    isStart = true,
                    position = anchor,
                    onDragStart = {
                        val b = state.selectionBounds()
                        handleFixedEnd = b?.let { it.endLine to it.endColumn }
                        handleDragPos = anchor
                        showToolbar = false
                    },
                    onDrag = { delta ->
                        handleDragPos += delta
                        dragHandleTo(handleDragPos)
                    },
                    onDragEnd = {
                        handleFixedEnd = null
                        showToolbar = state.hasSelection
                    },
                )
            }
            geometry.endHandle?.let { anchor ->
                SelectionHandle(
                    isStart = false,
                    position = anchor,
                    onDragStart = {
                        val b = state.selectionBounds()
                        handleFixedEnd = b?.let { it.startLine to it.startColumn }
                        handleDragPos = anchor
                        showToolbar = false
                    },
                    onDrag = { delta ->
                        handleDragPos += delta
                        dragHandleTo(handleDragPos)
                    },
                    onDragEnd = {
                        handleFixedEnd = null
                        showToolbar = state.hasSelection
                    },
                )
            }
        }

        // ── Floating edit toolbar (R-50) ─────────────────────────────────
        if (showToolbar && state.hasSelection && geometry != null) {
            FloatingActionToolbar(
                anchorRect = geometry.anchorRect,
                hasSelection = true,
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
                onSelectLine = {
                    val b = state.selectionBounds()
                    val sLine = b?.startLine ?: state.caretLine
                    val eLine = b?.endLine ?: state.caretLine
                    val eLen = state.document.line(eLine).length
                    state.setSelection(sLine, 0, eLine, eLen)
                },
                onDelete = {
                    state.deleteSelection()
                    showToolbar = false
                },
                onDuplicate = {
                    val b = state.selectionBounds()
                    val sLine = b?.startLine ?: state.caretLine
                    val eLine = b?.endLine ?: state.caretLine
                    val block = buildString {
                        for (l in sLine..eLine) {
                            append(state.document.line(l))
                            if (l < eLine) append('\n')
                        }
                    }
                    val eText = state.document.line(eLine).toString()
                    state.document.edit(eLine..eLine, eText + "\n" + block)
                    showToolbar = false
                },
                onUpperCase = {
                    transformSelection(state) { it.uppercase() }
                },
                onLowerCase = {
                    transformSelection(state) { it.lowercase() }
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
 * Replace the current selection with a transformed version of itself and
 * re-select the replacement, so chained transforms (UPPERCASE → lowercase)
 * work without re-selecting by hand.
 */
private fun transformSelection(state: EditorState, transform: (String) -> String) {
    val bounds = state.selectionBounds() ?: return
    val text = state.selectedText()
    if (text.isEmpty()) return
    state.deleteSelection()
    state.insertAtCaret(transform(text))
    state.setSelection(
        bounds.startLine, bounds.startColumn,
        state.caretLine, state.caretColumn,
    )
}

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
 * position-to-offset queries are indexed by display offsets.
 */
internal fun displayColumn(text: CharSequence, charColumn: Int): Int {
    val end = charColumn.coerceIn(0, text.length)
    var column = 0
    for (i in 0 until end) {
        column += if (text[i] == '\t') 4 - (column % 4) else 1
    }
    return column
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
