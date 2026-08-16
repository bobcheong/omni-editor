package com.omnieditor.core.diff

import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import org.junit.Test

class BracketMatcherTest {

    @Test
    fun `matches parentheses forward`() {
        BracketMatcher.findMatch("foo(bar)", 3) shouldBe 7
    }

    @Test
    fun `matches parentheses backward`() {
        BracketMatcher.findMatch("foo(bar)", 7) shouldBe 3
    }

    @Test
    fun `matches nested brackets`() {
        BracketMatcher.findMatch("{a{b}c}", 0) shouldBe 6
    }

    @Test
    fun `returns null for non-bracket`() {
        BracketMatcher.findMatch("hello", 2).shouldBeNull()
    }

    @Test
    fun `returns null for unmatched bracket`() {
        BracketMatcher.findMatch("(unclosed", 0).shouldBeNull()
    }

    @Test
    fun `matches square brackets`() {
        BracketMatcher.findMatch("a[b]c", 1) shouldBe 3
    }
}
