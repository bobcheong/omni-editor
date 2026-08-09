package com.omnieditor.core.diff

import com.omnieditor.core.model.ColumnRange
import com.omnieditor.core.model.LinePattern
import com.omnieditor.core.model.MarkerPair
import com.omnieditor.core.model.MatchPosition
import com.omnieditor.core.model.RuleSet
import com.omnieditor.core.model.WhitespaceRule
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

class NormaliserTest {

    private fun norm(line: String, rules: RuleSet, lineIndex: Long = 0, totalLines: Long = 10) =
        Normaliser.normaliseLine(line, lineIndex, totalLines, rules)

    private fun hashStr(s: String): Long {
        var hash = -3750763034362895579L
        for (b in s.toByteArray()) {
            hash = hash xor (b.toLong() and 0xFF)
            hash *= 1099511628211L
        }
        return hash
    }

    // ── Case folding ──

    @Test
    fun `ignoreCase folds to lowercase`() {
        val rules = RuleSet(ignoreCase = true)
        norm("Hello World", rules).text shouldBe "hello world"
    }

    @Test
    fun `ignoreCase makes case-different lines hash equal`() {
        val rules = RuleSet(ignoreCase = true)
        val h1 = hashStr(norm("Hello", rules).text)
        val h2 = hashStr(norm("HELLO", rules).text)
        h1 shouldBe h2
    }

    @Test
    fun `ignoreCase off preserves case`() {
        val rules = RuleSet(ignoreCase = false)
        norm("Hello", rules).text shouldBe "Hello"
    }

    // ── Whitespace rules ──

    @Test
    fun `whitespace NONE preserves all whitespace`() {
        val rules = RuleSet(whitespace = WhitespaceRule.NONE)
        norm("  hello  ", rules).text shouldBe "  hello  "
    }

    @Test
    fun `whitespace TRAILING removes trailing only`() {
        val rules = RuleSet(whitespace = WhitespaceRule.TRAILING)
        norm("  hello  ", rules).text shouldBe "  hello"
    }

    @Test
    fun `whitespace LEADING_AND_TRAILING trims both`() {
        val rules = RuleSet(whitespace = WhitespaceRule.LEADING_AND_TRAILING)
        norm("  hello  ", rules).text shouldBe "hello"
    }

    @Test
    fun `whitespace ALL removes all whitespace`() {
        val rules = RuleSet(whitespace = WhitespaceRule.ALL)
        norm("  he llo  wo rld  ", rules).text shouldBe "helloworld"
    }

    @Test
    fun `whitespace ALL makes whitespace-different lines hash equal`() {
        val rules = RuleSet(whitespace = WhitespaceRule.ALL)
        val h1 = hashStr(norm("int x = 1;", rules).text)
        val h2 = hashStr(norm("int  x  =  1 ;", rules).text)
        h1 shouldBe h2
    }

    // ── Blank lines ──

    @Test
    fun `blank line normalises to empty`() {
        val rules = RuleSet()
        norm("", rules).text shouldBe ""
        norm("   ", rules).text shouldBe "   "
    }

    @Test
    fun `blank line with whitespace ALL normalises to empty`() {
        val rules = RuleSet(whitespace = WhitespaceRule.ALL)
        norm("   ", rules).text shouldBe ""
    }

    // ── Line terminators ──

    @Test
    fun `line terminators are always stripped`() {
        val rules = RuleSet()
        norm("hello\r\n", rules).text shouldBe "hello"
        norm("hello\n", rules).text shouldBe "hello"
        norm("hello\r", rules).text shouldBe "hello"
    }

    // ── Head and tail skip ──

    @Test
    fun `headSkip excludes first N lines`() {
        val rules = RuleSet(headSkip = 3)
        norm("line0", rules, lineIndex = 0, totalLines = 10).skipped shouldBe true
        norm("line2", rules, lineIndex = 2, totalLines = 10).skipped shouldBe true
        norm("line3", rules, lineIndex = 3, totalLines = 10).skipped shouldBe false
    }

