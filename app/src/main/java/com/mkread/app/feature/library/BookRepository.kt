package com.mkread.app.feature.library

import com.mkread.app.core.database.BookEntity
import com.mkread.app.core.database.ChapterEntity

interface BookRepository {
    suspend fun findBookIdBySourceHash(sourceSha256: String): String?

    suspend fun commitImportedBook(
        book: BookEntity,
        chapters: List<ChapterEntity>,
    )
}

class DuplicateSourceException(
    val existingBookId: String?,
    cause: Throwable? = null,
) : Exception("The source hash is already imported", cause)
