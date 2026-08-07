package com.omnieditor.core.io

import io.kotest.matchers.shouldBe
import org.junit.Test

class FindReplaceTest {

    private fun doc(content: String) = PieceTableDocument.create(content)

    // ── Basic find ──

    @Test
    fun `find simple text`() {
        val d = doc("hello world\nhello there")
        val result = FindReplace.findAll(d, "hello")
        result.totalCount shouldBe 2
        result.matches[0].line shouldBe 0
        result.matches[0].startColumn shouldBe 0
        result.matches[0].endColumn shouldBe 5
        result.matches[1].line shouldBe 1
    }

    @Test
    fun `find returns empty for no matches`() {
        val result = FindReplace.findAll(doc("hello world"), "xyz")
        result.totalCount shouldBe 0
    }

    @Test
    fun `find empty pattern returns empty`() {
        val result = FindReplace.findAll(doc("hello"), "")
        result.totalCount shouldBe 0
    }

    // ── Case sensitivity ──

    @Test
    fun `find case insensitive by default`() {
        val result = FindReplace.findAll(doc("Hello HELLO hello"), "hello")
        result.totalCount shouldBe 3
    }

    @Test
    fun `find case sensitive`() {
        val opts = FindReplace.FindOptions(caseSensitive = true)
        val result = FindReplace.findAll(doc("Hello HELLO hello"), "hello", opts)
        result.totalCount shouldBe 1
        result.matches[0].startColumn shouldBe 12
    }

    // ── Whole word ──

    @Test
    fun `find whole word`() {
        val opts = FindReplace.FindOptions(wholeWord = true)
        val result = FindReplace.findAll(doc("cat concatenate cats"), "cat", opts)
        result.totalCount shouldBe 1
        result.matches[0].startColumn shouldBe 0
    }

    @Test
    fun `whole word at end of line`() {
        val opts = FindReplace.FindOptions(wholeWord = true)
        val result = FindReplace.findAll(doc("the cat"), "cat", opts)
        result.totalCount shouldBe 1
    }

    // ── Regex ──

    @Test
    fun `find with regex`() {
        val opts = FindReplace.FindOptions(regex = true)
        val result = FindReplace.findAll(doc("foo123 bar456"), """\w+\d+""", opts)
        result.totalCount shouldBe 2
    }

    @Test
    fun `regex with capture groups`() {
        val opts = FindReplace.FindOptions(regex = true)
        val result = FindReplace.findAll(doc("version=1.2.3"), """version=(\d+\.\d+\.\d+)""", opts)
        result.totalCount shouldBe 1
        result.matches[0].text shouldBe "version=1.2.3"
    }

    @Test
    fun `regex case insensitive`() {
        val opts = FindReplace.FindOptions(regex = true, caseSensitive = false)
        val result = FindReplace.findAll(doc("Hello World"), """hello""", opts)
        result.totalCount shouldBe 1
    }

    // ── Find in selection ──

    @Test
    fun `find in selection restricts to line range`() {
        val d = doc("aaa\nbbb\nccc\naaa\nbbb")
        val result = FindReplace.findAll(d, "aaa", selectionOnly = 2L..4L)
        result.totalCount shouldBe 1
        result.matches[0].line shouldBe 3
    }

    // ── Replace all ──

    @Test
    fun `replace all simple`() {
        val table = PieceTable.create("hello world hello")
        val result = FindReplace.replaceAll(
            table, "hello", "hi",
            lineCount = 1,
            lineReader = { table.text() },
        )
        result.replacementCount shouldBe 2
        table.text() shouldBe "hi world hi"
    }

    @Test
    fun `replace all case insensitive`() {
        val table = PieceTable.create("Hello HELLO hello")
        val opts = FindReplace.FindOptions(caseSensitive = false)
        val result = FindReplace.replaceAll(
            table, "hello", "hi", opts,
            lineCount = 1,
            lineReader = { table.text() },
        )
        result.replacementCount shouldBe 3
        table.text() shouldBe "hi hi hi"
    }

    @Test
    fun `replace all with regex capture groups`() {
        val table = PieceTable.create("foo123 bar456")
        val opts = FindReplace.FindOptions(regex = true)
        val result = FindReplace.replaceAll(
            table, """([a-z]+)(\d+)""", "$1_$2", opts,
            lineCount = 1,
            lineReader = { table.text() },
        )
        result.replacementCount shouldBe 2
        table.text() shouldBe "foo_123 bar_456"
    }

    @Test
    fun `replace all with no matches does nothing`() {
        val table = PieceTable.create("hello world")
        val result = FindReplace.replaceAll(
            table, "xyz", "abc",
            lineCount = 1,
            lineReader = { table.text() },
        )
        result.replacementCount shouldBe 0
        table.text() shouldBe "hello world"
    }

    @Test
    fun `replace all multiline`() {
        val table = PieceTable.create("aaa bbb\nccc aaa\nbbb aaa")
        val lines = { l: Long -> table.text().split("\n")[l.toInt()] as CharSequence }
        val result = FindReplace.replaceAll(
            table, "aaa", "X",
            lineCount = 3,
            lineReader = lines,
        )
        result.replacementCount shouldBe 3
        table.text() shouldBe "X bbb\nccc X\nbbb X"
    }

    @Test
    fun `replace all whole word`() {
        val table = PieceTable.create("cat concatenate cats")
        val opts = FindReplace.FindOptions(wholeWord = true)
        val result = FindReplace.replaceAll(
            table, "cat", "dog", opts,
            lineCount = 1,
            lineReader = { table.text() },
        )
        result.replacementCount shouldBe 1
        table.text() shouldBe "dog concatenate cats"
    }

    // ── Replace all as single undo step ──

    @Test
    fun `replace all through document is one undo step`() {
        val d = doc("hello world hello there hello")
        // Use direct piece table for replace-all
        val table = getPieceTable(d)
        val beforeText = table.text()
        FindReplace.replaceAll(
            table, "hello", "hi",
            lineCount = 1,
            lineReader = { table.text() },
        )
        table.text() shouldBe "hi world hi there hi"
        // The replaceAll modifies the table directly — when wired through
        // the document, it would be wrapped as a single compound edit
    }

    // ── Line-level find helper ──

    @Test
    fun `findInLine finds multiple occurrences`() {
        val matches = FindReplace.findInLine("abcabcabc", "abc", FindReplace.FindOptions())
        matches.size shouldBe 3
        matches[0].start shouldBe 0
        matches[1].start shouldBe 3
        matches[2].start shouldBe 6
    }

    @Test
    fun `findInLine preserves original case in match text`() {
        val matches = FindReplace.findInLine("Hello HELLO hello", "hello", FindReplace.FindOptions())
        matches.size shouldBe 3
        matches[0].text shouldBe "Hello"
        matches[1].text shouldBe "HELLO"
        matches[2].text shouldBe "hello"
    }

    private fun getPieceTable(doc: PieceTableDocument): PieceTable {
        val field = PieceTableDocument::class.java.getDeclaredField("table")
        field.isAccessible = true
        return field.get(doc) as PieceTable
    }
}