    @Test
    fun `tailSkip excludes last N lines`() {
        val rules = RuleSet(tailSkip = 2)
        norm("line7", rules, lineIndex = 7, totalLines = 10).skipped shouldBe false
        norm("line8", rules, lineIndex = 8, totalLines = 10).skipped shouldBe true
        norm("line9", rules, lineIndex = 9, totalLines = 10).skipped shouldBe true
    }

    // ── R-06: tailSkip under newlines + 1 model ──

    @Test
    fun `R-06 tailSkip 1 on newline-terminated file skips trailing empty line`() {
        // "a\nb\nc\n" is 4 lines under D-7: "a", "b", "c", ""
        // tailSkip = 1 skips lineIndex >= totalLines - 1 = 3, i.e., the empty trailing line.
        // A difference on line "c" (index 2) is still reported.
        val rules = RuleSet(tailSkip = 1)
        // Line 2 ("c") is NOT skipped — it's a content line
        norm("c", rules, lineIndex = 2, totalLines = 4).skipped shouldBe false
        // Line 3 ("") IS skipped — it's the trailing empty line
        norm("", rules, lineIndex = 3, totalLines = 4).skipped shouldBe true
    }

    @Test
    fun `R-06 tailSkip 2 on newline-terminated file skips last content line and trailing empty`() {
        // "a\nb\nc\n" is 4 lines: "a"(0), "b"(1), "c"(2), ""(3)
        // tailSkip = 2 skips lineIndex >= 4 - 2 = 2, i.e., lines 2 and 3
        val rules = RuleSet(tailSkip = 2)
        norm("b", rules, lineIndex = 1, totalLines = 4).skipped shouldBe false
        norm("c", rules, lineIndex = 2, totalLines = 4).skipped shouldBe true
        norm("", rules, lineIndex = 3, totalLines = 4).skipped shouldBe true
    }

    // ── Line pattern exclusion ──

    @Test
    fun `begins_with pattern excludes matching lines`() {
        val rules = RuleSet(linePatterns = listOf(LinePattern(MatchPosition.BEGINS_WITH, "#")))
        norm("# comment", rules).skipped shouldBe true
        norm("not a comment", rules).skipped shouldBe false
    }

    @Test
    fun `contains pattern excludes matching lines`() {
        val rules = RuleSet(linePatterns = listOf(LinePattern(MatchPosition.CONTAINS, "TODO")))
        norm("fix this // TODO later", rules).skipped shouldBe true
        norm("fix this // done", rules).skipped shouldBe false
    }

    @Test
    fun `ends_with pattern excludes matching lines`() {
        val rules = RuleSet(linePatterns = listOf(LinePattern(MatchPosition.ENDS_WITH, ";")))
        norm("int x = 1;", rules).skipped shouldBe true
        norm("if (true) {", rules).skipped shouldBe false
    }

    @Test
    fun `pattern case sensitivity works`() {
        val rules = RuleSet(linePatterns = listOf(
            LinePattern(MatchPosition.CONTAINS, "todo", caseSensitive = false)
        ))
        norm("fix TODO later", rules).skipped shouldBe true
        norm("fix todo later", rules).skipped shouldBe true
    }

    @Test
    fun `pattern case sensitive by default`() {
        val rules = RuleSet(linePatterns = listOf(
            LinePattern(MatchPosition.CONTAINS, "TODO", caseSensitive = true)
        ))
        norm("fix TODO later", rules).skipped shouldBe true
        norm("fix todo later", rules).skipped shouldBe false
    }

    // ── Between markers ──

    @Test
    fun `between markers removes content on same line`() {
        val rules = RuleSet(betweenMarkers = listOf(MarkerPair("/*", "*/")))
        norm("code /* comment */ more", rules).text shouldBe "code  more"
    }

