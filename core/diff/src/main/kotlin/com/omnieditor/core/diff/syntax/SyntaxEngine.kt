package com.omnieditor.core.diff.syntax

/**
 * Syntax highlighting engine using our own grammar format (IND-3, OE-TXT-5).
 *
 * Grammars are regex-based tokenizers applied per line. Highlighting is
 * progressive — applied lazily per visible line, never delaying first paint.
 * Unknown extensions degrade to plain text silently.
 */
object SyntaxEngine {

    /** Max characters to highlight per line. Beyond this, the rest is plain text. */
    private const val MAX_HIGHLIGHT_LENGTH = 2000

    /**
     * Tokenize a single line using the given grammar, ignoring cross-line state.
     * Use [tokenizeLineStateful] when multi-line constructs (block comments,
     * multi-line strings) must be tracked across lines.
     */
    fun tokenizeLine(line: String, grammar: Grammar): List<SyntaxToken> {
        val result = tokenizeLineStateful(line, grammar, LexerState.NORMAL)
        return result.tokens
    }

    /**
     * Tokenize a single line, carrying lexer entry state across lines.
     *
     * [entryState] is the state in which this line starts (e.g. inside a block
     * comment). Returns a [StatefulTokenResult] containing the token list and
     * the exit state to use as the entry state for the next line.
     */
    fun tokenizeLineStateful(
        line: String,
        grammar: Grammar,
        entryState: LexerState,
    ): StatefulTokenResult {
        if (line.isEmpty()) return StatefulTokenResult(emptyList(), entryState)

        val tokens = mutableListOf<SyntaxToken>()
        val effectiveLine = if (line.length > MAX_HIGHLIGHT_LENGTH) line.substring(0, MAX_HIGHLIGHT_LENGTH) else line

        // Consume any entry continuation state (block comment or multi-line string).
        val afterEntry = consumeEntryState(effectiveLine, entryState, tokens)
            ?: return StatefulTokenResult(tokens, entryState)  // entire line consumed

        var pos = afterEntry
        while (pos < effectiveLine.length) {
            val specialResult = handleSpecialOpener(effectiveLine, grammar, pos, tokens)
            if (specialResult != null) {
                // null exitState means we found an opener that was closed on this line.
                val (newPos, exitState) = specialResult
                if (exitState != null) return StatefulTokenResult(tokens, exitState)
                pos = newPos
                continue
            }
            pos = matchGrammarRule(effectiveLine, grammar, pos, tokens)
        }

        return StatefulTokenResult(tokens, LexerState.NORMAL)
    }

    /**
     * Consume any continuation state at the start of a line (block comment or
     * multi-line string). Returns the position after the closing marker, or
     * `null` if the entire line was consumed (caller should return immediately).
     */
    private fun consumeEntryState(
        line: String,
        state: LexerState,
        tokens: MutableList<SyntaxToken>,
    ): Int? {
        return when (state) {
            LexerState.BLOCK_COMMENT -> {
                val closeIdx = line.indexOf("*/")
                if (closeIdx == -1) {
                    tokens.add(SyntaxToken(0, line.length, TokenType.COMMENT))
                    null  // entire line consumed
                } else {
                    val end = closeIdx + 2
                    tokens.add(SyntaxToken(0, end, TokenType.COMMENT))
                    end
                }
            }
            LexerState.MULTILINE_STRING -> {
                val closeIdx = line.indexOf("\"\"\"")
                if (closeIdx == -1) {
                    tokens.add(SyntaxToken(0, line.length, TokenType.STRING))
                    null  // entire line consumed
                } else {
                    val end = closeIdx + 3
                    tokens.add(SyntaxToken(0, end, TokenType.STRING))
                    end
                }
            }
            LexerState.NORMAL -> 0
        }
    }

    /**
     * At [pos], check if a multi-line opener (slash-star or triple-quote) starts
     * exactly here and takes priority over grammar rules.
     *
     * Returns a pair of (newPos, exitState):
     * - If the opener is closed on the same line: (newPos, null).
     * - If the opener spans to the next line: (irrelevant, exitState).
     * Returns `null` if no special opener is at [pos].
     */
    private fun handleSpecialOpener(
        line: String,
        grammar: Grammar,
        pos: Int,
        tokens: MutableList<SyntaxToken>,
    ): Pair<Int, LexerState?>? {
        val slashStar = line.indexOf("/*", pos)
        val tripleQuote = if (grammar.hasMultilineStrings) line.indexOf("\"\"\"", pos) else -1

        // Find the nearest special opener at exactly pos.
        val specialAtPos = when {
            tripleQuote == pos -> "triple"
            slashStar == pos -> "block"
            else -> null
        } ?: return null

        return when (specialAtPos) {
            "triple" -> {
                val closeIdx = line.indexOf("\"\"\"", pos + 3)
                if (closeIdx == -1) {
                    tokens.add(SyntaxToken(pos, line.length - pos, TokenType.STRING))
                    Pair(line.length, LexerState.MULTILINE_STRING)
                } else {
                    val end = closeIdx + 3
                    tokens.add(SyntaxToken(pos, end - pos, TokenType.STRING))
                    Pair(end, null)
                }
            }
            else -> {  // "block"
                val closeIdx = line.indexOf("*/", pos + 2)
                if (closeIdx == -1) {
                    tokens.add(SyntaxToken(pos, line.length - pos, TokenType.COMMENT))
                    Pair(line.length, LexerState.BLOCK_COMMENT)
                } else {
                    val end = closeIdx + 2
                    tokens.add(SyntaxToken(pos, end - pos, TokenType.COMMENT))
                    Pair(end, null)
                }
            }
        }
    }

