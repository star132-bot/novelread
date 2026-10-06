package com.mkread.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSegmenterTest {
    private val segmenter = SentenceSegmenter()

    @Test
    fun chinesePunctuationKeepsClosingQuotesAndHorizontalWhitespace() {
        val text = "她问：“你好吗？”  他点头。\n\n第二段！"

        assertEquals(
            listOf("她问：“你好吗？”  ", "他点头。", "第二段！"),
            segments(text),
        )
    }

    @Test
    fun englishPunctuationDoesNotSplitDecimalOrAbbreviation() {
        val text = "Mr. Li paid 3.14 dollars. Really? Yes!"

        assertEquals(
            listOf("Mr. Li paid 3.14 dollars. ", "Really? ", "Yes!"),
            segments(text),
        )
    }

    @Test
    fun ellipsisAndPairedCloserEndTheSentenceTogether() {
        val text = "等等……」下一句！"

        assertEquals(listOf("等等……」", "下一句！"), segments(text))
    }

    @Test
    fun paragraphBreakCreatesAnInterstitialGap() {
        val text = "No punctuation here\r\n\r\nNext paragraph"
        val ranges = segmenter.segment(text)

        assertEquals(
            listOf("No punctuation here", "Next paragraph"),
            ranges.map { text.sliceRange(it) },
        )
        assertNull(ranges.sentenceAt(text.indexOf('\r')))
        assertEquals(ranges[1], ranges.nearestBoundary(text.indexOf('\r')))
        assertEquals(ranges[0], ranges.sentenceAt(2))
    }

    @Test
    fun whitespaceOnlyInputHasNoSentences() {
        assertTrue(segmenter.segment(" \t\n\r\n").isEmpty())
    }

    @Test
    fun longParagraphBreaksAtNearestWhitespaceBeforeFiveHundredCodePoints() {
        val text = "word ".repeat(4_000).trimEnd()
        val ranges = segmenter.segment(text)

        assertTrue(ranges.size > 20)
        assertEquals(0, ranges.first().startInclusive)
        assertEquals(text.length, ranges.last().endExclusive)
        assertTrue(ranges.zipWithNext().all { (left, right) -> left.endExclusive == right.startInclusive })
        assertTrue(ranges.all { text.codePointCount(it.startInclusive, it.endExclusive) <= 500 })
    }

    @Test
    fun forcedBoundaryNeverSplitsSurrogatePair() {
        val text = "😀".repeat(600)
        val ranges = segmenter.segment(text)

        assertEquals(2, ranges.size)
        assertTrue(ranges.all { text.codePointCount(it.startInclusive, it.endExclusive) <= 500 })
        ranges.dropLast(1).forEach { range ->
            assertFalse(Character.isHighSurrogate(text[range.endExclusive - 1]))
            assertFalse(Character.isLowSurrogate(text[range.endExclusive]))
        }
    }

    private fun segments(text: String): List<String> =
        segmenter.segment(text).map { text.sliceRange(it) }

    private fun String.sliceRange(range: SentenceRange): String =
        substring(range.startInclusive, range.endExclusive)
}
