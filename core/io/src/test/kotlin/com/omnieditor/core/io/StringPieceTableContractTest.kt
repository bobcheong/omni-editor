package com.omnieditor.core.io

class StringPieceTableContractTest : PieceTableContractTest() {
    override fun createTable(content: String): PieceTableLike {
        val pt = PieceTable.create(content)
        return object : PieceTableLike {
            override val length get() = pt.length
            override val lineCount get() = pt.lineCount
            override fun line(lineIndex: Int) = pt.line(lineIndex)
            override fun insert(offset: Int, text: String) = pt.insert(offset, text)
            override fun delete(offset: Int, count: Int) = pt.delete(offset, count)
            override fun replace(offset: Int, count: Int, text: String) = pt.replace(offset, count, text)
            override fun substring(start: Int, end: Int) = pt.substring(start, end)
            override fun charAt(offset: Int) = pt.charAt(offset)
            override fun lineToOffset(lineIndex: Int) = pt.lineToOffset(lineIndex)
            override fun offsetToLine(charOffset: Int) = pt.offsetToLine(charOffset)
        }
    }
}
