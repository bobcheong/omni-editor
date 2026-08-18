package com.omnieditor.core.io

import kotlinx.coroutines.runBlocking
import org.junit.After
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

class ChannelPieceTableContractTest : PieceTableContractTest() {

    private val tempFiles = mutableListOf<File>()
    private val openFiles = mutableListOf<RandomAccessFile>()

    override fun createTable(content: String): PieceTableLike {
        val file = File.createTempFile("contract-pt-", ".txt")
        tempFiles.add(file)
        file.writeText(content, Charsets.UTF_8)
        val raf = RandomAccessFile(file, "r")
        openFiles.add(raf)
        val channel = raf.channel
        val indexResult = runBlocking { FileIndexer.index(file) }
        val cpt = ChannelPieceTable(channel, indexResult.index, Charsets.UTF_8, indexResult.encoding.bomLength)
        return object : PieceTableLike {
            override val length get() = cpt.length
            override val lineCount get() = cpt.lineCount
            override fun line(lineIndex: Int) = cpt.line(lineIndex)
            override fun insert(offset: Int, text: String) = cpt.insert(offset, text)
            override fun delete(offset: Int, count: Int) = cpt.delete(offset, count)
            override fun replace(offset: Int, count: Int, text: String) = cpt.replace(offset, count, text)
            override fun substring(start: Int, end: Int) = cpt.substring(start, end)
            override fun charAt(offset: Int) = cpt.charAt(offset)
            override fun lineToOffset(lineIndex: Int) = cpt.lineToOffset(lineIndex)
            override fun offsetToLine(charOffset: Int) = cpt.offsetToLine(charOffset)
        }
    }

    @After
    fun tearDown() {
        openFiles.forEach { it.close() }
        tempFiles.forEach { it.delete() }
    }
}
