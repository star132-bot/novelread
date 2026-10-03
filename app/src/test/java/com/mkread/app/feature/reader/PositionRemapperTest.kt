package com.mkread.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PositionRemapperTest {
    @Test
    fun unchangedPrefixKeepsOffset() {
        assertEquals(2, PositionRemapper.remap("abcdef", "abcdef", 2))
    }

    @Test
    fun insertionBeforePositionShiftsIntoUnchangedSuffix() {
        assertEquals(5, PositionRemapper.remap("abcdef", "XXabcdef", 3))
    }

    @Test
    fun deletionAcrossPositionMapsToSurvivingPrefixEnd() {
        assertEquals(2, PositionRemapper.remap("abcdef", "abef", 3))
    }

    @Test
    fun replacementAroundPositionMapsMiddleToPrefixEnd() {
        assertEquals(2, PositionRemapper.remap("abcdef", "abXYZf", 3))
    }

    @Test
    fun unchangedSuffixKeepsRelativeOffset() {
        assertEquals(3, PositionRemapper.remap("abcTAIL", "xTAIL", 5))
    }

    @Test
    fun emptyNewTextMapsEveryPositionToZero() {
        assertEquals(0, PositionRemapper.remap("content", "", 4))
    }

    @Test
    fun emojiPrefixAndSuffixNeverSplitSurrogatePair() {
        val oldText = "😀 middle end"
        val newText = "😀 changed end"

        val mapped = PositionRemapper.remap(oldText, newText, oldText.indexOf("end"))

        assertEquals(newText.indexOf("end"), mapped)
        assertFalse(
            mapped in 1 until newText.length &&
                Character.isHighSurrogate(newText[mapped - 1]) &&
                Character.isLowSurrogate(newText[mapped]),
        )
    }
}
