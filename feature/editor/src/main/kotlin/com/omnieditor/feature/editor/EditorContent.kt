package com.omnieditor.feature.editor

import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
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
import com.omnieditor.core.diff.syntax.TokenType
import com.omnieditor.design.LocalCompareColors

@Composable
fun EditorContent(
    state: EditorState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    fileName: String = "",
) {
    val listState = rememberLazyListState()
    val compareColors = LocalCompareColors.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val horizontalScrollState = rememberScrollState()

    // Detect language for syntax highlighting
    val language = remember(fileName) { SyntaxEngine.detectLanguage(fileName) }
    val grammar = remember(language) { SyntaxEngine.grammarFor(language) }

    val gutterWidth by remember(state.lineCount) {
        derivedStateOf {
            val digits = state.lineCount.toString().length
            (digits * 10 + 24).dp
        }
    }

    LaunchedEffect(state.firstVisibleLine) {
        if (state.firstVisibleLine >= 0 && state.firstVisibleLine < state.lineCount) {
            listState.scrollToItem(state.firstVisibleLine.toInt())
        }
    }

    LaunchedEffect(listState.firstVisibleItemIndex) {
        state.firstVisibleLine = listState.firstVisibleItemIndex.toLong()
    }

    // Syntax highlighting colors
    val syntaxColors = SyntaxColors(
        keyword = Color(0xFF0033B3),
        type = Color(0xFF1750EB),
        string = Color(0xFF067D17),
        number = Color(0xFF1750EB),
        comment = Color(0xFF8C8C8C),
        annotation = Color(0xFFBBB529),
        tag = Color(0xFF871094),
        attribute = Color(0xFF174AD4),
        heading = Color(0xFF0033B3),
        operator = onSurface,
        punctuation = onSurface,
        constant = Color(0xFF871094),
        function = Color(0xFF00627A),
    )

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
                val lineText = remember(index, state.document.dirty) {
                    state.document.line(index.toLong()).toString()
                }
                val isCaretLine = index.toLong() == state.caretLine

                val annotated = remember(lineText, grammar) {
                    if (grammar != null) {
                        highlightLine(lineText, grammar, syntaxColors, onSurface)
                    } else {
                        AnnotatedString(lineText)
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

                    // Content with horizontal scroll
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
            }
        }
    }
}

data class SyntaxColors(
    val keyword: Color,
    val type: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val annotation: Color,
    val tag: Color,
    val attribute: Color,
    val heading: Color,
    val operator: Color,
    val punctuation: Color,
    val constant: Color,
    val function: Color,
)

private fun highlightLine(
    text: String,
    grammar: com.omnieditor.core.diff.syntax.Grammar,
    colors: SyntaxColors,
    defaultColor: Color,
): AnnotatedString {
    val tokens = SyntaxEngine.tokenizeLine(text, grammar)
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
            val color = when (token.type) {
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
