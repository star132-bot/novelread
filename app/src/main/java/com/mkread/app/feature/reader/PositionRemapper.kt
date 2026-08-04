package com.mkread.app.feature.reader

object PositionRemapper {
    fun remap(oldText: String, newText: String, oldOffset: Int): Int {
        if (newText.isEmpty()) return 0
        val clampedOffset = oldOffset.coerceIn(0, oldText.length)
        val prefix = commonPrefixLength(oldText, newText)
        if (clampedOffset <= prefix) return safeBoundary(newText, clampedOffset)

        val suffix = commonSuffixLength(oldText, newText, prefix)
        val oldSuffixStart = oldText.length - suffix
        val newSuffixStart = newText.length - suffix
        if (clampedOffset >= oldSuffixStart) {
            return safeBoundary(newText, newSuffixStart + (clampedOffset - oldSuffixStart))
        }
        return safeBoundary(newText, prefix)
    }

    private fun commonPrefixLength(left: String, right: String): Int {
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < left.length && rightIndex < right.length) {
            val leftCodePoint = left.codePointAt(leftIndex)
            val rightCodePoint = right.codePointAt(rightIndex)
            if (leftCodePoint != rightCodePoint) break
            leftIndex += Character.charCount(leftCodePoint)
            rightIndex += Character.charCount(rightCodePoint)
        }
        return rightIndex
    }

    private fun commonSuffixLength(left: String, right: String, prefixLength: Int): Int {
        var leftIndex = left.length
        var rightIndex = right.length
        var length = 0
        while (leftIndex > prefixLength && rightIndex > prefixLength) {
            val leftCodePoint = left.codePointBefore(leftIndex)
            val rightCodePoint = right.codePointBefore(rightIndex)
            if (leftCodePoint != rightCodePoint) break
            val charCount = Character.charCount(rightCodePoint)
            leftIndex -= Character.charCount(leftCodePoint)
            rightIndex -= charCount
            length += charCount
        }
        return length
    }

    private fun safeBoundary(text: String, offset: Int): Int {
        var result = offset.coerceIn(0, text.length)
        if (
            result in 1 until text.length &&
            Character.isHighSurrogate(text[result - 1]) &&
            Character.isLowSurrogate(text[result])
        ) {
            result -= 1
        }
        return result
    }
}
