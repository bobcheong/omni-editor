package com.omnieditor.core.diff.syntax

/**
 * Syntax highlighting engine using our own grammar format (IND-3, OE-TXT-5).
 *
 * Grammars are regex-based tokenizers applied per line. Highlighting is
 * progressive — applied lazily per visible line, never delaying first paint.
 * Unknown extensions degrade to plain text silently.
 */
object SyntaxEngine {

    /**
     * Tokenize a single line using the given grammar.
     * Returns a list of tokens with their type and position.
     */
    /** Max characters to highlight per line. Beyond this, the rest is plain text. */
    private const val MAX_HIGHLIGHT_LENGTH = 2000

    fun tokenizeLine(line: String, grammar: Grammar): List<SyntaxToken> {
        if (line.isEmpty()) return emptyList()
        val tokens = mutableListOf<SyntaxToken>()
        val effectiveLine = if (line.length > MAX_HIGHLIGHT_LENGTH) line.substring(0, MAX_HIGHLIGHT_LENGTH) else line
        var pos = 0

        while (pos < effectiveLine.length) {
            var bestMatch: MatchResult? = null
            var bestRule: GrammarRule? = null

            for (rule in grammar.rules) {
                val match = rule.compiledPattern.find(effectiveLine, pos)
                if (match != null && match.range.first == pos) {
                    if (bestMatch == null || match.value.length > bestMatch.value.length) {
                        bestMatch = match
                        bestRule = rule
                    }
                }
            }

            if (bestMatch != null && bestRule != null) {
                tokens.add(SyntaxToken(pos, bestMatch.value.length, bestRule.tokenType))
                pos += bestMatch.value.length
            } else {
                pos++
            }
        }

        return tokens
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
 * A grammar definition — our own format (IND-3).
 * Each grammar is a list of rules, ordered by priority.
 */
data class Grammar(
    val name: String,
    val rules: List<GrammarRule>,
)

data class GrammarRule(
    val pattern: String,
    val tokenType: TokenType,
) {
    val compiledPattern: Regex by lazy { Regex(pattern) }
}
