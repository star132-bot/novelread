package com.mkread.app.feature.library

import com.mkread.app.core.database.BookEntity
import com.mkread.app.core.database.ChapterEntity
import com.mkread.app.core.model.BookSummary
import kotlinx.coroutines.flow.Flow

interface BookImportRepository {
    suspend fun findBookIdBySourceHash(sourceSha256: String): String?

    suspend fun commitImportedBook(
        book: BookEntity,
        chapters: List<ChapterEntity>,
    )
}

interface BookRepository : BookImportRepository {
    fun observeLibrary(query: LibraryQuery): Flow<List<BookSummary>>

    suspend fun updateMetadata(
        bookId: String,
        title: String,
        author: String?,
    ): Boolean

    suspend fun markOpened(bookId: String): Boolean

    suspend fun removeBook(bookId: String): Boolean

    suspend fun reconcile()
}

class DuplicateSourceException(
    val existingBookId: String?,
    cause: Throwable? = null,
) : Exception("The source hash is already imported", cause)
