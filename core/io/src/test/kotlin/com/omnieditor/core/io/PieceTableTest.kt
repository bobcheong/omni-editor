package com.omnieditor.core.io

import io.kotest.matchers.shouldBe
import org.junit.Test

class PieceTableTest {

    @Test
    fun `empty table has zero length`() {
        val pt = PieceTable.create()
        pt.length shouldBe 0
        pt.lineCount shouldBe 1
    }

    @Test
    fun `create from content`() {
        val pt = PieceTable.create("hello world")
        pt.text() shouldBe "hello world"
        pt.length shouldBe 11
    }

    @Test
    fun `insert at beginning`() {
        val pt = PieceTable.create("world")
        pt.insert(0, "hello ")
        pt.text() shouldBe "hello world"
    }

    @Test
    fun `insert at end`() {
        val pt = PieceTable.create("hello")
        pt.insert(5, " world")
        pt.text() shouldBe "hello world"
    }

    @Test
    fun `insert in middle`() {
        val pt = PieceTable.create("hllo")
        pt.insert(1, "e")
        pt.text() shouldBe "hello"
    }

    @Test
    fun `delete from beginning`() {
        val pt = PieceTable.create("hello world")
        pt.delete(0, 6)
        pt.text() shouldBe "world"
    }

    @Test
    fun `delete from end`() {
        val pt = PieceTable.create("hello world")
        pt.delete(5, 6)
        pt.text() shouldBe "hello"
    }

    @Test
    fun `delete from middle`() {
        val pt = PieceTable.create("hello world")
        pt.delete(4, 4)
        pt.text() shouldBe "hellrld"
    }

    @Test
    fun `replace in middle`() {
        val pt = PieceTable.create("hello world")
        pt.replace(6, 5, "there")
        pt.text() shouldBe "hello there"
    }

    @Test
    fun `multiple inserts`() {
        val pt = PieceTable.create("ac")
        pt.insert(1, "b")
        pt.text() shouldBe "abc"
        pt.insert(3, "d")
        pt.text() shouldBe "abcd"
        pt.insert(0, "z")
        pt.text() shouldBe "zabcd"
    }

    @Test
    fun `line count with newlines`() {
        val pt = PieceTable.create("a\nb\nc")
        pt.lineCount shouldBe 3
    }

    @Test
    fun `line access`() {
        val pt = PieceTable.create("alpha\nbeta\ngamma")
        pt.line(0) shouldBe "alpha"
        pt.line(1) shouldBe "beta"
        pt.line(2) shouldBe "gamma"
    }

    @Test
    fun `line access after insert`() {
        val pt = PieceTable.create("alpha\ngamma")
        pt.insert(6, "beta\n")
        pt.text() shouldBe "alpha\nbeta\ngamma"
        pt.line(0) shouldBe "alpha"
        pt.line(1) shouldBe "beta"
        pt.line(2) shouldBe "gamma"
    }

    @Test
    fun `substring extraction`() {
        val pt = PieceTable.create("hello world")
        pt.substring(0, 5) shouldBe "hello"
        pt.substring(6, 11) shouldBe "world"
        pt.substring(3, 8) shouldBe "lo wo"
    }

    @Test
    fun `substring across pieces`() {
        val pt = PieceTable.create("ac")
        pt.insert(1, "b")
        pt.substring(0, 3) shouldBe "abc"
        pt.substring(0, 1) shouldBe "a"
        pt.substring(1, 2) shouldBe "b"
        pt.substring(2, 3) shouldBe "c"
    }

    @Test
    fun `delete returns correct deleted text`() {
        val pt = PieceTable.create("hello world")
        val record = pt.delete(5, 6)
        record.deleted shouldBe " world"
    }

    @Test
    fun `insert into empty table`() {
        val pt = PieceTable.create()
        pt.insert(0, "hello")
        pt.text() shouldBe "hello"
    }
}
