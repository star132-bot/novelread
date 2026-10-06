package com.mkread.app.feature.reader

/**
 * Splits long sentences into clause-sized chunks for narration. Synthesis time grows with
 * input length, so short chunks start playing sooner and keep the queue ahead of playback.
 * Chunks keep the parent sentence's index so the reader still highlights the whole sentence.
 */
class NarrationChunker(
    private val maxChunkLength: Int = DEFAULT_MAX_CHUNK_LENGTH,
    private val minChunkLength: Int = DEFAULT_MIN_CHUNK_LENGTH,
) {
    init {
        require(minChunkLength in 1 until maxChunkLength) { "Chunk limits are invalid" }
    }

    fun split(text: String, sentence: SentenceRange): List<SentenceRange> {
        val length = sentence.endExclusive - sentence.startInclusive
        if (length <= maxChunkLength) return listOf(sentence)

        val chunks = mutableListOf<SentenceRange>()
        var start = sentence.startInclusive
        while (sentence.endExclusive - start > maxChunkLength) {
            val end = breakPoint(text, start, sentence.endExclusive)
            chunks += SentenceRange(sentence.index, start, end)
            start = end
            while (start < sentence.endExclusive && text[start].isWhitespace()) start += 1
        }
        if (start < sentence.endExclusive) {
            val tail = SentenceRange(sentence.index, start, sentence.endExclusive)
            val last = chunks.lastOrNull()
            if (last != null && tail.endExclusive - tail.startInclusive < minChunkLength &&
                tail.endExclusive - last.startInclusive <= maxChunkLength + minChunkLength
            ) {
                chunks[chunks.lastIndex] = SentenceRange(sentence.index, last.startInclusive, tail.endExclusive)
            } else {
                chunks += tail
            }
        }
        return chunks
    }

    /** Prefers the last clause mark within the window, then whitespace, then a hard cut. */
    private fun breakPoint(text: String, start: Int, end: Int): Int {
        val limit = minOf(end, start + maxChunkLength)
        val earliest = start + minChunkLength
        for (index in limit - 1 downTo earliest) {
            if (text[index] in CLAUSE_MARKS) return index + 1
        }
        for (index in limit - 1 downTo earliest) {
            if (text[index].isWhitespace()) return index + 1
        }
        var cut = limit
        if (cut < end && Character.isLowSurrogate(text[cut])) cut -= 1
        return cut
    }

    companion object {
        const val DEFAULT_MAX_CHUNK_LENGTH = 48
        const val DEFAULT_MIN_CHUNK_LENGTH = 8
        private val CLAUSE_MARKS = setOf('，', '、', '；', '：', ',', ';', ':', '”', '’', '）', ')')
    }
}
