package com.omnieditor.core.io

import com.omnieditor.core.model.LineEnding
import io.kotest.matchers.shouldBe
import org.junit.Test

class EncodingDetectorTest {

    // ── BOM detection ──

    @Test
    fun `detects UTF-8 BOM`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'h'.code.toByte())
        val result = EncodingDetector.detect(bytes)
        result.charset shouldBe "UTF-8"
        result.bomLength shouldBe 3
        result.confidence shouldBe EncodingDetector.Confidence.BOM
    }

    @Test
    fun `detects UTF-16LE BOM`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 'h'.code.toByte(), 0)
        val result = EncodingDetector.detect(bytes)
        result.charset shouldBe "UTF-16LE"
        result.bomLength shouldBe 2
        result.confidence shouldBe EncodingDetector.Confidence.BOM
    }

    @Test
    fun `detects UTF-16BE BOM`() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0, 'h'.code.toByte())
        val result = EncodingDetector.detect(bytes)
        result.charset shouldBe "UTF-16BE"
        result.bomLength shouldBe 2
        result.confidence shouldBe EncodingDetector.Confidence.BOM
    }

    @Test
    fun `detects UTF-32LE BOM`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0, 'h'.code.toByte(), 0, 0, 0)
        val result = EncodingDetector.detect(bytes)
        result.charset shouldBe "UTF-32LE"
        result.bomLength shouldBe 4
        result.confidence shouldBe EncodingDetector.Confidence.BOM
    }

    @Test
    fun `detects UTF-32BE BOM`() {
        val bytes = byteArrayOf(0, 0, 0xFE.toByte(), 0xFF.toByte(), 0, 0, 0, 'h'.code.toByte())
        val result = EncodingDetector.detect(bytes)
        result.charset shouldBe "UTF-32BE"
        result.bomLength shouldBe 4
        result.confidence shouldBe EncodingDetector.Confidence.BOM
    }

    // ── UTF-8 validation ──

    @Test
    fun `validates pure ASCII as UTF-8`() {
        val bytes = "hello world\nline two".toByteArray(Charsets.UTF_8)
        val result = EncodingDetector.detect(bytes)
        result.charset shouldBe "UTF-8"
        result.bomLength shouldBe 0
        result.confidence shouldBe EncodingDetector.Confidence.VALIDATED
    }

    @Test
    fun `validates multi-byte UTF-8`() {
        val bytes = "café résumé naïve".toByteArray(Charsets.UTF_8)
        val result = EncodingDetector.detect(bytes)
        result.charset shouldBe "UTF-8"
        result.confidence shouldBe EncodingDetector.Confidence.VALIDATED
    }

    @Test
    fun `validates CJK UTF-8`() {
        val bytes = "日本語テスト".toByteArray(Charsets.UTF_8)
        val result = EncodingDetector.detect(bytes)
        result.charset shouldBe "UTF-8"
        result.confidence shouldBe EncodingDetector.Confidence.VALIDATED
    }

    @Test
    fun `validates emoji UTF-8`() {
        val bytes = "hello 🌍🚀".toByteArray(Charsets.UTF_8)
        val result = EncodingDetector.detect(bytes)
        result.charset shouldBe "UTF-8"
        result.confidence shouldBe EncodingDetector.Confidence.VALIDATED
    }

    // ── Heuristic fallback ──

    @Test
    fun `detects Windows-1252 by high byte heuristic`() {
        // Windows-1252 specific: smart quotes (0x93, 0x94), em dash (0x97)
        val bytes = byteArrayOf(
            'H'.code.toByte(), 'e'.code.toByte(), 0x93.toByte(), // left smart quote
            'h'.code.toByte(), 'i'.code.toByte(), 0x94.toByte(), // right smart quote
            0x97.toByte(), // em dash
            'b'.code.toByte(), 'y'.code.toByte(), 'e'.code.toByte(),
        )
        val result = EncodingDetector.detect(bytes)
        result.charset shouldBe "windows-1252"
        result.confidence shouldBe EncodingDetector.Confidence.HEURISTIC
    }

    @Test
    fun `empty input returns UTF-8`() {
        val result = EncodingDetector.detect(ByteArray(0))
        result.charset shouldBe "UTF-8"
        result.bomLength shouldBe 0
    }

    // ── UTF-8 validation edge cases ──

    @Test
    fun `rejects overlong 2-byte sequence`() {
        // C0 80 is an overlong encoding of U+0000
        val bytes = byteArrayOf(0xC0.toByte(), 0x80.toByte())
        EncodingDetector.isValidUtf8(bytes, bytes.size) shouldBe false
    }

    @Test
    fun `rejects surrogate range`() {
        // ED A0 80 encodes U+D800 (surrogate)
        val bytes = byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())
        EncodingDetector.isValidUtf8(bytes, bytes.size) shouldBe false
    }

    // ── Line ending detection ──

    @Test
    fun `detects LF line endings`() {
        val bytes = "line one\nline two\nline three\n".toByteArray()
        LineEndingDetector.detect(bytes) shouldBe LineEnding.LF
    }

    @Test
    fun `detects CRLF line endings`() {
        val bytes = "line one\r\nline two\r\nline three\r\n".toByteArray()
        LineEndingDetector.detect(bytes) shouldBe LineEnding.CRLF
    }

    @Test
    fun `detects CR line endings`() {
        val bytes = "line one\rline two\rline three\r".toByteArray()
        LineEndingDetector.detect(bytes) shouldBe LineEnding.CR
    }

    @Test
    fun `mixed line endings picks majority`() {
        val bytes = "a\r\nb\r\nc\r\nd\ne\n".toByteArray()
        LineEndingDetector.detect(bytes) shouldBe LineEnding.CRLF
    }

    @Test
    fun `no line endings defaults to LF`() {
        val bytes = "no newlines here".toByteArray()
        LineEndingDetector.detect(bytes) shouldBe LineEnding.LF
    }
}
