package com.mkread.app.feature.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mkread.app.core.database.BookEntity
import com.mkread.app.core.database.ChapterEntity
import com.mkread.app.core.database.MkreadDatabase
import com.mkread.app.core.files.BookStorage
import com.mkread.app.core.files.CopiedSource
import com.mkread.app.core.files.ImportStaging
import com.mkread.app.core.files.StoredBookMetadata
import com.mkread.app.core.files.StoredChapter
import com.mkread.app.core.files.StorageException
import com.mkread.app.core.files.StorageFailure
import com.mkread.app.core.model.LibrarySort
import com.mkread.app.core.model.SourceType
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomBookRepositoryTest {
    private lateinit var database: MkreadDatabase
    private lateinit var storage: FakeBookStorage
    private lateinit var repository: RoomBookRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MkreadDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storage = FakeBookStorage()
        repository = RoomBookRepository(
            database = database,
            storage = storage,
            clock = { CLOCK_MILLIS },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun observeLibrary_blankQueryAndCaseInsensitiveSearch_matchTitleOrAuthor() = runBlocking {
        insert(book("moon", "Moon Light", author = "Writer"))
        insert(book("author", "Other", author = "Alice Chen"))
        insert(book("none", "Unrelated", author = null))

        val all = repository.observeLibrary(LibraryQuery(search = "   ")).first()
        val titleMatch = repository.observeLibrary(LibraryQuery(search = "MOON")).first()
        val authorMatch = repository.observeLibrary(LibraryQuery(search = "alice")).first()

        assertEquals(setOf("moon", "author", "none"), all.map { it.id }.toSet())
        assertEquals(listOf("moon"), titleMatch.map { it.id })
        assertEquals(listOf("author"), authorMatch.map { it.id })
    }

    @Test
    fun observeLibrary_appliesAllDeterministicSorts() = runBlocking {
        insert(book("alpha", "Alpha", importedAt = 100L, lastOpenedAt = null))
        insert(book("beta", "beta", importedAt = 300L, lastOpenedAt = 500L))
        insert(book("gamma", "Gamma", importedAt = 200L, lastOpenedAt = 700L))

        val lastOpened = repository.observeLibrary(
            LibraryQuery(sort = LibrarySort.LAST_OPENED),
        ).first()
        val title = repository.observeLibrary(
            LibraryQuery(sort = LibrarySort.TITLE),
        ).first()
        val imported = repository.observeLibrary(
            LibraryQuery(sort = LibrarySort.IMPORTED),
        ).first()

        assertEquals(listOf("gamma", "beta", "alpha"), lastOpened.map { it.id })
        assertEquals(listOf("alpha", "beta", "gamma"), title.map { it.id })
        assertEquals(listOf("beta", "gamma", "alpha"), imported.map { it.id })
    }

    @Test
    fun updateMetadata_normalizesWhitespaceAndOnlyChangesRoomMetadata() = runBlocking {
        insert(book("book", "Old title", author = "Old author", importedAt = 123L))

        val updated = repository.updateMetadata(
            bookId = "book",
            title = "  New\t\tTitle \n",
            author = "  Ada   Lovelace  ",
        )

        assertTrue(updated)
        val entity = requireNotNull(database.bookDao().getById("book"))
        assertEquals("New Title", entity.title)
        assertEquals("Ada Lovelace", entity.author)
        assertEquals(123L, entity.importedAt)
        assertEquals(CLOCK_MILLIS, entity.modifiedAt)
        assertTrue(storage.events.isEmpty())

        repository.updateMetadata("book", "New Title", "   ")
        assertNull(database.bookDao().getById("book")?.author)
    }

    @Test
    fun updateMetadata_validatesUnicodeCodePoints() = runBlocking {
        insert(book("book", "Original"))
        val emoji = "\uD83D\uDE00"

        assertLibraryFailure(LibraryFailure.TITLE_REQUIRED) {
            repository.updateMetadata("book", " \n\t ", null)
        }
        assertLibraryFailure(LibraryFailure.TITLE_TOO_LONG) {
            repository.updateMetadata("book", emoji.repeat(201), null)
        }
        assertLibraryFailure(LibraryFailure.AUTHOR_TOO_LONG) {
            repository.updateMetadata("book", "Valid", emoji.repeat(201))
        }

        assertTrue(repository.updateMetadata("book", emoji.repeat(200), null))
    }

    @Test
    fun markOpened_updatesLastOpenedTimestamp() = runBlocking {
        insert(book("book", "Book", lastOpenedAt = null))

        assertTrue(repository.markOpened("book"))

        assertEquals(CLOCK_MILLIS, database.bookDao().getById("book")?.lastOpenedAt)
    }

    @Test
    fun removeBook_deletesPrivateFilesThenCascadesRoomRows() = runBlocking {
        insert(book("book", "Book"), listOf(chapter("book", 1)))
        storage.bookIds += "book"

        val removed = repository.removeBook("book")

        assertTrue(removed)
        assertEquals(listOf("delete:book"), storage.events)
        assertNull(database.bookDao().getById("book"))
        assertTrue(database.chapterDao().getByBookId("book").isEmpty())
    }

    @Test
    fun removeBook_fileFailureIsRecoverableAndKeepsVisibleRoomRow() = runBlocking {
        insert(book("book", "Book"))
        storage.bookIds += "book"
        storage.deleteFailure = StorageException(StorageFailure.IO_ERROR, "injected")

        assertLibraryFailure(LibraryFailure.FILE_DELETE) {
            repository.removeBook("book")
        }

        assertTrue(storage.bookIds.contains("book"))
        assertEquals(listOf("book"), repository.observeLibrary(LibraryQuery()).first().map { it.id })
    }

    @Test
    fun removeBook_databaseFailureHidesTombstoneUntilReconciliation() = runBlocking {
        insert(book("book", "Book"))
        storage.bookIds += "book"
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER block_book_delete
            BEFORE DELETE ON books
            WHEN OLD.id = 'book'
            BEGIN
                SELECT RAISE(ABORT, 'injected delete failure');
            END
            """.trimIndent(),
        )

        assertLibraryFailure(LibraryFailure.DATABASE_DELETE) {
            repository.removeBook("book")
        }

        assertFalse(storage.bookIds.contains("book"))
        assertTrue(repository.observeLibrary(LibraryQuery()).first().isEmpty())
        assertEquals("book", database.bookDao().getById("book")?.id)

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER block_book_delete")
        repository.reconcile()
        assertNull(database.bookDao().getById("book"))
    }

    @Test
    fun reconcile_cleansStaleAndOrphanStorageAndMissingRoomRows() = runBlocking {
        insert(book("retained", "Retained"))
        insert(book("missing", "Missing"))
        storage.bookIds += setOf("retained", "orphan")

        repository.reconcile()

        assertEquals(
            listOf(
                "clean-stale:$CLOCK_MILLIS",
                "clean-orphans:missing,retained",
                "exists:missing",
                "exists:retained",
            ),
            storage.events,
        )
        assertEquals(setOf("retained"), storage.bookIds)
        assertNull(database.bookDao().getById("missing"))
        assertEquals("retained", database.bookDao().getById("retained")?.id)
    }

    private suspend fun insert(
        book: BookEntity,
        chapters: List<ChapterEntity> = emptyList(),
    ) {
        database.bookDao().insertBookWithChapters(book, chapters)
    }

    private suspend fun assertLibraryFailure(
        expected: LibraryFailure,
        block: suspend () -> Unit,
    ) {
        try {
            block()
            fail("Expected library failure $expected")
        } catch (failure: LibraryException) {
            assertEquals(expected, failure.failure)
        }
    }

    private fun book(
        id: String,
        title: String,
        author: String? = null,
        importedAt: Long = 1_000L,
        lastOpenedAt: Long? = null,
    ) = BookEntity(
        id = id,
        title = title,
        author = author,
        sourceType = SourceType.TXT,
        sourceSha256 = "hash-$id",
        coverRelativePath = null,
        importedAt = importedAt,
        modifiedAt = importedAt,
        lastOpenedAt = lastOpenedAt,
    )

    private fun chapter(bookId: String, ordinal: Int) = ChapterEntity(
        id = "$bookId:${ordinal.toString().padStart(4, '0')}",
        bookId = bookId,
        ordinal = ordinal,
        title = "Chapter $ordinal",
        relativePath = "chapter-${ordinal.toString().padStart(4, '0')}.txt",
        characterCount = 4,
        contentSha256 = "chapter-hash-$ordinal",
    )

    private class FakeBookStorage : BookStorage {
        val bookIds = linkedSetOf<String>()
        val events = mutableListOf<String>()
        var deleteFailure: StorageException? = null

        override fun begin(transactionId: String): ImportStaging = unused()

        override fun copySource(
            input: InputStream,
            staging: ImportStaging,
            extension: String,
        ): CopiedSource = unused()

        override fun writeChapter(
            staging: ImportStaging,
            ordinal: Int,
            text: String,
        ): StoredChapter = unused()

        override fun writeMetadata(staging: ImportStaging, metadata: StoredBookMetadata) = unused<Unit>()

        override fun writeCover(staging: ImportStaging, bytes: ByteArray): String = unused()

        override fun promote(staging: ImportStaging, bookId: String): File = unused()

        override fun discard(staging: ImportStaging) = unused<Unit>()

        override fun deleteBook(bookId: String) {
            events += "delete:$bookId"
            deleteFailure?.let { throw it }
            bookIds -= bookId
        }

        override fun bookExists(bookId: String): Boolean {
            events += "exists:$bookId"
            return bookId in bookIds
        }

        override fun cleanStaleTransactions(nowMillis: Long) {
            events += "clean-stale:$nowMillis"
        }

        override fun cleanOrphanBooks(retainedBookIds: Set<String>) {
            val retained = retainedBookIds.sorted().joinToString(",")
            events += "clean-orphans:$retained"
            bookIds.retainAll(retainedBookIds)
        }

        private fun <T> unused(): T = error("Not used by repository tests")
    }

    private companion object {
        const val CLOCK_MILLIS = 9_000L
    }
}
