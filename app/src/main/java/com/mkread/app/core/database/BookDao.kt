package com.mkread.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.mkread.app.core.model.BookSummary
import kotlinx.coroutines.flow.Flow

@Dao
abstract class BookDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertBook(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Transaction
    open suspend fun insertBookWithChapters(
        book: BookEntity,
        chapters: List<ChapterEntity>,
    ) {
        require(chapters.all { it.bookId == book.id }) {
            "Every chapter must belong to the inserted book"
        }
        insertBook(book)
        insertChapters(chapters)
    }

    @Query("SELECT * FROM books WHERE source_sha256 = :sourceSha256 LIMIT 1")
    abstract suspend fun getBySourceSha256(sourceSha256: String): BookEntity?

    @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
    abstract suspend fun getById(bookId: String): BookEntity?

    @Query("SELECT id FROM books ORDER BY id ASC")
    abstract suspend fun getAllIds(): List<String>

    @Query(
        """
        UPDATE books
        SET title = :title, author = :author, modified_at = :modifiedAt
        WHERE id = :bookId
        """,
    )
    abstract suspend fun updateMetadata(
        bookId: String,
        title: String,
        author: String?,
        modifiedAt: Long,
    ): Int

    @Query("UPDATE books SET last_opened_at = :openedAt WHERE id = :bookId")
    abstract suspend fun updateLastOpened(bookId: String, openedAt: Long): Int

    @Query("UPDATE books SET modified_at = :modifiedAt WHERE id = :bookId")
    abstract suspend fun updateModifiedAt(bookId: String, modifiedAt: Long): Int

    @Query("DELETE FROM books WHERE id = :bookId")
    abstract suspend fun deleteById(bookId: String): Int

    @Query("DELETE FROM books WHERE id IN (:bookIds)")
    abstract suspend fun deleteByIds(bookIds: List<String>): Int

    @Query(LIBRARY_BY_LAST_OPENED)
    abstract fun observeByLastOpened(escapedQuery: String): Flow<List<BookSummary>>

    @Query(LIBRARY_BY_TITLE)
    abstract fun observeByTitle(escapedQuery: String): Flow<List<BookSummary>>

    @Query(LIBRARY_BY_IMPORTED)
    abstract fun observeByImported(escapedQuery: String): Flow<List<BookSummary>>
}

fun escapeLikePattern(value: String): String = buildString(value.length) {
    value.forEach { character ->
        if (character == '\\' || character == '%' || character == '_') {
            append('\\')
        }
        append(character)
    }
}

private const val LIBRARY_PROJECTION = """
    SELECT
        books.id AS id,
        books.title AS title,
        books.author AS author,
        books.source_type AS sourceType,
        books.cover_relative_path AS coverPath,
        (
            SELECT chapters.title
            FROM chapters
            WHERE chapters.book_id = books.id
            ORDER BY chapters.ordinal ASC
            LIMIT 1
        ) AS chapterTitle,
        CAST(0 AS REAL) AS progressFraction,
        books.last_opened_at AS lastOpenedAt
    FROM books
    WHERE :escapedQuery = ''
        OR books.title LIKE '%' || :escapedQuery || '%' ESCAPE '\'
        OR COALESCE(books.author, '') LIKE '%' || :escapedQuery || '%' ESCAPE '\'
"""

private const val LIBRARY_BY_LAST_OPENED = LIBRARY_PROJECTION + """
    ORDER BY
        books.last_opened_at IS NULL ASC,
        books.last_opened_at DESC,
        books.imported_at DESC,
        books.title COLLATE NOCASE ASC,
        books.id ASC
"""

private const val LIBRARY_BY_TITLE = LIBRARY_PROJECTION + """
    ORDER BY
        books.title COLLATE NOCASE ASC,
        books.imported_at DESC,
        books.id ASC
"""

private const val LIBRARY_BY_IMPORTED = LIBRARY_PROJECTION + """
    ORDER BY
        books.imported_at DESC,
        books.title COLLATE NOCASE ASC,
        books.id ASC
"""
