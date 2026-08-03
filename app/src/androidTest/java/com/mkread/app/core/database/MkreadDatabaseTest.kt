package com.mkread.app.core.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mkread.app.core.model.SourceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MkreadDatabaseTest {
    private lateinit var database: MkreadDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MkreadDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun insertBookWithChapters_observesBook_andKeepsChapterOrder() = runBlocking {
        val book = book(id = "book-1", hash = "hash-1", title = "A Book")
        val chapters = listOf(
            chapter(bookId = book.id, ordinal = 2, title = "Second"),
            chapter(bookId = book.id, ordinal = 1, title = "First"),
        )

        database.bookDao().insertBookWithChapters(book, chapters)

        val library = database.bookDao().observeByImported(escapeLikePattern("")).first()
        assertEquals(listOf(book.id), library.map { it.id })
        assertEquals("First", library.single().chapterTitle)
        assertEquals(
            listOf(1, 2),
            database.chapterDao().getByBookId(book.id).map { it.ordinal },
        )
    }

    @Test
    fun duplicateSourceHash_isRejected() = runBlocking {
        database.bookDao().insertBook(book(id = "book-1", hash = "same-hash"))

        try {
            database.bookDao().insertBook(book(id = "book-2", hash = "same-hash"))
            fail("Expected duplicate source hash to be rejected")
        } catch (_: SQLiteConstraintException) {
            // Expected unique-index failure.
        }
    }

    @Test
    fun deletingBook_cascadesToChapters() = runBlocking {
        val book = book(id = "book-1", hash = "hash-1")
        database.bookDao().insertBookWithChapters(
            book,
            listOf(chapter(bookId = book.id, ordinal = 1, title = "Only")),
        )

        database.bookDao().deleteById(book.id)

        assertTrue(database.chapterDao().getByBookId(book.id).isEmpty())
    }

    @Test
    fun librarySearch_escapesLikeWildcards() = runBlocking {
        database.bookDao().insertBook(book("percent", "hash-1", "100% True"))
        database.bookDao().insertBook(book("underscore", "hash-2", "Plain", "Author_Name"))
        database.bookDao().insertBook(book("other", "hash-3", "Other"))

        val percent = database.bookDao()
            .observeByTitle(escapeLikePattern("%"))
            .first()
        val underscore = database.bookDao()
            .observeByTitle(escapeLikePattern("_"))
            .first()

        assertEquals(listOf("percent"), percent.map { it.id })
        assertEquals(listOf("underscore"), underscore.map { it.id })
    }

    private fun book(
        id: String,
        hash: String,
        title: String = "Book",
        author: String? = null,
    ) = BookEntity(
        id = id,
        title = title,
        author = author,
        sourceType = SourceType.TXT,
        sourceSha256 = hash,
        coverRelativePath = null,
        importedAt = 1_000L,
        modifiedAt = 1_000L,
        lastOpenedAt = null,
    )

    private fun chapter(
        bookId: String,
        ordinal: Int,
        title: String,
    ) = ChapterEntity(
        id = "$bookId:${ordinal.toString().padStart(4, '0')}",
        bookId = bookId,
        ordinal = ordinal,
        title = title,
        relativePath = "chapter-${ordinal.toString().padStart(4, '0')}.txt",
        characterCount = title.length,
        contentSha256 = "content-$ordinal",
    )
}
