package com.mkread.app.core.files

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class DecodedText(
    val text: String,
    val charsetName: String,
)

class TxtEncodingDetector {
    fun decode(bytes: ByteArray): DecodedText {
        val bom = detectBom(bytes)
        if (bom != null) {
            val decoded = decodeStrict(bytes, bom.byteOffset, bom.charset)
                ?: throw BookParseException(
                    BookParseFailure.INVALID_ENCODING,
                    "TXT bytes do not match the declared byte-order mark",
                )
            return DecodedText(
                text = normalizeAndValidate(decoded),
                charsetName = bom.charset.name(),
            )
        }

        val utf8 = decodeStrict(bytes, 0, Charsets.UTF_8)
        if (utf8 != null) {
            return DecodedText(
                text = normalizeAndValidate(utf8),
                charsetName = Charsets.UTF_8.name(),
            )
        }

        val gb18030 = Charset.forName(GB18030)
        val legacyChinese = decodeStrict(bytes, 0, gb18030)
            ?: throw BookParseException(
                BookParseFailure.INVALID_ENCODING,
                "TXT is neither strict UTF-8 nor strict GB18030",
            )
        return DecodedText(
            text = normalizeAndValidate(legacyChinese),
            charsetName = gb18030.name(),
        )
    }

    private fun decodeStrict(
        bytes: ByteArray,
        offset: Int,
        charset: Charset,
    ): String? {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun normalizeAndValidate(decoded: String): String {
        val suspicious = decoded.count { character ->
            character == '\uFFFD' || (
                Character.isISOControl(character) &&
                    character != '\n' &&
                    character != '\r' &&
                    character != '\t'
                )
        }
        if (decoded.isNotEmpty() && suspicious.toLong() * 100L > decoded.length.toLong()) {
            throw BookParseException(
                BookParseFailure.INVALID_CONTENT,
                "TXT contains more than one percent replacement or control characters",
            )
        }

        val unixLines = decoded
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val normalizedLines = ArrayList<String>()
        var blankRun = 0
        unixLines.split('\n').forEach { sourceLine ->
            val line = sourceLine
                .replace("\u0000", "")
                .trimEnd(' ', '\t')
            if (line.isBlank()) {
                blankRun += 1
                if (blankRun <= MAX_CONSECUTIVE_BLANK_LINES) {
                    normalizedLines += ""
                }
            } else {
                blankRun = 0
                normalizedLines += line
            }
        }
        return normalizedLines.joinToString("\n")
    }

    private fun detectBom(bytes: ByteArray): Bom? = when {
        bytes.startsWith(UTF8_BOM) -> Bom(Charsets.UTF_8, UTF8_BOM.size)
        bytes.startsWith(UTF16_LE_BOM) -> Bom(Charsets.UTF_16LE, UTF16_LE_BOM.size)
        bytes.startsWith(UTF16_BE_BOM) -> Bom(Charsets.UTF_16BE, UTF16_BE_BOM.size)
        else -> null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

    private data class Bom(
        val charset: Charset,
        val byteOffset: Int,
    )

    private companion object {
        const val GB18030 = "GB18030"
        const val MAX_CONSECUTIVE_BLANK_LINES = 2
        val UTF8_BOM = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
        val UTF16_LE_BOM = byteArrayOf(0xff.toByte(), 0xfe.toByte())
        val UTF16_BE_BOM = byteArrayOf(0xfe.toByte(), 0xff.toByte())
    }
}
