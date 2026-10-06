package com.mkread.app.feature.reader

import java.util.Locale

class SentenceSegmenter(
    private val maxSentenceCodePoints: Int = 500,
) {
    init {
        require(maxSentenceCodePoints > 0) { "Sentence limit must be positive" }
    }

    fun segment(text: String): List<SentenceRange> {
        val ranges = mutableListOf<SentenceRange>()
        var cursor = 0
        while (cursor < text.length) {
            cursor = skipWhitespace(text, cursor)
            if (cursor >= text.length) break

            val start = cursor
            var scan = cursor
            var codePointCount = 0
            var lastHorizontalWhitespaceEnd = -1
            var emitted = false
            while (scan < text.length) {
                val codePoint = text.codePointAt(scan)
                if (isLineBreak(codePoint)) {
                    addRange(ranges, text, start, scan)
                    cursor = scan + Character.charCount(codePoint)
                    emitted = true
                    break
                }

                val next = scan + Character.charCount(codePoint)
                codePointCount += 1
                if (isHorizontalWhitespace(codePoint)) {
                    lastHorizontalWhitespaceEnd = next
                }

                val punctuationEnd = terminalPunctuationEnd(text, scan, codePoint)
                if (punctuationEnd != null) {
                    var end = consumeClosingPunctuation(text, punctuationEnd)
                    end = consumeHorizontalWhitespace(text, end)
                    addRange(ranges, text, start, end)
                    cursor = end
                    emitted = true
                    break
                }

                if (codePointCount >= maxSentenceCodePoints) {
                    val end = lastHorizontalWhitespaceEnd.takeIf { it > start } ?: next
                    addRange(ranges, text, start, end)
                    cursor = end
                    emitted = true
                    break
                }
                scan = next
            }

            if (!emitted) {
                addRange(ranges, text, start, text.length)
                cursor = text.length
            }
        }
        return ranges
    }

    private fun terminalPunctuationEnd(text: String, index: Int, codePoint: Int): Int? {
        if (codePoint == '.'.code) {
            if (isDecimalPoint(text, index) || isAbbreviation(text, index)) return null
            if (text.getOrNull(index + 1) == '.') {
                var end = index + 1
                while (end < text.length && text[end] == '.') end += 1
                return consumeTerminalRun(text, end)
            }
        }
        if (codePoint !in TERMINAL_PUNCTUATION) return null
        return consumeTerminalRun(text, index + Character.charCount(codePoint))
    }

    private fun consumeTerminalRun(text: String, start: Int): Int {
        var cursor = start
        while (cursor < text.length) {
            val codePoint = text.codePointAt(cursor)
            if (codePoint !in TERMINAL_PUNCTUATION) break
            cursor += Character.charCount(codePoint)
        }
        return cursor
    }

    private fun consumeClosingPunctuation(text: String, start: Int): Int {
        var cursor = start
        while (cursor < text.length) {
            val codePoint = text.codePointAt(cursor)
            if (codePoint !in CLOSING_PUNCTUATION) break
            cursor += Character.charCount(codePoint)
        }
        return cursor
    }

    private fun consumeHorizontalWhitespace(text: String, start: Int): Int {
        var cursor = start
        while (cursor < text.length) {
            val codePoint = text.codePointAt(cursor)
            if (!isHorizontalWhitespace(codePoint)) break
            cursor += Character.charCount(codePoint)
        }
        return cursor
    }

    private fun skipWhitespace(text: String, start: Int): Int {
        var cursor = start
        while (cursor < text.length) {
            val codePoint = text.codePointAt(cursor)
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) break
            cursor += Character.charCount(codePoint)
        }
        return cursor
    }

    private fun addRange(
        ranges: MutableList<SentenceRange>,
        text: String,
        start: Int,
        end: Int,
    ) {
        if (end <= start || text.substring(start, end).isBlank()) return
        ranges += SentenceRange(ranges.size, start, end)
    }

    private fun isDecimalPoint(text: String, index: Int): Boolean =
        index > 0 &&
            index + 1 < text.length &&
            text[index - 1].isDigit() &&
            text[index + 1].isDigit()

    private fun isAbbreviation(text: String, index: Int): Boolean {
        if (
            index > 0 &&
            index + 1 < text.length &&
            text[index - 1].isLetter() &&
            text[index + 1].isLetter()
        ) {
            return true
        }
        var start = index
        while (start > 0) {
            val previous = text[start - 1]
            if (!previous.isLetter() && previous != '.') break
            start -= 1
        }
        return text.substring(start, index + 1).lowercase(Locale.ROOT) in ABBREVIATIONS
    }

    private fun isLineBreak(codePoint: Int): Boolean =
        codePoint == '\n'.code || codePoint == '\r'.code

    private fun isHorizontalWhitespace(codePoint: Int): Boolean =
        !isLineBreak(codePoint) &&
            (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint))

    private companion object {
        val TERMINAL_PUNCTUATION = setOf(
            '.'.code,
            '?'.code,
            '!'.code,
            '。'.code,
            '？'.code,
            '！'.code,
            '…'.code,
        )
        val CLOSING_PUNCTUATION = setOf(
            '"'.code,
            '\''.code,
            ')'.code,
            ']'.code,
            '}'.code,
            '”'.code,
            '’'.code,
            '」'.code,
            '』'.code,
            '）'.code,
            '】'.code,
            '〕'.code,
            '〉'.code,
            '》'.code,
        )
        val ABBREVIATIONS = setOf(
            "mr.", "mrs.", "ms.", "dr.", "prof.", "sr.", "jr.", "st.",
            "vs.", "etc.", "e.g.", "i.e.", "u.s.", "u.k.",
        )
    }
}
