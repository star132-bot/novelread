package com.mkread.app.feature.reader

interface PaginationCache {
    suspend fun load(key: PaginationKey, text: String): List<PageRange>?

    suspend fun store(
        key: PaginationKey,
        text: String,
        ranges: List<PageRange>,
    ): Boolean

    suspend fun invalidateChapter(chapterId: String)
}

fun validPageRanges(text: String, ranges: List<PageRange>): Boolean {
    if (text.isEmpty()) return ranges.isEmpty()
    if (ranges.isEmpty()) return false
    if (ranges.first().start != 0) return false
    if (ranges.last().endExclusive != text.length) return false

    var expectedStart = 0
    ranges.forEachIndexed { index, page ->
        if (page.index != index || page.start != expectedStart) return false
        if (page.endExclusive <= page.start || page.endExclusive > text.length) return false
        if (splitsSurrogatePair(text, page.start) || splitsSurrogatePair(text, page.endExclusive)) {
            return false
        }
        expectedStart = page.endExclusive
    }
    return expectedStart == text.length
}

private fun splitsSurrogatePair(text: String, offset: Int): Boolean =
    offset in 1 until text.length &&
        Character.isHighSurrogate(text[offset - 1]) &&
        Character.isLowSurrogate(text[offset])
