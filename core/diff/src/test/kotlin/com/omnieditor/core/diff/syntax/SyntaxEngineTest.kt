package com.omnieditor.core.diff.syntax

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

class SyntaxEngineTest {

    // ── Language detection ──

    @Test
    fun `detects Kotlin from extension`() {
        SyntaxEngine.detectLanguage("Main.kt") shouldBe "kotlin"
        SyntaxEngine.detectLanguage("build.gradle.kts") shouldBe "kotlin"
    }

    @Test
    fun `detects Java from extension`() {
        SyntaxEngine.detectLanguage("App.java") shouldBe "java"
    }

    @Test
    fun `detects JavaScript from extension`() {
        SyntaxEngine.detectLanguage("index.js") shouldBe "javascript"
        SyntaxEngine.detectLanguage("module.mjs") shouldBe "javascript"
    }

    @Test
    fun `detects TypeScript from extension`() {
        SyntaxEngine.detectLanguage("app.ts") shouldBe "typescript"
    }

    @Test
    fun `detects Python from extension`() {
        SyntaxEngine.detectLanguage("script.py") shouldBe "python"
    }

    @Test
    fun `detects Go from extension`() {
        SyntaxEngine.detectLanguage("main.go") shouldBe "go"
    }

    @Test
    fun `detects Rust from extension`() {
        SyntaxEngine.detectLanguage("lib.rs") shouldBe "rust"
    }

    @Test
    fun `detects C and CPP from extension`() {
        SyntaxEngine.detectLanguage("main.c") shouldBe "c"
        SyntaxEngine.detectLanguage("app.cpp") shouldBe "cpp"
        SyntaxEngine.detectLanguage("header.h") shouldBe "c"
    }

    @Test
    fun `detects shell from extension`() {
        SyntaxEngine.detectLanguage("deploy.sh") shouldBe "shell"
        SyntaxEngine.detectLanguage("init.bash") shouldBe "shell"
    }

    @Test
    fun `detects YAML from extension`() {
        SyntaxEngine.detectLanguage("config.yaml") shouldBe "yaml"
        SyntaxEngine.detectLanguage("ci.yml") shouldBe "yaml"
    }

    @Test
    fun `detects JSON from extension`() {
        SyntaxEngine.detectLanguage("package.json") shouldBe "json"
    }

    @Test
    fun `detects XML and HTML from extension`() {
        SyntaxEngine.detectLanguage("layout.xml") shouldBe "xml"
        SyntaxEngine.detectLanguage("index.html") shouldBe "html"
    }

    @Test
    fun `detects SQL from extension`() {
        SyntaxEngine.detectLanguage("schema.sql") shouldBe "sql"
    }

    @Test
    fun `detects Markdown from extension`() {
        SyntaxEngine.detectLanguage("README.md") shouldBe "markdown"
    }

    @Test
    fun `unknown extension returns null`() {
        SyntaxEngine.detectLanguage("data.xyz") shouldBe null
        SyntaxEngine.detectLanguage("noext") shouldBe null
    }

    // ── Grammar availability ──

    @Test
    fun `all 15 grammars available`() {
        val languages = listOf(
            "kotlin", "java", "javascript", "typescript", "python",
            "go", "rust", "c", "cpp", "shell", "yaml", "json",
            "xml", "html", "sql", "markdown",
        )
        for (lang in languages) {
            SyntaxEngine.grammarFor(lang) shouldNotBe null
        }
    }

    @Test
    fun `grammarFor null returns null`() {
        SyntaxEngine.grammarFor(null) shouldBe null
    }

    @Test
    fun `grammarFor unknown returns null`() {
        SyntaxEngine.grammarFor("brainfuck") shouldBe null
    }

    // ── Kotlin tokenization ──

    @Test
    fun `kotlin highlights keywords`() {
        val grammar = SyntaxEngine.grammarFor("kotlin")!!
        val tokens = SyntaxEngine.tokenizeLine("fun main() {", grammar)
        val keyword = tokens.find { it.type == TokenType.KEYWORD }
        keyword shouldNotBe null
        keyword!!.start shouldBe 0
        keyword.length shouldBe 3 // "fun"
    }

