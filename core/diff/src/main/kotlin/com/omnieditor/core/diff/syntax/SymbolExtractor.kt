package com.omnieditor.core.diff.syntax

/**
 * F-11: Extract symbol declarations from source lines using the lexer token stream.
 *
 * Scans for declaration-keyword tokens then reads the identifier that follows
 * in the raw line text. Language-agnostic via grammar tokens.
 */
object SymbolExtractor {

    /** Declaration keywords that introduce a named symbol. */
    private val FUNCTION_KEYWORDS = setOf(
        "fun",      // Kotlin
        "def",      // Python
        "function", // JavaScript / TypeScript
        "func",     // Go
        "fn",       // Rust
    )

    private val CLASS_KEYWORDS = setOf(
        "class", "object", "interface", "enum", "typealias", // Kotlin
        "struct", "trait", "impl", "type",                   // Rust / Go
    )

    private val ALL_DECL_KEYWORDS = FUNCTION_KEYWORDS + CLASS_KEYWORDS

    /**
     * Extract all named symbols from [lines] using [grammar] for tokenisation.
     *
     * @return list of [SymbolInfo] in source order
     */
    fun extract(lines: List<String>, grammar: Grammar): List<SymbolInfo> {
        if (grammar.rules.isEmpty()) return emptyList() // plain text — no symbols

        val symbols = mutableListOf<SymbolInfo>()
        for ((lineIdx, line) in lines.withIndex()) {
            val trimmed = line.trimStart()
            if (trimmed.isEmpty()) continue

            val tokens = SyntaxEngine.tokenizeLine(trimmed, grammar)
            for (token in tokens) {
                if (token.type != TokenType.KEYWORD) continue
                val keyword = trimmed.substring(token.start, minOf(token.start + token.length, trimmed.length))
                if (keyword !in ALL_DECL_KEYWORDS) continue

                val kind = when (keyword) {
                    in FUNCTION_KEYWORDS -> SymbolKind.FUNCTION
                    in CLASS_KEYWORDS    -> SymbolKind.CLASS
                    else                 -> SymbolKind.PROPERTY
                }

                // Find the next identifier in the raw line immediately after the keyword.
                val name = nextIdentifier(trimmed, token.start + token.length) ?: continue
                symbols.add(SymbolInfo(name = name, kind = kind, line = lineIdx.toLong()))
            }
        }
        return symbols
    }

    /**
     * Scan [line] starting at [from], skipping whitespace, and return the first
     * run of word characters (letters, digits, underscore), or null if none found
     * before a non-identifier character that cannot start an identifier.
     */
    private fun nextIdentifier(line: String, from: Int): String? {
        var i = from
        // Skip whitespace
        while (i < line.length && line[i].isWhitespace()) i++
        if (i >= line.length) return null
        val start = i
        while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_')) i++
        if (i == start) return null
        return line.substring(start, i)
    }
}

data class SymbolInfo(
    val name: String,
    val kind: SymbolKind,
    val line: Long,
)

enum class SymbolKind { FUNCTION, CLASS, PROPERTY }
