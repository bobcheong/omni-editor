package com.omnieditor.feature.compare

import com.omnieditor.core.model.ColumnRange
import com.omnieditor.core.model.Granularity
import com.omnieditor.core.model.LinePattern
import com.omnieditor.core.model.MarkerPair
import com.omnieditor.core.model.MatchPosition
import com.omnieditor.core.model.RuleSet
import com.omnieditor.core.model.WhitespaceRule
import io.kotest.matchers.shouldBe
import org.junit.Test

class RuleSetSheetTest {

    @Test
    fun `countActiveRules returns zero for defaults`() {
        countActiveRules(RuleSet.DEFAULT) shouldBe 0
    }

    @Test
    fun `countActiveRules counts every non-default field`() {
        val rules = RuleSet(
            ignoreCase = true,
            whitespace = WhitespaceRule.ALL,
            ignoreBlankLines = true,
            ignoreLineEndings = false,
            linePatterns = listOf(LinePattern(MatchPosition.BEGINS_WITH, "#")),
            betweenMarkers = listOf(MarkerPair("/*", "*/")),
            headSkip = 2,
            tailSkip = 3,
            columnRanges = listOf(ColumnRange(1, 10)),
            granularity = Granularity.CHARACTER,
        )
        countActiveRules(rules) shouldBe 10
    }

    @Test
    fun `countActiveRules counts individual changes`() {
        countActiveRules(RuleSet(ignoreCase = true)) shouldBe 1
        countActiveRules(RuleSet(whitespace = WhitespaceRule.TRAILING)) shouldBe 1
        countActiveRules(RuleSet(ignoreBlankLines = true)) shouldBe 1
        countActiveRules(RuleSet(ignoreLineEndings = false)) shouldBe 1
        countActiveRules(RuleSet(headSkip = 5)) shouldBe 1
        countActiveRules(RuleSet(tailSkip = 1)) shouldBe 1
        countActiveRules(RuleSet(granularity = Granularity.LINE)) shouldBe 1
        countActiveRules(
            RuleSet(linePatterns = listOf(LinePattern(MatchPosition.CONTAINS, "x"))),
        ) shouldBe 1
        countActiveRules(
            RuleSet(betweenMarkers = listOf(MarkerPair("a", "b"))),
        ) shouldBe 1
        countActiveRules(
            RuleSet(columnRanges = listOf(ColumnRange(1, 5))),
        ) shouldBe 1
    }

    @Test
    fun `ignoreLineEndings default is true so disabling it counts as active`() {
        // The default has ignoreLineEndings=true, so setting it false should count
        countActiveRules(RuleSet(ignoreLineEndings = false)) shouldBe 1
        // Keeping it true should not count
        countActiveRules(RuleSet(ignoreLineEndings = true)) shouldBe 0
    }
}
