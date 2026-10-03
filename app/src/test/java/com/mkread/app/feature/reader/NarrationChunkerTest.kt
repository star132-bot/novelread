package com.mkread.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationChunkerTest {
    private val chunker = NarrationChunker(maxChunkLength = 20, minChunkLength = 4)

    @Test
    fun shortSentencesStayWhole() {
        val text = "夜色渐深。"
        val sentence = SentenceRange(0, 0, text.length)

        assertEquals(listOf(sentence), chunker.split(text, sentence))
    }

    @Test
    fun longSentencesSplitAfterClauseMarksAndCoverTheWholeRange() {
        val text = "屋里只点着一盏油灯，老人抬起头，缓缓说道，你终于来了，我等了你整整三十五年。"
        val sentence = SentenceRange(3, 0, text.length)

        val chunks = chunker.split(text, sentence)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.index == 3 && it.endExclusive - it.startInclusive <= 24 })
        assertEquals(sentence.startInclusive, chunks.first().startInclusive)
        assertEquals(sentence.endExclusive, chunks.last().endExclusive)
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.endExclusive, b.startInclusive) }
        chunks.dropLast(1).forEach { assertEquals('，', text[it.endExclusive - 1]) }
    }

    @Test
    fun textWithoutBreaksIsCutAtTheLimit() {
        val text = "一".repeat(45)
        val chunks = chunker.split(text, SentenceRange(0, 0, text.length))

        assertEquals(listOf(0 to 20, 20 to 40, 40 to 45), chunks.map { it.startInclusive to it.endExclusive })
    }
}
