package com.omnieditor.core.diff.syntax

// ── C-like grammar builder ──

private fun cLikeGrammar(
    name: String,
    keywords: List<String>,
    types: List<String>,
    hasAnnotations: Boolean = false,
    annotationChar: Char = '@',
): Grammar {
    val rules = mutableListOf<GrammarRule>()

    // Line comments
    rules.add(GrammarRule("""//.*""", TokenType.COMMENT))
    // Block comments (single line only — multi-line handled by state)
    rules.add(GrammarRule("""/\*.*?\*/""", TokenType.COMMENT))

    // Annotations
    if (hasAnnotations) {
        rules.add(GrammarRule("""$annotationChar\w+""", TokenType.ANNOTATION))
    }

    // Strings
    rules.add(GrammarRule(""""(?:[^"\\]|\\.)*"""", TokenType.STRING))
    rules.add(GrammarRule("""'(?:[^'\\]|\\.)*'""", TokenType.STRING))

    // Numbers
    rules.add(GrammarRule("""\b0[xX][0-9a-fA-F_]+[Ll]?\b""", TokenType.NUMBER))
    rules.add(GrammarRule("""\b\d[\d_]*\.?[\d_]*(?:[eE][+-]?\d+)?[fFdDlL]?\b""", TokenType.NUMBER))

    // Types (before keywords so they take priority if overlapping)
    if (types.isNotEmpty()) {
        rules.add(GrammarRule("""\b(?:${types.joinToString("|")})\b""", TokenType.TYPE))
    }

    // Keywords
    rules.add(GrammarRule("""\b(?:${keywords.joinToString("|")})\b""", TokenType.KEYWORD))

    // Constants
    rules.add(GrammarRule("""\b(?:true|false|null|nil|undefined|NaN|Infinity)\b""", TokenType.CONSTANT))

    // Operators
    rules.add(GrammarRule("""[+\-*/%=!<>&|^~?:]+""", TokenType.OPERATOR))

    // Punctuation
    rules.add(GrammarRule("""[{}()\[\];,.]""", TokenType.PUNCTUATION))

    return Grammar(name, rules)
}

// ── Language-specific keyword/type lists ──

private val kotlinKeywords = listOf(
    "fun", "val", "var", "class", "object", "interface", "when", "if", "else",
    "for", "while", "do", "return", "break", "continue", "throw", "try", "catch",
    "finally", "import", "package", "as", "is", "in", "by", "data", "sealed",
    "enum", "abstract", "open", "override", "private", "protected", "internal",
    "public", "suspend", "inline", "crossinline", "noinline", "reified",
    "companion", "init", "get", "set", "constructor", "typealias", "annotation",
    "lateinit", "const", "tailrec", "operator", "infix", "expect", "actual",
)
private val kotlinTypes = listOf(
    "Int", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Char",
    "String", "Unit", "Nothing", "Any", "List", "Map", "Set", "Array",
)

private val javaKeywords = listOf(
    "class", "interface", "enum", "extends", "implements", "abstract", "final",
    "static", "void", "new", "return", "if", "else", "for", "while", "do",
    "switch", "case", "default", "break", "continue", "try", "catch", "finally",
    "throw", "throws", "import", "package", "public", "private", "protected",
    "synchronized", "volatile", "transient", "native", "this", "super",
    "instanceof", "assert", "record", "sealed", "permits", "var", "yield",
)
private val javaTypes = listOf(
    "int", "long", "short", "byte", "float", "double", "boolean", "char",
    "String", "Object", "Integer", "Long", "Double", "Boolean", "List", "Map",
)

private val jsKeywords = listOf(
    "function", "var", "let", "const", "if", "else", "for", "while", "do",
    "return", "break", "continue", "switch", "case", "default", "try", "catch",
    "finally", "throw", "new", "delete", "typeof", "instanceof", "in", "of",
    "class", "extends", "import", "export", "from", "async", "await", "yield",
    "this", "super", "with", "debugger", "void",
)
private val jsTypes = listOf("Array", "Object", "String", "Number", "Boolean", "Promise", "Map", "Set")

private val tsKeywords = jsKeywords + listOf(
    "type", "interface", "enum", "namespace", "declare", "readonly", "abstract",
    "implements", "as", "is", "keyof", "infer", "never", "unknown", "any",
)
private val tsTypes = jsTypes + listOf("string", "number", "boolean", "void", "never", "unknown", "any")

private val goKeywords = listOf(
    "func", "var", "const", "type", "struct", "interface", "if", "else", "for",
    "range", "switch", "case", "default", "return", "break", "continue", "go",
    "defer", "select", "chan", "map", "package", "import", "fallthrough", "goto",
)
private val goTypes = listOf(
    "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16",
    "uint32", "uint64", "float32", "float64", "complex64", "complex128",
    "string", "bool", "byte", "rune", "error", "any",
)

private val rustKeywords = listOf(
    "fn", "let", "mut", "const", "static", "struct", "enum", "impl", "trait",
    "pub", "mod", "use", "crate", "self", "super", "if", "else", "match",
    "for", "while", "loop", "return", "break", "continue", "as", "in",
    "where", "async", "await", "move", "ref", "type", "unsafe", "extern", "dyn",
)
private val rustTypes = listOf(
    "i8", "i16", "i32", "i64", "i128", "isize", "u8", "u16", "u32", "u64",
    "u128", "usize", "f32", "f64", "bool", "char", "str", "String",
    "Vec", "Box", "Option", "Result", "Self",
)

private val cKeywords = listOf(
    "auto", "break", "case", "char", "const", "continue", "default", "do",
    "else", "enum", "extern", "for", "goto", "if", "inline", "register",
    "restrict", "return", "sizeof", "static", "struct", "switch", "typedef",
    "union", "volatile", "while", "_Alignas", "_Alignof", "_Atomic", "_Bool",
    "_Complex", "_Generic", "_Imaginary", "_Noreturn", "_Static_assert",
)
private val cTypes = listOf(
    "int", "long", "short", "float", "double", "char", "void", "unsigned",
    "signed", "size_t", "ssize_t", "int8_t", "int16_t", "int32_t", "int64_t",
    "uint8_t", "uint16_t", "uint32_t", "uint64_t", "bool", "FILE",
)

private val cppKeywords = cKeywords + listOf(
    "class", "namespace", "template", "typename", "virtual", "override", "final",
    "public", "private", "protected", "new", "delete", "try", "catch", "throw",
    "using", "operator", "explicit", "friend", "mutable", "constexpr", "decltype",
    "noexcept", "nullptr", "concept", "requires", "co_await", "co_yield", "co_return",
)
private val cppTypes = cTypes + listOf(
    "string", "vector", "map", "set", "unique_ptr", "shared_ptr", "optional",
    "variant", "tuple", "array", "span", "string_view",
)

// ── Non-C-like grammars ──

private fun pythonGrammar(): Grammar {
    val keywords = listOf(
        "def", "class", "if", "elif", "else", "for", "while", "return", "yield",
        "import", "from", "as", "try", "except", "finally", "raise", "with",
        "pass", "break", "continue", "lambda", "and", "or", "not", "in", "is",
        "global", "nonlocal", "del", "assert", "async", "await", "match", "case",
    )
    return Grammar("python", listOf(
        GrammarRule("""#.*""", TokenType.COMMENT),
        GrammarRule("""\"\"\"[\s\S]*?\"\"\"""", TokenType.STRING),
        GrammarRule("""'''[\s\S]*?'''""", TokenType.STRING),
        GrammarRule(""""(?:[^"\\]|\\.)*"""", TokenType.STRING),
        GrammarRule("""'(?:[^'\\]|\\.)*'""", TokenType.STRING),
        GrammarRule("""\b\d[\d_]*\.?[\d_]*(?:[eE][+-]?\d+)?[jJ]?\b""", TokenType.NUMBER),
        GrammarRule("""\b0[xX][0-9a-fA-F_]+\b""", TokenType.NUMBER),
        GrammarRule("""@\w+""", TokenType.ANNOTATION),
        GrammarRule("""\b(?:${keywords.joinToString("|")})\b""", TokenType.KEYWORD),
        GrammarRule("""\b(?:True|False|None)\b""", TokenType.CONSTANT),
        GrammarRule("""\b(?:int|float|str|bool|list|dict|tuple|set|bytes|type|object)\b""", TokenType.TYPE),
        GrammarRule("""[+\-*/%=!<>&|^~@:]+""", TokenType.OPERATOR),
        GrammarRule("""[{}()\[\];,.]""", TokenType.PUNCTUATION),
    ))
}

private fun shellGrammar(): Grammar {
    return Grammar("shell", listOf(
        GrammarRule("""#.*""", TokenType.COMMENT),
        GrammarRule(""""(?:[^"\\]|\\.)*"""", TokenType.STRING),
        GrammarRule("""'[^']*'""", TokenType.STRING),
        GrammarRule("""\$\{[^}]+\}""", TokenType.ANNOTATION),
        GrammarRule("""\$\w+""", TokenType.ANNOTATION),
        GrammarRule("""\b\d+\b""", TokenType.NUMBER),
        GrammarRule("""\b(?:if|then|else|elif|fi|for|while|do|done|case|esac|in|function|return|exit|local|export|readonly|declare|typeset|unset|shift|source|eval|exec|set|trap|wait|until|select|coproc)\b""", TokenType.KEYWORD),
        GrammarRule("""[|&;><!()\[\]]+""", TokenType.OPERATOR),
    ))
}

private fun yamlGrammar(): Grammar {
    return Grammar("yaml", listOf(
        GrammarRule("""#.*""", TokenType.COMMENT),
        GrammarRule("""^[\w.-]+(?=\s*:)""", TokenType.TAG),
        GrammarRule(""""(?:[^"\\]|\\.)*"""", TokenType.STRING),
        GrammarRule("""'[^']*'""", TokenType.STRING),
        GrammarRule("""\b\d[\d.]*\b""", TokenType.NUMBER),
        GrammarRule("""\b(?:true|false|yes|no|null|~)\b""", TokenType.CONSTANT),
        GrammarRule("""[:\-|>]+""", TokenType.OPERATOR),
    ))
}

private fun jsonGrammar(): Grammar {
    return Grammar("json", listOf(
        GrammarRule(""""(?:[^"\\]|\\.)*"\s*(?=:)""", TokenType.TAG),
        GrammarRule(""""(?:[^"\\]|\\.)*"""", TokenType.STRING),
        GrammarRule("""-?\b\d[\d.]*(?:[eE][+-]?\d+)?\b""", TokenType.NUMBER),
        GrammarRule("""\b(?:true|false|null)\b""", TokenType.CONSTANT),
        GrammarRule("""[{}()\[\]:,]""", TokenType.PUNCTUATION),
    ))
}

private fun xmlGrammar(): Grammar {
    return Grammar("xml", listOf(
        GrammarRule("""<!--.*?-->""", TokenType.COMMENT),
        GrammarRule("""<!\[CDATA\[.*?]]>""", TokenType.STRING),
        GrammarRule("""</?[\w:.-]+""", TokenType.TAG),
        GrammarRule("""\b[\w:.-]+(?=\s*=)""", TokenType.ATTRIBUTE),
        GrammarRule(""""[^"]*"""", TokenType.STRING),
        GrammarRule("""'[^']*'""", TokenType.STRING),
        GrammarRule("""/?>""", TokenType.TAG),
        GrammarRule("""&\w+;""", TokenType.CONSTANT),
    ))
}

private fun sqlGrammar(): Grammar {
    val keywords = listOf(
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET",
        "DELETE", "CREATE", "DROP", "ALTER", "TABLE", "INDEX", "VIEW", "JOIN",
        "LEFT", "RIGHT", "INNER", "OUTER", "CROSS", "ON", "AND", "OR", "NOT",
        "IN", "EXISTS", "BETWEEN", "LIKE", "IS", "NULL", "AS", "ORDER", "BY",
        "GROUP", "HAVING", "LIMIT", "OFFSET", "UNION", "ALL", "DISTINCT",
        "CASE", "WHEN", "THEN", "ELSE", "END", "BEGIN", "COMMIT", "ROLLBACK",
        "GRANT", "REVOKE", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "UNIQUE",
        "CHECK", "DEFAULT", "CONSTRAINT", "CASCADE", "TRIGGER", "FUNCTION",
        "PROCEDURE", "RETURN", "DECLARE", "CURSOR", "FETCH", "OPEN", "CLOSE",
    )
    return Grammar("sql", listOf(
        GrammarRule("""--.*""", TokenType.COMMENT),
        GrammarRule("""/\*.*?\*/""", TokenType.COMMENT),
        GrammarRule("""'(?:[^'\\]|\\.)*'""", TokenType.STRING),
        GrammarRule("""\b\d[\d.]*\b""", TokenType.NUMBER),
        GrammarRule("""\b(?i)(?:${keywords.joinToString("|")})\b""", TokenType.KEYWORD),
        GrammarRule("""\b(?i)(?:INT|INTEGER|BIGINT|SMALLINT|FLOAT|DOUBLE|DECIMAL|NUMERIC|VARCHAR|CHAR|TEXT|BLOB|DATE|TIMESTAMP|BOOLEAN|SERIAL)\b""", TokenType.TYPE),
        GrammarRule("""\b(?:TRUE|FALSE|NULL)\b""", TokenType.CONSTANT),
        GrammarRule("""[=<>!+\-*/%]+""", TokenType.OPERATOR),
        GrammarRule("""[();,.]""", TokenType.PUNCTUATION),
    ))
}

private fun markdownGrammar(): Grammar {
    return Grammar("markdown", listOf(
        GrammarRule("""^#{1,6}\s+.*""", TokenType.HEADING),
        GrammarRule("""`[^`]+`""", TokenType.STRING),
        GrammarRule("""```.*""", TokenType.STRING),
        GrammarRule("""\*\*[^*]+\*\*""", TokenType.KEYWORD),
        GrammarRule("""\*[^*]+\*""", TokenType.ANNOTATION),
        GrammarRule("""\[([^\]]+)\]\([^)]+\)""", TokenType.TAG),
        GrammarRule("""^>\s+.*""", TokenType.COMMENT),
        GrammarRule("""^[-*+]\s+""", TokenType.OPERATOR),
        GrammarRule("""^\d+\.\s+""", TokenType.NUMBER),
    ))
}

/**
 * Built-in grammars for P1 languages (OE-TXT-5).
 *
 * Our own format — not imported from any third-party grammar system (IND-3).
 * Rules are ordered by priority: first match wins.
 */
internal val GRAMMARS: Map<String, Grammar> = buildMap {
    put("kotlin", cLikeGrammar("kotlin", kotlinKeywords, kotlinTypes, hasAnnotations = true))
    put("java", cLikeGrammar("java", javaKeywords, javaTypes, hasAnnotations = true))
    put("javascript", cLikeGrammar("javascript", jsKeywords, jsTypes))
    put("typescript", cLikeGrammar("typescript", tsKeywords, tsTypes))
    put("go", cLikeGrammar("go", goKeywords, goTypes))
    put("rust", cLikeGrammar("rust", rustKeywords, rustTypes, hasAnnotations = true, annotationChar = '#'))
    put("c", cLikeGrammar("c", cKeywords, cTypes))
    put("cpp", cLikeGrammar("cpp", cppKeywords, cppTypes))
    put("python", pythonGrammar())
    put("shell", shellGrammar())
    put("yaml", yamlGrammar())
    put("json", jsonGrammar())
    put("xml", xmlGrammar())
    put("html", xmlGrammar())
    put("sql", sqlGrammar())
    put("markdown", markdownGrammar())
}
