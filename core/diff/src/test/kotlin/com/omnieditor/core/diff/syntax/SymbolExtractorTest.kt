package com.omnieditor.core.diff.syntax

import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import org.junit.Test

class SymbolExtractorTest {

    @Test
    fun `extracts Kotlin function declarations`() {
        val lines = listOf(
            "package com.example",
            "",
            "fun greet(name: String): String {",
            "    return \"Hello \$name\"",
            "}",
            "",
            "fun farewell() {",
            "    println(\"bye\")",
            "}",
        )
        val grammar = Grammars.forExtension("kt")
        val symbols = SymbolExtractor.extract(lines, grammar)
        symbols shouldHaveSize 2
        symbols[0].name shouldBe "greet"
        symbols[0].kind shouldBe SymbolKind.FUNCTION
        symbols[0].line shouldBe 2L
        symbols[1].name shouldBe "farewell"
        symbols[1].line shouldBe 6L
    }

    @Test
    fun `extracts class declarations`() {
        val lines = listOf(
            "class Foo {",
            "    fun bar() {}",
            "}",
        )
        val grammar = Grammars.forExtension("kt")
        val symbols = SymbolExtractor.extract(lines, grammar)
        symbols.any { it.kind == SymbolKind.CLASS && it.name == "Foo" } shouldBe true
    }

    @Test
    fun `returns empty for plain text`() {
        val lines = listOf("just some text", "nothing special")
        val grammar = Grammars.forExtension("txt")
        val symbols = SymbolExtractor.extract(lines, grammar)
        symbols shouldHaveSize 0
    }
}
