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

    @Test
    fun `session round trips`() {
        val session = Session(
            id = "s1",
            name = "config compare",
            mode = CompareMode.TEXT,
            pinned = true,
            createdAt = 1_700_000_000,
            lastRunAt = 1_700_001_000,
            lastSummary = SessionSummary(hunkCount = 5, linesAdded = 3, linesChanged = 2),
            sources = listOf(
                SourceRef(id = "r1", kind = SourceKind.LOCAL, path = "/a.txt", label = "a.txt"),
                SourceRef(id = "r2", kind = SourceKind.LOCAL, path = "/b.txt", label = "b.txt"),
            ),
            bookmarks = listOf(Bookmark(line = 42, slot = Slot.LEFT, label = "important")),
        )
        val json = Json.encodeToString(Session.serializer(), session)
        Json.decodeFromString(Session.serializer(), json) shouldBe session
    }

    @Test
    fun `session defaults are sensible`() {
        val session = Session(id = "s2", name = "test", mode = CompareMode.EDITOR, createdAt = 0)
        session.pinned shouldBe false
        session.groupId shouldBe null
        session.lastRunAt shouldBe null
        session.displaySettings.layout shouldBe Layout.AUTO
        session.displaySettings.syncScroll shouldBe true
    }

    @Test
    fun `document meta round trips`() {
        val doc = DocumentMeta(
            id = "d1",
            sourceRefId = "r1",
            dirty = true,
            caretLine = 100,
            caretColumn = 25,
            encoding = "UTF-16LE",
            lineEnding = LineEnding.CRLF,
            language = "kotlin",
        )
        val json = Json.encodeToString(DocumentMeta.serializer(), doc)
        Json.decodeFromString(DocumentMeta.serializer(), json) shouldBe doc
    }

    @Test
    fun `line ending enum has correct chars`() {
        LineEnding.LF.chars shouldBe "\n"
        LineEnding.CRLF.chars shouldBe "\r\n"
        LineEnding.CR.chars shouldBe "\r"
    }

    @Test
    fun `display settings round trips`() {
        val settings = DisplaySettings(
            layout = Layout.SPLIT,
            wordWrap = true,
            showWhitespace = true,
            tabWidth = 2,
            syncScroll = false,
            granularity = Granularity.CHARACTER,
        )
        val json = Json.encodeToString(DisplaySettings.serializer(), settings)
        Json.decodeFromString(DisplaySettings.serializer(), json) shouldBe settings
    }

    @Test
    fun `compare mode covers all expected values`() {
        val modes = CompareMode.entries.map { it.name }.toSet()
        modes shouldBe setOf("TEXT", "FOLDER", "TABLE", "DOCUMENT", "BINARY", "EDITOR")
    }

    @Test
    fun `intra line range rejects invalid bounds`() {
        shouldThrow<IllegalArgumentException> { IntraLineRange(start = 5, end = 3, type = HunkType.CHANGED) }
    }

    @Test
    fun `intra line range accepts zero-width`() {
        val range = IntraLineRange(start = 5, end = 5, type = HunkType.ADDED)
        range.start shouldBe 5
        range.end shouldBe 5
    }

    @Test
    fun `omni error variants are exhaustive for spec section 13`() {
        // Verify all variants exist by constructing each one
        val ref = SourceRef(id = "x", kind = SourceKind.LOCAL, path = "/x", label = "x")
        val errors: List<OmniError> = listOf(
            OmniError.AccessRevoked(ref),
            OmniError.NotReachable("c1", NetCause.DNS),
            OmniError.AuthFailed("c1", AuthKind.PASSWORD),
            OmniError.HostKeyChanged("c1", "sha256:abc"),
            OmniError.Unsupported("rar", "archive module"),
            OmniError.TooLarge(5_000_000_000, 2_000_000_000),
            OmniError.NoTextLayer(ref),
            OmniError.WriteFailed(ref, partial = true),
            OmniError.DecodeFailed(ref, "Shift_JIS"),
            OmniError.Cancelled,
        )
        errors.size shouldBe 10
    }

    @Test
    fun `session summary round trips`() {
        val summary = SessionSummary(
            hunkCount = 42,
            linesAdded = 10,
            linesRemoved = 5,
            linesChanged = 27,
            engineMode = EngineMode.BLOCK_MATCH,
            stale = true,
        )
        val json = Json.encodeToString(SessionSummary.serializer(), summary)
        Json.decodeFromString(SessionSummary.serializer(), json) shouldBe summary
    }
}
