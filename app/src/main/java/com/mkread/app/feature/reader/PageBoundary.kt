package com.mkread.app.feature.reader

object PageBoundary {
    fun safeEnd(text: String, proposedEnd: Int): Int =
        safeEnd(text, pageStart = 0, proposedEnd = proposedEnd)

    fun safeEnd(text: String, pageStart: Int, proposedEnd: Int): Int {
        require(pageStart in 0..text.length) { "Page start is outside the text" }
        if (pageStart == text.length) return text.length

        var end = proposedEnd.coerceIn(pageStart, text.length)
        if (end <= pageStart) {
            end = pageStart + Character.charCount(text.codePointAt(pageStart))
        }
        if (
            end in 1 until text.length &&
            Character.isHighSurrogate(text[end - 1]) &&
            Character.isLowSurrogate(text[end])
        ) {
            end += 1
        }
        if (end in 1 until text.length && text[end - 1] == '\r' && text[end] == '\n') {
            end += 1
        }
        while (end < text.length && isCombiningMark(text.codePointAt(end))) {
            end += Character.charCount(text.codePointAt(end))
        }
        return end.coerceAtMost(text.length)
    }

    private fun isCombiningMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true
        else -> false
    }
}
