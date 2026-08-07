package com.omnieditor.core.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.Test

class ModelTest {

    @Test
    fun `source ref round trips through json`() {
        val ref = SourceRef(
            id = "a1", kind = SourceKind.LOCAL, path = "/storage/emulated/0/notes.txt",
            label = "notes.txt", fingerprint = Fingerprint(120, 1_700_000_000, "ab12"),
        )
        val json = Json.encodeToString(SourceRef.serializer(), ref)
        Json.decodeFromString(SourceRef.serializer(), json) shouldBe ref
    }

    @Test
    fun `source ref rejects having neither a path nor a grant`() {
        shouldThrow<IllegalArgumentException> {
            SourceRef(id = "a2", kind = SourceKind.LOCAL, label = "nowhere")
        }
    }

    @Test
    fun `column range rejects inverted bounds`() {
        shouldThrow<IllegalArgumentException> { ColumnRange(from = 9, to = 4) }
    }

    @Test
    fun `rule set round trips`() {
        val rules = RuleSet(
            ignoreCase = true,
            whitespace = WhitespaceRule.ALL,
            linePatterns = listOf(LinePattern(MatchPosition.BEGINS_WITH, "#")),
            columnRanges = listOf(ColumnRange(1, 8, compare = false)),
        )
        val json = Json.encodeToString(RuleSet.serializer(), rules)
        Json.decodeFromString(RuleSet.serializer(), json) shouldBe rules
    }
}