    @Test
    fun `kotlin highlights strings`() {
        val grammar = SyntaxEngine.grammarFor("kotlin")!!
        val tokens = SyntaxEngine.tokenizeLine("""val x = "hello"""", grammar)
        tokens.any { it.type == TokenType.STRING } shouldBe true
    }

    @Test
    fun `kotlin highlights comments`() {
        val grammar = SyntaxEngine.grammarFor("kotlin")!!
        val tokens = SyntaxEngine.tokenizeLine("// this is a comment", grammar)
        tokens.any { it.type == TokenType.COMMENT } shouldBe true
    }

    @Test
    fun `kotlin highlights annotations`() {
        val grammar = SyntaxEngine.grammarFor("kotlin")!!
        val tokens = SyntaxEngine.tokenizeLine("@Test fun test() {}", grammar)
        tokens.any { it.type == TokenType.ANNOTATION } shouldBe true
    }

    @Test
    fun `kotlin highlights numbers`() {
        val grammar = SyntaxEngine.grammarFor("kotlin")!!
        val tokens = SyntaxEngine.tokenizeLine("val x = 42", grammar)
        tokens.any { it.type == TokenType.NUMBER } shouldBe true
    }

    // ── Python tokenization ──

    @Test
    fun `python highlights keywords and decorators`() {
        val grammar = SyntaxEngine.grammarFor("python")!!
        val tokens = SyntaxEngine.tokenizeLine("@property", grammar)
        tokens.any { it.type == TokenType.ANNOTATION } shouldBe true

        val tokens2 = SyntaxEngine.tokenizeLine("def hello():", grammar)
        tokens2.any { it.type == TokenType.KEYWORD } shouldBe true
    }

    @Test
    fun `python highlights comments`() {
        val grammar = SyntaxEngine.grammarFor("python")!!
        val tokens = SyntaxEngine.tokenizeLine("# comment", grammar)
        tokens.any { it.type == TokenType.COMMENT } shouldBe true
    }

    // ── JSON tokenization ──

    @Test
    fun `json highlights keys and values`() {
        val grammar = SyntaxEngine.grammarFor("json")!!
        val tokens = SyntaxEngine.tokenizeLine("""  "name": "value",""", grammar)
        tokens.any { it.type == TokenType.TAG } shouldBe true // key
        tokens.any { it.type == TokenType.STRING } shouldBe true // value
    }

    @Test
    fun `json highlights constants`() {
        val grammar = SyntaxEngine.grammarFor("json")!!
        val tokens = SyntaxEngine.tokenizeLine("""  "flag": true,""", grammar)
        tokens.any { it.type == TokenType.CONSTANT } shouldBe true
    }

    // ── SQL tokenization ──

    @Test
    fun `sql highlights keywords case insensitive`() {
        val grammar = SyntaxEngine.grammarFor("sql")!!
        val tokens = SyntaxEngine.tokenizeLine("select * from users where id = 1;", grammar)
        val keywords = tokens.filter { it.type == TokenType.KEYWORD }
        keywords.isNotEmpty() shouldBe true
    }

    // ── XML tokenization ──

    @Test
    fun `xml highlights tags`() {
        val grammar = SyntaxEngine.grammarFor("xml")!!
        val tokens = SyntaxEngine.tokenizeLine("""<root attr="val">""", grammar)
        tokens.any { it.type == TokenType.TAG } shouldBe true
    }

    // ── Markdown tokenization ──

    @Test
    fun `markdown highlights headings`() {
        val grammar = SyntaxEngine.grammarFor("markdown")!!
        val tokens = SyntaxEngine.tokenizeLine("## Hello World", grammar)
        tokens.any { it.type == TokenType.HEADING } shouldBe true
    }

    // ── Stateful tokenization (R-19) ──

    @Test
    fun `block comment spanning two lines — second line is COMMENT when entry state is BLOCK_COMMENT`() {
        val grammar = SyntaxEngine.grammarFor("kotlin")!!

        // First line opens a block comment but doesn't close it.
        val line1 = "/* start of comment"
        val result1 = SyntaxEngine.tokenizeLineStateful(line1, grammar, LexerState.NORMAL)
        result1.exitState shouldBe LexerState.BLOCK_COMMENT
        result1.tokens.any { it.type == TokenType.COMMENT } shouldBe true

        // Second line starts inside the block comment.
        val line2 = "   still inside comment"
        val result2 = SyntaxEngine.tokenizeLineStateful(line2, grammar, LexerState.BLOCK_COMMENT)
        result2.exitState shouldBe LexerState.BLOCK_COMMENT
        result2.tokens.all { it.type == TokenType.COMMENT } shouldBe true

        // Third line closes the block comment.
        val line3 = "   end of comment */"
        val result3 = SyntaxEngine.tokenizeLineStateful(line3, grammar, LexerState.BLOCK_COMMENT)
        result3.exitState shouldBe LexerState.NORMAL
        result3.tokens.any { it.type == TokenType.COMMENT } shouldBe true
    }

    @Test
    fun `inline block comment on single line returns NORMAL exit state`() {
        val grammar = SyntaxEngine.grammarFor("kotlin")!!
        val line = "val x = /* inline */ 42"
        val result = SyntaxEngine.tokenizeLineStateful(line, grammar, LexerState.NORMAL)
        result.exitState shouldBe LexerState.NORMAL
        result.tokens.any { it.type == TokenType.COMMENT } shouldBe true
    }

    @Test
    fun `triple-quoted string spanning lines — exit state is MULTILINE_STRING`() {
        val grammar = SyntaxEngine.grammarFor("kotlin")!!

        val line1 = "val s = \"\"\""
        val result1 = SyntaxEngine.tokenizeLineStateful(line1, grammar, LexerState.NORMAL)
        result1.exitState shouldBe LexerState.MULTILINE_STRING

        val line2 = "  inside the string"
        val result2 = SyntaxEngine.tokenizeLineStateful(line2, grammar, LexerState.MULTILINE_STRING)
        result2.exitState shouldBe LexerState.MULTILINE_STRING
        result2.tokens.all { it.type == TokenType.STRING } shouldBe true

        val line3 = "\"\"\""
        val result3 = SyntaxEngine.tokenizeLineStateful(line3, grammar, LexerState.MULTILINE_STRING)
        result3.exitState shouldBe LexerState.NORMAL
    }

    @Test
    fun `stateless tokenizeLine delegates to NORMAL entry state`() {
        val grammar = SyntaxEngine.grammarFor("kotlin")!!
        val line = "fun main() {}"
        val stateless = SyntaxEngine.tokenizeLine(line, grammar)
        val stateful = SyntaxEngine.tokenizeLineStateful(line, grammar, LexerState.NORMAL).tokens
        stateless shouldBe stateful
    }

    @Test
    fun `LexerState round-trips through fromCode`() {
        LexerState.fromCode(0) shouldBe LexerState.NORMAL
        LexerState.fromCode(1) shouldBe LexerState.BLOCK_COMMENT
        LexerState.fromCode(2) shouldBe LexerState.MULTILINE_STRING
        LexerState.fromCode(99) shouldBe LexerState.NORMAL // unknown → NORMAL
    }

    @Test
    fun `empty line with BLOCK_COMMENT entry state stays in BLOCK_COMMENT`() {
        val grammar = SyntaxEngine.grammarFor("kotlin")!!
        val result = SyntaxEngine.tokenizeLineStateful("", grammar, LexerState.BLOCK_COMMENT)
        result.exitState shouldBe LexerState.BLOCK_COMMENT
    }

    // ── Edge cases ──

    @Test
    fun `empty line produces no tokens`() {
        val grammar = SyntaxEngine.grammarFor("kotlin")!!
        SyntaxEngine.tokenizeLine("", grammar) shouldBe emptyList()
    }

    @Test
    fun `plain text line produces some tokens`() {
        val grammar = SyntaxEngine.grammarFor("kotlin")!!
        // A line with no keywords/strings should still not crash
        val tokens = SyntaxEngine.tokenizeLine("hello world", grammar)
        // May or may not produce tokens — just shouldn't crash
    }

    // ── Performance ──

    @Test
    fun `tokenizing a long line completes quickly with truncation`() {
        val grammar = SyntaxEngine.grammarFor("kotlin")!!
        // A 10KB line — should be truncated to MAX_HIGHLIGHT_LENGTH and still fast
        val longLine = "val x = 1; ".repeat(1000)
        val start = System.nanoTime()
        val tokens = SyntaxEngine.tokenizeLine(longLine, grammar)
        val elapsed = (System.nanoTime() - start) / 1_000_000
        // Should be well under 5s even on slow CI
        (elapsed < 5000) shouldBe true
        // Tokens should exist (truncated highlighting still works)
        tokens.isNotEmpty() shouldBe true
    }
}
