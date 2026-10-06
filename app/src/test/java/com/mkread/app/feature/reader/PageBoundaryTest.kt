package com.mkread.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PageBoundaryTest {
    @Test
    fun safeEndKeepsCrLfTogether() {
        assertEquals(3, PageBoundary.safeEnd("a\r\nb", pageStart = 0, proposedEnd = 2))
    }

    @Test
    fun safeEndKeepsSurrogatePairTogether() {
        assertEquals(2, PageBoundary.safeEnd("😀x", pageStart = 0, proposedEnd = 1))
    }

    @Test
    fun safeEndKeepsCombiningMarkWithBaseCharacter() {
        assertEquals(2, PageBoundary.safeEnd("e\u0301x", pageStart = 0, proposedEnd = 1))
    }

    @Test
    fun safeEndMakesProgressWhenLayoutReportsZero() {
        assertEquals(2, PageBoundary.safeEnd("abc", pageStart = 1, proposedEnd = 1))
    }

    @Test
    fun safeEndReturnsTextLengthWhenAlreadyExhausted() {
        assertEquals(3, PageBoundary.safeEnd("abc", pageStart = 3, proposedEnd = 3))
    }
}