    @Test
    fun `between markers spans multiple lines`() {
        val rules = RuleSet(betweenMarkers = listOf(MarkerPair("/*", "*/")))
        val r1 = norm("start /* begin", rules, lineIndex = 0)
        r1.text shouldBe "start "
        r1.inMarker shouldBe true

        val r2 = Normaliser.normaliseLine("middle of comment", 1, 10, rules, inMarker = true)
        r2.text shouldBe ""
        r2.inMarker shouldBe true

        val r3 = Normaliser.normaliseLine("end */ after", 2, 10, rules, inMarker = true)
        r3.text shouldBe " after"
        r3.inMarker shouldBe false
    }

    @Test
    fun `between markers handles nested on same line`() {
        val rules = RuleSet(betweenMarkers = listOf(MarkerPair("<!--", "-->")))
        norm("a <!-- x --> b <!-- y --> c", rules).text shouldBe "a  b  c"
    }

    // ── Column ranges ──

    @Test
    fun `column range compare extracts specified columns`() {
        val rules = RuleSet(columnRanges = listOf(ColumnRange(1, 5, compare = true)))
        norm("ABCDEFGHIJ", rules).text shouldBe "ABCDE"
    }

    @Test
    fun `column range ignore removes specified columns`() {
        val rules = RuleSet(columnRanges = listOf(ColumnRange(3, 5, compare = false)))
        norm("ABCDEFGHIJ", rules).text shouldBe "ABFGHIJ"
    }

    @Test
    fun `column range handles short lines`() {
        val rules = RuleSet(columnRanges = listOf(ColumnRange(1, 20, compare = true)))
        norm("ABC", rules).text shouldBe "ABC"
    }

    @Test
    fun `column range makes timestamp-different lines hash equal`() {
        // Columns 1-19 are a timestamp, columns 20+ are the message
        val rules = RuleSet(columnRanges = listOf(ColumnRange(1, 19, compare = false)))
        val h1 = hashStr(norm("2026-01-01T00:00:00 message one", rules).text)
        val h2 = hashStr(norm("2026-06-15T12:30:45 message one", rules).text)
        h1 shouldBe h2
    }

    // ── Combined rules ──

    @Test
    fun `combined case and whitespace`() {
        val rules = RuleSet(ignoreCase = true, whitespace = WhitespaceRule.ALL)
        val h1 = hashStr(norm("Hello World", rules).text)
        val h2 = hashStr(norm("  hello   world  ", rules).text)
        h1 shouldBe h2
    }

    @Test
    fun `headSkip plus pattern`() {
        val rules = RuleSet(
            headSkip = 2,
            linePatterns = listOf(LinePattern(MatchPosition.BEGINS_WITH, "#")),
        )
        // Line 0: skipped by headSkip
        norm("anything", rules, lineIndex = 0, totalLines = 10).skipped shouldBe true
        // Line 3: not skipped by headSkip, but matches pattern
        norm("# comment", rules, lineIndex = 3, totalLines = 10).skipped shouldBe true
        // Line 3: not skipped by headSkip, no pattern match
        norm("code", rules, lineIndex = 3, totalLines = 10).skipped shouldBe false
    }

    // ── normaliseHashes ──

    @Test
    fun `normaliseHashes produces correct number of hashes`() {
        val lines = listOf("alpha", "beta", "gamma")
        val result = Normaliser.normaliseHashes(
            lineCount = 3,
            lineReader = { lines[it.toInt()] },
            lineHasher = ::hashStr,
            rules = RuleSet.DEFAULT,
        )
        result.hashes.size shouldBe 3
        result.isBlank.size shouldBe 3
    }

    @Test
    fun `normaliseHashes marks blank lines`() {
        val lines = listOf("alpha", "", "gamma")
        val result = Normaliser.normaliseHashes(
            lineCount = 3,
            lineReader = { lines[it.toInt()] },
            lineHasher = ::hashStr,
            rules = RuleSet.DEFAULT,
        )
        result.isBlank[0] shouldBe false
        result.isBlank[1] shouldBe true
        result.isBlank[2] shouldBe false
    }