    /**
     * Try to match the longest grammar rule at [pos]. Appends the token if found.
     * Returns the new position (advanced by token length, or by 1 if no match).
     */
    private fun matchGrammarRule(
        line: String,
        grammar: Grammar,
        pos: Int,
        tokens: MutableList<SyntaxToken>,
    ): Int {
        var bestMatch: MatchResult? = null
        var bestRule: GrammarRule? = null

        for (rule in grammar.rules) {
            val match = rule.compiledPattern.find(line, pos)
            if (match != null && match.range.first == pos) {
                if (bestMatch == null || match.value.length > bestMatch.value.length) {
                    bestMatch = match
                    bestRule = rule
                }
            }
        }

        return if (bestMatch != null && bestRule != null) {
            tokens.add(SyntaxToken(pos, bestMatch.value.length, bestRule.tokenType))
            pos + bestMatch.value.length
        } else {
            pos + 1
        }
    }

    /**
     * Detect language from file extension.
     */
    fun detectLanguage(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return EXTENSION_MAP[ext]
    }

    /**
     * Get the grammar for a language name, or null for plain text.
     */
    fun grammarFor(language: String?): Grammar? {
        if (language == null) return null
        return GRAMMARS[language]
    }

    private val EXTENSION_MAP = mapOf(
        "kt" to "kotlin", "kts" to "kotlin",
        "java" to "java",
        "js" to "javascript", "mjs" to "javascript", "cjs" to "javascript",
        "ts" to "typescript", "tsx" to "typescript",
        "py" to "python", "pyw" to "python",
        "go" to "go",
        "rs" to "rust",
        "c" to "c", "h" to "c", "cpp" to "cpp", "cc" to "cpp", "cxx" to "cpp", "hpp" to "cpp",
        "sh" to "shell", "bash" to "shell", "zsh" to "shell",
        "yaml" to "yaml", "yml" to "yaml",
        "json" to "json", "jsonc" to "json",
        "xml" to "xml", "html" to "html", "htm" to "html", "xhtml" to "html", "svg" to "xml",
        "sql" to "sql",
        "md" to "markdown", "markdown" to "markdown",
    )
}

/**
 * A syntax token: a span of text with a semantic type.
 */
data class SyntaxToken(
    val start: Int,
    val length: Int,
    val type: TokenType,
)

enum class TokenType {
    KEYWORD,
    TYPE,
    STRING,
    NUMBER,
    COMMENT,
    OPERATOR,
    PUNCTUATION,
    ANNOTATION,
    FUNCTION,
    CONSTANT,
    TAG,
    ATTRIBUTE,
    HEADING,
}

/**
 * The state in which a line's lexer starts (entry state) or ends (exit state).
 * Carried across lines to correctly highlight multi-line block comments and
 * triple-quoted strings.
 *
 * Encoded as an Int (0 = NORMAL, 1 = BLOCK_COMMENT, 2 = MULTILINE_STRING) so
 * it can be stored compactly in an IntArray cache inside StatefulSyntaxHighlighter.
 */
enum class LexerState(val code: Int) {
    NORMAL(0),
    BLOCK_COMMENT(1),
    MULTILINE_STRING(2);

    companion object {
        fun fromCode(code: Int): LexerState = when (code) {
            1 -> BLOCK_COMMENT
            2 -> MULTILINE_STRING
            else -> NORMAL
        }
    }
}

/**
 * Result of a stateful tokenization pass: the token list for the line plus
 * the lexer state at the end of the line (to be used as the entry state for
 * the next line).
 */
data class StatefulTokenResult(
    val tokens: List<SyntaxToken>,
    val exitState: LexerState,
)

/**
 * A grammar definition — our own format (IND-3).
 * Each grammar is a list of rules, ordered by priority.
 *
 * [hasMultilineStrings] should be true for languages that support triple-quoted
 * (or similar) string literals that can span multiple lines (e.g. Kotlin, Python).
 */
data class Grammar(
    val name: String,
    val rules: List<GrammarRule>,
    val hasMultilineStrings: Boolean = false,
)

data class GrammarRule(
    val pattern: String,
    val tokenType: TokenType,
) {
    val compiledPattern: Regex by lazy { Regex(pattern) }
}
