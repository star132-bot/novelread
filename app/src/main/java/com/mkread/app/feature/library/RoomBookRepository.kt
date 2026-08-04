package com.mkread.app.feature.library

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.mkread.app.core.database.BookEntity
import com.mkread.app.core.database.ChapterEntity
import com.mkread.app.core.database.MkreadDatabase

class RoomBookRepository(
    private val database: MkreadDatabase,
) : BookRepository {
    override suspend fun findBookIdBySourceHash(sourceSha256: String): String? =
        database.bookDao().getBySourceSha256(sourceSha256)?.id

    override suspend fun commitImportedBook(
        book: BookEntity,
        chapters: List<ChapterEntity>,
    ) {
        try {
            database.withTransaction {
                database.bookDao().insertBookWithChapters(book, chapters)
            }
        } catch (failure: SQLiteConstraintException) {
            val existing = database.bookDao().getBySourceSha256(book.sourceSha256)?.id
            throw DuplicateSourceException(existing, failure)
        }
    }
}
