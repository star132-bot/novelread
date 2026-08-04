package com.mkread.app.feature.reader

import com.mkread.app.core.database.ChapterEntity

data class ChapterContent(
    val chapter: ChapterEntity,
    val text: String,
    val contentSha256: String,
)

interface ChapterContentRepository {
    suspend fun load(chapterId: String): ChapterContent

    suspend fun listChapters(bookId: String): List<ChapterEntity>
}

interface ChapterMetadataSource {
    suspend fun getChapter(chapterId: String): ChapterEntity?

    suspend fun getChapters(bookId: String): List<ChapterEntity>
}

enum class ChapterContentFailure {
    NOT_FOUND,
    UNSAFE_PATH,
    TOO_LARGE,
    INVALID_UTF8,
    HASH_MISMATCH,
    IO_ERROR,
}

class ChapterContentException(
    val failure: ChapterContentFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
