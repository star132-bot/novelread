package com.mkread.app.core.files

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class TxtEncodingDetectorTest {
    private val detector = TxtEncodingDetector()

    @Test
    fun utf8Bom_isDetectedAndRemoved() {
        val source = fixture("utf8-bom.txt")
        val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) +
            source.toByteArray(Charsets.UTF_8)

        val decoded = detector.decode(bytes)

        assertEquals("UTF-8", decoded.charsetName)
        assertEquals(source, decoded.text)
    }

    @Test
    fun utf16LittleAndBigEndianBom_areDecodedStrictly() {
        val source = fixture("utf16le.txt")
        val littleEndian = byteArrayOf(0xff.toByte(), 0xfe.toByte()) +
            source.toByteArray(Charsets.UTF_16LE)
        val bigEndian = byteArrayOf(0xfe.toByte(), 0xff.toByte()) +
            source.toByteArray(Charsets.UTF_16BE)

        val decodedLittle = detector.decode(littleEndian)
        val decodedBig = detector.decode(bigEndian)

        assertEquals("UTF-16LE", decodedLittle.charsetName)
        assertEquals(source, decodedLittle.text)
        assertEquals("UTF-16BE", decodedBig.charsetName)
        assertEquals(source, decodedBig.text)
    }

    @Test
    fun validUtf8WithoutBom_isPreferred() {
        val source = "A valid UTF-8 line.\n第二行。"

        val decoded = detector.decode(source.toByteArray(Charsets.UTF_8))

        assertEquals("UTF-8", decoded.charsetName)
        assertEquals(source, decoded.text)
    }

    @Test
    fun invalidUtf8_fallsBackToStrictGb18030() {
        val source = fixture("gb18030.txt")
        val gb18030 = Charset.forName("GB18030")
        val bytes = source.toByteArray(gb18030)

        val decoded = detector.decode(bytes)

        assertEquals("GB18030", decoded.charsetName)
        assertEquals(source, decoded.text)
    }

    @Test
    fun truncatedMultibyteInput_isRejected() {
        assertParseFailure(BookParseFailure.INVALID_ENCODING) {
            detector.decode(byteArrayOf(0x81.toByte()))
        }
    }

    @Test
    fun lineEndingsTrailingSpacesAndBlankRuns_areNormalized() {
        val source = "one  \r\n\r\n\r\n\r\ntwo\t \rthree"

        val decoded = detector.decode(source.toByteArray(Charsets.UTF_8))

        assertEquals("one\n\n\ntwo\nthree", decoded.text)
    }

    @Test
    fun lowNulRate_isRemoved_butHighControlRateIsRejected() {
        val mostlyText = "a".repeat(200) + "\u0000b"

        val decoded = detector.decode(mostlyText.toByteArray(Charsets.UTF_8))

        assertFalse(decoded.text.contains('\u0000'))
        assertParseFailure(BookParseFailure.INVALID_CONTENT) {
            detector.decode("abc\u0000def".toByteArray(Charsets.UTF_8))
        }
    }

    private fun fixture(name: String): String = requireNotNull(
        javaClass.getResourceAsStream("/txt/$name"),
    ).bufferedReader(Charsets.UTF_8).use { reader ->
        reader.readText().trimEnd('\r', '\n')
    }

    private fun assertParseFailure(
        expected: BookParseFailure,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("Expected parse failure $expected")
        } catch (failure: BookParseException) {
            assertEquals(expected, failure.failure)
        }
    }
}
