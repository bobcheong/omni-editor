package com.omnieditor.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
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

    // Detect language for syntax highlighting
    val language = remember(fileName) { SyntaxEngine.detectLanguage(fileName) }
    val grammar = remember(language) { SyntaxEngine.grammarFor(language) }

    // Hoist syntax colours — resolved once per theme change, not per recomposition (R-19).
    val syntaxColors = LocalSyntaxColors.current

    // Stateful highlighter — carries lexer entry state across lines (R-19).
    // Created once per grammar; invalidated from the edit point downward.
    val highlighter = remember(grammar) {
        grammar?.let { StatefulSyntaxHighlighter(it) }
    }

    // Track the earliest line invalidated by an edit so we can tell the
    // highlighter where to start re-lexing. This is collected from the
    // document's changes flow (which emits DocumentChange with startLine).
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

    // ── Scroll management ──────────────────────────────────────────────────
    // One direction is authoritative:
    //   - Programmatic (go-to-line, search) writes state.firstVisibleLine and
    //     sets pendingScrollCommand, which the list effect below consumes once.
    //   - User fling/drag → the snapshotFlow below feeds back into firstVisibleLine.
    //
    // The trick: we only write to the LazyListState from the programmatic path
    // (pendingScrollCommand), and only write firstVisibleLine from the user-scroll
    // path. The two LaunchedEffects no longer write each other's state, so there
    // is no feedback loop.

    // A one-shot command set by programmatic navigation (go-to-line, search result).
    val pendingScrollCommand = remember { mutableStateOf<Long?>(null) }

    // Detect when firstVisibleLine changes *programmatically* (i.e., not from the
    // list-scroll feedback). We track the value that was last written by the list
    // to avoid re-triggering on user scroll.
    val lastListFirstVisible = remember { mutableStateOf(listState.firstVisibleItemIndex.toLong()) }

    // When firstVisibleLine is changed externally (not by the user scroll feedback),
    // store it as a pending scroll command.
    LaunchedEffect(state.firstVisibleLine) {
        val target = state.firstVisibleLine
        if (target != lastListFirstVisible.value) {
            pendingScrollCommand.value = target
        }
    }

    // Consume the pending scroll command.
    LaunchedEffect(pendingScrollCommand.value) {
        val target = pendingScrollCommand.value ?: return@LaunchedEffect
        if (target >= 0 && target < state.lineCount) {
            listState.scrollToItem(target.toInt())
            lastListFirstVisible.value = target
        }
        pendingScrollCommand.value = null
    }

    // Feed user scroll back into state (authoritative for user-driven scroll).
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                val line = index.toLong()
                lastListFirstVisible.value = line
                state.firstVisibleLine = line
            }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                count = state.lineCount.toInt(),
                key = { it },
            ) { index ->
                // ── Fix: key on editGeneration instead of dirty ─────────────
                // dirty is a Boolean that stays true after the first edit and
                // therefore never triggers recomposition for subsequent edits.
                // editGeneration is monotonically increasing, so every edit
                // produces a new key value.
                val editGen = state.document.editGeneration
                val lineText = remember(index, editGen) {
                    state.document.line(index.toLong()).toString()
                }
                val isCaretLine = index.toLong() == state.caretLine

                // Track whether this line's truncation has been expanded by the user
                val isExpanded = remember(index) { mutableStateOf(false) }

                val isLongLine = lineText.length > TRUNCATION_CHAR_THRESHOLD
                val displayText = if (isLongLine && !isExpanded.value) {
                    lineText.take(TRUNCATION_CHAR_THRESHOLD)
                } else {
                    lineText
                }

                val showWs = displaySettings.showWhitespace

                // Tokenize with carried state when a grammar is present (R-19).
                // When the highlighter is available we call it directly (it carries
                // state across lines); otherwise fall back to the stateless path.
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
                                    ' ' -> withStyle(SpanStyle(color = whitespaceColor)) { append('·') }
                                    '\t' -> withStyle(SpanStyle(color = whitespaceColor)) { append('→') }
                                    else -> withStyle(SpanStyle(color = onSurface)) { append(ch) }
                                }
                            }
                        }
                        else -> AnnotatedString(displayText)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isCaretLine) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            else Color.Transparent
                        ),
                ) {
                    // Gutter — one number per logical line regardless of wrap
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

                    // Content — word-wrap or horizontal scroll depending on setting
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
                            text = "…expand",
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
            // Unhighlighted text before this token
            if (token.start > pos) {
                withStyle(SpanStyle(color = defaultColor)) {
                    append(text.substring(pos, token.start))
                }
            }
            // Highlighted token
            val color = tokenColor(token.type, colors)
            val end = (token.start + token.length).coerceAtMost(text.length)
            withStyle(SpanStyle(color = color)) {
                append(text.substring(token.start, end))
            }
            pos = end
        }
        // Remaining text
        if (pos < text.length) {
            withStyle(SpanStyle(color = defaultColor)) {
                append(text.substring(pos))
            }
        }
    }
}

/**
 * Highlight [original] text for syntax and simultaneously substitute whitespace
 * characters with visible glyphs in [whitespaceColor]. This avoids running the
 * substituted text through the tokenizer (which would produce wrong token ranges).
 */
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
                    ' ' -> withStyle(SpanStyle(color = whitespaceColor)) { append('·') }
                    '\t' -> withStyle(SpanStyle(color = whitespaceColor)) { append('→') }
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
