package com.mkread.app.feature.reader

data class ChapterEditResult(
    val chapterId: String,
    val oldContentSha256: String,
    val newContentSha256: String,
    val characterCount: Int,
    val mappedOffset: Int,
    val undoAvailable: Boolean,
    val cleanupRecorded: Boolean,
)

interface ChapterEditor {
    suspend fun save(
        chapterId: String,
        newText: String,
        currentOffset: Int,
    ): ChapterEditResult

    suspend fun undo(chapterId: String, currentOffset: Int): ChapterEditResult?

    suspend fun hasUndo(chapterId: String): Boolean
}

enum class ChapterEditFailure {
    NOT_FOUND,
    BLANK_CHAPTER,
    TOO_LARGE,
    UNSAFE_PATH,
    CORRUPT_CURRENT,
    FILE_IO,
    DATABASE,
    CLEANUP_MARKER,
}

class ChapterEditException(
    val failure: ChapterEditFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
