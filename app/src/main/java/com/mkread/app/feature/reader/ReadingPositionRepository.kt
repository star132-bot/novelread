package com.mkread.app.feature.reader

data class ReadingPosition(
    val bookId: String,
    val chapterId: String,
    val characterOffset: Int,
    val pageIndex: Int,
    val sentenceIndex: Int,
    val updatedAt: Long,
)

interface ReadingPositionRepository {
    suspend fun get(bookId: String): ReadingPosition?

    /**
     * Saves a position when it moved, its sentence changed, the coalescing window elapsed, or
     * [force] is true. Returns true only when a database write occurred.
     */
    suspend fun save(
        position: ReadingPosition,
        chapterLength: Int,
        force: Boolean = false,
    ): Boolean

    suspend fun checkpoint(position: ReadingPosition, chapterLength: Int): Boolean

    suspend fun clear(bookId: String)
}
