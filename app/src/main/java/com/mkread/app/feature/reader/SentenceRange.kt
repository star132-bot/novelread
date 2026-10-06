package com.mkread.app.feature.reader

data class SentenceRange(
    val index: Int,
    val startInclusive: Int,
    val endExclusive: Int,
) {
    init {
        require(index >= 0) { "Sentence index must be non-negative" }
        require(startInclusive >= 0) { "Sentence start must be non-negative" }
        require(endExclusive > startInclusive) { "Sentence range must be non-empty" }
    }

    fun contains(offset: Int): Boolean = offset in startInclusive until endExclusive
}

fun List<SentenceRange>.sentenceAt(offset: Int): SentenceRange? {
    var low = 0
    var high = lastIndex
    while (low <= high) {
        val middle = (low + high).ushr(1)
        val range = this[middle]
        when {
            offset < range.startInclusive -> high = middle - 1
            offset >= range.endExclusive -> low = middle + 1
            else -> return range
        }
    }
    return null
}

fun List<SentenceRange>.nearestBoundary(offset: Int): SentenceRange? {
    sentenceAt(offset)?.let { return it }
    if (isEmpty()) return null

    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high).ushr(1)
        if (this[middle].startInclusive < offset) {
            low = middle + 1
        } else {
            high = middle
        }
    }
    return getOrNull(low) ?: last()
}
