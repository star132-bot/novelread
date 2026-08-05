package com.mkread.app.speech

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer

data class NormalizedText(
    val value: String,
    val sha256: String,
    val normalizerVersion: Int = TextNormalizer.NORMALIZER_VERSION,
)

class TextNormalizer(
    private val pronunciationOverrides: PronunciationOverrides,
) {
    fun normalize(input: String): NormalizedText {
        val normalized = input.normalizeNfkcPreservingNarrationPunctuation()
            .collapseWhitespace()
            .normalizeEllipsis()
            .let(pronunciationOverrides::applyTo)
            .collapseWhitespace()

        require(normalized.isNotEmpty()) { "Narration text is blank after normalization" }
        return NormalizedText(
            value = normalized,
            sha256 = normalized.sha256(),
        )
    }

    companion object {
        const val NORMALIZER_VERSION = 1

        fun fromAsset(openAsset: (String) -> InputStream): TextNormalizer {
            val overrides = openAsset(PronunciationOverrides.ASSET_PATH)
                .buffered()
                .use(PronunciationOverrides::load)
            return TextNormalizer(overrides)
        }
    }
}

internal fun String.normalizeNfkcPreservingNarrationPunctuation(): String {
    val result = StringBuilder(length)
    var segmentStart = 0
    forEachIndexed { index, character ->
        if (character in PRESERVED_NARRATION_PUNCTUATION) {
            if (segmentStart < index) {
                result.append(Normalizer.normalize(substring(segmentStart, index), Normalizer.Form.NFKC))
            }
            result.append(character)
            segmentStart = index + 1
        }
    }
    if (segmentStart < length) {
        result.append(Normalizer.normalize(substring(segmentStart), Normalizer.Form.NFKC))
    }
    return result.toString()
}

internal fun String.collapseWhitespace(): String {
    val result = StringBuilder(length)
    var pendingSpace = false
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
            pendingSpace = result.isNotEmpty()
        } else {
            if (pendingSpace) result.append(' ')
            result.appendCodePoint(codePoint)
            pendingSpace = false
        }
        index += Character.charCount(codePoint)
    }
    return result.toString()
}

private fun String.normalizeEllipsis(): String {
    val result = StringBuilder(length)
    var index = 0
    while (index < length) {
        when (this[index]) {
            '.' -> {
                val runEnd = runEnd(index, '.')
                if (runEnd - index >= 2) result.append(CHINESE_ELLIPSIS) else result.append('.')
                index = runEnd
            }

            '\u2026' -> {
                val runEnd = runEnd(index, '\u2026')
                if (runEnd - index >= 2) result.append(CHINESE_ELLIPSIS) else result.append('\u2026')
                index = runEnd
            }

            else -> {
                val codePoint = codePointAt(index)
                result.appendCodePoint(codePoint)
                index += Character.charCount(codePoint)
            }
        }
    }
    return result.toString()
}

private fun String.runEnd(start: Int, character: Char): Int {
    var index = start + 1
    while (index < length && this[index] == character) index += 1
    return index
}

private fun String.sha256(): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
    val result = CharArray(bytes.size * 2)
    bytes.forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        result[index * 2] = HEX_DIGITS[value ushr 4]
        result[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
    }
    return String(result)
}

private const val CHINESE_ELLIPSIS = "……"
private const val HEX_DIGITS = "0123456789abcdef"
private const val PRESERVED_NARRATION_PUNCTUATION = "，。！？；：、"
