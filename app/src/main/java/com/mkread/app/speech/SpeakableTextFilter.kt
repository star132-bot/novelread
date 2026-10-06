package com.mkread.app.speech

/**
 * Removes characters that narration engines cannot pronounce. Without this step engines
 * either read the symbol's name aloud (for example "tilde") or drop the whole chunk.
 *
 * Quotes and brackets disappear while their contents stay; dashes, tildes and similar
 * separators become a short pause; decorative symbols and emoji are dropped.
 */
object SpeakableTextFilter {
    fun filter(input: String): String {
        val result = StringBuilder(input.length)
        var index = 0
        while (index < input.length) {
            val codePoint = input.codePointAt(index)
            index += Character.charCount(codePoint)
            when {
                codePoint == '…'.code -> result.append('。')
                isSpeakable(codePoint) -> result.appendCodePoint(codePoint)
                codePoint == '-'.code && isLatinLetterAt(input, index - 2) && isLatinLetterAt(input, index) ->
                    result.append(' ')
                codePoint in PAUSES -> result.append(PAUSE_FOR[codePoint] ?: '，')
                Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) -> result.append(' ')
                codePoint == '&'.code -> result.append('和')
                codePoint == '+'.code -> result.append('加')
                codePoint == '='.code -> result.append("等于")
                codePoint == '@'.code -> result.append(" at ")
                else -> result.append(' ')
            }
        }
        return result.toString().tidyPunctuation()
    }

    private fun isLatinLetterAt(text: String, index: Int): Boolean =
        index in text.indices && (text[index] in 'a'..'z' || text[index] in 'A'..'Z')

    /** True when [text] has something an engine can actually say. */
    fun hasSpeech(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (Character.isLetterOrDigit(codePoint)) return true
            index += Character.charCount(codePoint)
        }
        return false
    }

    private fun isSpeakable(codePoint: Int): Boolean = when {
        codePoint in 'a'.code..'z'.code || codePoint in 'A'.code..'Z'.code -> true
        codePoint in '0'.code..'9'.code -> true
        codePoint == '\''.code -> true
        isHan(codePoint) -> true
        codePoint in KEPT_PUNCTUATION -> true
        Character.isLetter(codePoint) && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN -> true
        else -> false
    }

    private fun isHan(codePoint: Int): Boolean =
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN && Character.isLetter(codePoint) ||
            codePoint == '〇'.code

    /** Collapses runs of pauses, keeps the strongest stop and trims leading pauses. */
    private fun String.tidyPunctuation(): String {
        val result = StringBuilder(length)
        var pendingSpace = false
        var index = 0
        while (index < length) {
            val character = this[index]
            index += 1
            if (character == ' ') {
                pendingSpace = result.isNotEmpty()
                continue
            }
            if (character in SENTENCE_PUNCTUATION) {
                if (result.isEmpty()) continue
                val last = result.last()
                if (last in SENTENCE_PUNCTUATION) {
                    if (strength(character) > strength(last)) result.setCharAt(result.lastIndex, character)
                    pendingSpace = false
                    continue
                }
                result.append(character)
                pendingSpace = false
                continue
            }
            if (pendingSpace && result.isNotEmpty() && needsSpace(result.last(), character)) result.append(' ')
            pendingSpace = false
            result.append(character)
        }
        return result.toString().trim().trimEnd('，', '、', ',', ' ')
    }

    private fun needsSpace(previous: Char, next: Char): Boolean =
        if (previous in SENTENCE_PUNCTUATION) {
            previous.code < 0x80 && next.isAsciiLetterOrDigit()
        } else {
            previous.isAsciiLetterOrDigit() || next.isAsciiLetterOrDigit()
        }

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun strength(character: Char): Int = when (character) {
        '。', '！', '？', '.', '!', '?' -> 3
        '；', ';', '：', ':' -> 2
        else -> 1
    }

    private val SENTENCE_PUNCTUATION = setOf(
        '，', '。', '！', '？', '；', '：', '、', ',', '.', '!', '?', ';', ':',
    )
    private val KEPT_PUNCTUATION = SENTENCE_PUNCTUATION.map(Char::code).toSet()
    private val PAUSE_FOR = mapOf(
        '—'.code to '，',
        '―'.code to '，',
        '─'.code to '，',
        '~'.code to '，',
        '～'.code to '，',
        '|'.code to '，',
        '/'.code to '，',
        '\\'.code to '，',
        '·'.code to '，',
        '・'.code to '，',
        '→'.code to '，',
        '←'.code to '，',
        '-'.code to '，',
        '–'.code to '，',
        '_'.code to '，',
        '*'.code to '，',
        '#'.code to '，',
        '※'.code to '。',
    )
    private val PAUSES = PAUSE_FOR.keys
}
