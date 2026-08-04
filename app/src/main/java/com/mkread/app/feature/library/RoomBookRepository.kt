package com.mkread.app.feature.library

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.mkread.app.core.database.BookEntity
import com.mkread.app.core.database.ChapterEntity
import com.mkread.app.core.database.MkreadDatabase
import com.mkread.app.core.database.escapeLikePattern
import com.mkread.app.core.files.BookStorage
import com.mkread.app.core.model.BookSummary
import com.mkread.app.core.model.LibrarySort
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomBookRepository(
    private val database: MkreadDatabase,
    private val storage: BookStorage,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BookRepository {
    private val deletionGuard = ConcurrentHashMap.newKeySet<String>()
    private val diagnosticTombstones = ConcurrentHashMap.newKeySet<String>()

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
            diagnosticTombstones.remove(book.id)
        } catch (failure: SQLiteConstraintException) {
            val existing = database.bookDao().getBySourceSha256(book.sourceSha256)?.id
            throw DuplicateSourceException(existing, failure)
        }
    }

    override fun observeLibrary(query: LibraryQuery): Flow<List<BookSummary>> {
        val escapedQuery = escapeLikePattern(query.search.normalizeBookWhitespace())
        val source = when (query.sort) {
            LibrarySort.LAST_OPENED -> database.bookDao().observeByLastOpened(escapedQuery)
            LibrarySort.TITLE -> database.bookDao().observeByTitle(escapedQuery)
            LibrarySort.IMPORTED -> database.bookDao().observeByImported(escapedQuery)
        }
        return source.map { books ->
            books.filterNot { book -> isHidden(book.id) }
        }
    }

    override suspend fun updateMetadata(
        bookId: String,
        title: String,
        author: String?,
    ): Boolean {
        val metadata = normalizeBookMetadata(title, author)
        if (isHidden(bookId)) return false
        return database.bookDao().updateMetadata(
            bookId = bookId,
            title = metadata.title,
            author = metadata.author,
            modifiedAt = clock(),
        ) > 0
    }

    override suspend fun markOpened(bookId: String): Boolean {
        if (isHidden(bookId)) return false
        return database.bookDao().updateLastOpened(bookId, clock()) > 0
    }

    override suspend fun removeBook(bookId: String): Boolean = withContext(ioDispatcher) {
        if (!deletionGuard.add(bookId) || bookId in diagnosticTombstones) {
            return@withContext false
        }
        try {
            try {
                storage.deleteBook(bookId)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                throw LibraryException(
                    LibraryFailure.FILE_DELETE,
                    "Unable to delete private files for book $bookId",
                    failure,
                )
            }

            try {
                val deleted = database.withTransaction {
                    database.bookDao().deleteById(bookId)
                }
                diagnosticTombstones.remove(bookId)
                deleted > 0
            } catch (failure: CancellationException) {
                diagnosticTombstones.add(bookId)
                throw failure
            } catch (failure: Exception) {
                diagnosticTombstones.add(bookId)
                throw LibraryException(
                    LibraryFailure.DATABASE_DELETE,
                    "Private files were removed but the database row remains for $bookId",
                    failure,
                )
            }
        } finally {
            deletionGuard.remove(bookId)
        }
    }

    override suspend fun reconcile() = withContext(ioDispatcher) {
        try {
            storage.cleanStaleTransactions(clock())
            val roomBookIds = database.bookDao().getAllIds().toSet()
            diagnosticTombstones.retainAll(roomBookIds)
            storage.cleanOrphanBooks(roomBookIds)
            val missingBookIds = roomBookIds.filterNot(storage::bookExists)
            if (missingBookIds.isNotEmpty()) {
                diagnosticTombstones.addAll(missingBookIds)
                database.withTransaction {
                    database.bookDao().deleteByIds(missingBookIds)
                }
                diagnosticTombstones.removeAll(missingBookIds.toSet())
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            throw LibraryException(
                LibraryFailure.RECONCILIATION,
                "Unable to reconcile private book storage and Room metadata",
                failure,
            )
        }
    }

    private fun isHidden(bookId: String): Boolean =
        bookId in deletionGuard || bookId in diagnosticTombstones
}