    @Test
    fun `normaliseHashes with ignoreCase makes case-different lines equal`() {
        val lines1 = listOf("Hello", "World")
        val lines2 = listOf("HELLO", "WORLD")
        val rules = RuleSet(ignoreCase = true)
        val r1 = Normaliser.normaliseHashes(2, { lines1[it.toInt()] }, ::hashStr, rules)
        val r2 = Normaliser.normaliseHashes(2, { lines2[it.toInt()] }, ::hashStr, rules)
        r1.hashes[0] shouldBe r2.hashes[0]
        r1.hashes[1] shouldBe r2.hashes[1]
    }

    // ── Property: enabling a rule never increases differences ──

    @Test
    fun `property - ignoreCase never increases differences`() {
        verifyRuleNeverIncreasesDiffs(RuleSet(ignoreCase = true))
    }

    @Test
    fun `property - whitespace TRAILING never increases differences`() {
        verifyRuleNeverIncreasesDiffs(RuleSet(whitespace = WhitespaceRule.TRAILING))
    }

    @Test
    fun `property - whitespace ALL never increases differences`() {
        verifyRuleNeverIncreasesDiffs(RuleSet(whitespace = WhitespaceRule.ALL))
    }

    @Test
    fun `property - headSkip never increases differences`() {
        verifyRuleNeverIncreasesDiffs(RuleSet(headSkip = 2))
    }

    @Test
    fun `property - tailSkip never increases differences`() {
        verifyRuleNeverIncreasesDiffs(RuleSet(tailSkip = 2))
    }

    @Test
    fun `property - begins_with pattern never increases differences`() {
        verifyRuleNeverIncreasesDiffs(
            RuleSet(linePatterns = listOf(LinePattern(MatchPosition.BEGINS_WITH, "#")))
        )
    }

    @Test
    fun `property - column range ignore never increases differences`() {
        verifyRuleNeverIncreasesDiffs(
            RuleSet(columnRanges = listOf(ColumnRange(1, 5, compare = false)))
        )
    }

    /**
     * Property test: given a pair of files, enabling a rule should never produce
     * MORE different hash pairs than the default rules.
     */
    private fun verifyRuleNeverIncreasesDiffs(rules: RuleSet) {
        // Test pairs where the rule should help
        val testPairs = listOf(
            listOf("# header", "alpha", "beta  ", "GAMMA") to
                listOf("# different header", "alpha", "beta", "gamma"),
            listOf("  int x = 1;  ", "// comment", "  int y = 2;  ") to
                listOf("int x=1;", "// different comment", "int y=2;"),
            listOf("line1", "line2", "line3", "line4", "line5", "line6", "line7", "line8", "line9", "line10") to
                listOf("LINE1", "LINE2", "LINE3", "LINE4", "LINE5", "LINE6", "LINE7", "LINE8", "LINE9", "LINE10"),
        )

        for ((left, right) in testPairs) {
            val minLen = minOf(left.size, right.size)
            val defaultRules = RuleSet.DEFAULT
            val defaultDiffs = countDiffs(left, right, defaultRules)
            val ruleDiffs = countDiffs(left, right, rules)
            // The rule should never INCREASE the number of differences
            assert(ruleDiffs <= defaultDiffs) {
                "Rule $rules increased diffs from $defaultDiffs to $ruleDiffs"
            }
        }
    }

    private fun countDiffs(left: List<String>, right: List<String>, rules: RuleSet): Int {
        val lHashes = Normaliser.normaliseHashes(
            left.size.toLong(), { left[it.toInt()] }, ::hashStr, rules
        )
        val rHashes = Normaliser.normaliseHashes(
            right.size.toLong(), { right[it.toInt()] }, ::hashStr, rules
        )
        var diffs = 0
        val minLen = minOf(lHashes.hashes.size, rHashes.hashes.size)
        for (i in 0 until minLen) {
            if (lHashes.hashes[i] != rHashes.hashes[i]) diffs++
        }
        // Lines only in one side are also differences
        diffs += kotlin.math.abs(lHashes.hashes.size - rHashes.hashes.size)
        return diffs
    }
}
