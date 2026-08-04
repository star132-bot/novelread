package com.mkread.app.feature.library

import androidx.lifecycle.SavedStateHandle
import com.mkread.app.core.database.BookEntity
import com.mkread.app.core.database.ChapterEntity
import com.mkread.app.core.model.BookSummary
import com.mkread.app.core.model.LibrarySort
import com.mkread.app.core.model.SourceType
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun searchWaitsForTwoHundredMilliseconds() = runTest(dispatcher) {
        val repository = FakeBookRepository()
        val viewModel = viewModel(repository = repository)
        settleInitialQuery()

        viewModel.onSearchQueryChanged("  星河  ")
        advanceTimeBy(199)
        runCurrent()
        assertEquals("", repository.queries.last().search)

        advanceTimeBy(1)
        runCurrent()
        assertEquals("  星河  ", repository.queries.last().search)
    }

    @Test
    fun searchTextIsReflectedInUiStateBeforeDebouncedQueryRuns() = runTest(dispatcher) {
        val repository = FakeBookRepository()
        val viewModel = viewModel(repository = repository)
        settleInitialQuery()

        viewModel.onSearchQueryChanged("星河")

        assertEquals("星河", viewModel.uiState.value.query)
        assertEquals("", repository.queries.last().search)
    }

    @Test
    fun selectedSortIsRestoredFromSavedState() = runTest(dispatcher) {
        val savedState = SavedStateHandle()
        val firstRepository = FakeBookRepository()
        val first = viewModel(savedState = savedState, repository = firstRepository)

        first.onSortSelected(LibrarySort.TITLE)
        runCurrent()
        assertEquals(LibrarySort.TITLE.name, savedState.get<String>(LibraryViewModel.SORT_KEY))

        val secondRepository = FakeBookRepository()
        viewModel(savedState = savedState, repository = secondRepository)
        settleInitialQuery()
        assertEquals(LibrarySort.TITLE, secondRepository.queries.last().sort)
    }

    @Test
    fun activeImportIsPartOfDurableUiState() = runTest(dispatcher) {
        val imports = FakeBookImportManager()
        val viewModel = viewModel(imports = imports)
        settleInitialQuery()

        imports.emit(ImportWorkUpdate.Running(WORK_ID))
        runCurrent()

        assertEquals(LibraryImportState.Importing, viewModel.uiState.value.importState)
    }

    @Test
    fun duplicateImportProducesOneShotNotification() = runTest(dispatcher) {
        val imports = FakeBookImportManager()
        val viewModel = viewModel(imports = imports)
        settleInitialQuery()
        val event = async { viewModel.events.first() }
        runCurrent()

        imports.emit(ImportWorkUpdate.Duplicate(WORK_ID, "existing-book"))
        runCurrent()

        assertEquals(
            LibraryEvent.ShowSnackbar("这本小说已经在书架中"),
            event.await(),
        )
        assertEquals(LibraryImportState.Idle, viewModel.uiState.value.importState)
    }

    @Test
    fun renameValidationFailureIsShownAndDoesNotUpdate() = runTest(dispatcher) {
        val repository = FakeBookRepository().apply {
            metadataFailure = LibraryException(
                LibraryFailure.TITLE_REQUIRED,
                "Book title is required",
            )
        }
        val viewModel = viewModel(repository = repository)
        settleInitialQuery()
        val event = async { viewModel.events.first() }
        runCurrent()

        viewModel.confirmRename(BOOK, "   ")
        runCurrent()

        assertEquals(LibraryEvent.ShowSnackbar("书名不能为空"), event.await())
        assertTrue(repository.metadataUpdates.isEmpty())
    }

    @Test
    fun successfulRenamePreservesExistingAuthor() = runTest(dispatcher) {
        val repository = FakeBookRepository()
        val viewModel = viewModel(repository = repository)
        settleInitialQuery()

        viewModel.confirmRename(BOOK, "新书名")
        runCurrent()

        assertEquals(
            listOf(Triple(BOOK.id, "新书名", BOOK.author)),
            repository.metadataUpdates,
        )
    }

    @Test
    fun removalOnlyRunsAfterExplicitConfirmation() = runTest(dispatcher) {
        val repository = FakeBookRepository()
        val viewModel = viewModel(repository = repository)
        settleInitialQuery()
        val event = async { viewModel.events.first() }
        runCurrent()

        viewModel.requestRemove(BOOK)
        runCurrent()

        assertEquals(LibraryEvent.ConfirmRemoval(BOOK), event.await())
        assertTrue(repository.removedBookIds.isEmpty())

        viewModel.confirmRemove(BOOK.id)
        runCurrent()
        assertEquals(listOf(BOOK.id), repository.removedBookIds)
    }

    private fun viewModel(
        savedState: SavedStateHandle = SavedStateHandle(),
        repository: FakeBookRepository = FakeBookRepository(),
        imports: FakeBookImportManager = FakeBookImportManager(),
    ) = LibraryViewModel(
        savedStateHandle = savedState,
        repository = repository,
        importManager = imports,
    )

    private suspend fun TestScope.settleInitialQuery() {
        advanceTimeBy(LibraryViewModel.SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
    }

    private class FakeBookRepository : BookRepository {
        val books = MutableStateFlow<List<BookSummary>>(emptyList())
        val queries = mutableListOf<LibraryQuery>()
        val metadataUpdates = mutableListOf<Triple<String, String, String?>>()
        val removedBookIds = mutableListOf<String>()
        var metadataFailure: LibraryException? = null

        override fun observeLibrary(query: LibraryQuery): Flow<List<BookSummary>> {
            queries += query
            return books
        }

        override suspend fun updateMetadata(bookId: String, title: String, author: String?): Boolean {
            metadataFailure?.let { throw it }
            metadataUpdates += Triple(bookId, title, author)
            return true
        }

        override suspend fun markOpened(bookId: String): Boolean = true

        override suspend fun removeBook(bookId: String): Boolean {
            removedBookIds += bookId
            return true
        }

        override suspend fun reconcile() = Unit

        override suspend fun findBookIdBySourceHash(sourceSha256: String): String? = null

        override suspend fun commitImportedBook(book: BookEntity, chapters: List<ChapterEntity>) = Unit
    }

    private class FakeBookImportManager : BookImportManager {
        private val mutableUpdates = MutableSharedFlow<ImportWorkUpdate>(extraBufferCapacity = 4)
        override val updates: Flow<ImportWorkUpdate> = mutableUpdates

        override fun enqueue(uri: android.net.Uri): UUID = WORK_ID

        fun emit(update: ImportWorkUpdate) {
            check(mutableUpdates.tryEmit(update))
        }
    }

    private companion object {
        val WORK_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000008")
        val BOOK = BookSummary(
            id = "book-1",
            title = "星河",
            author = "作者",
            sourceType = SourceType.TXT,
            coverPath = null,
            chapterTitle = "第一章",
            progressFraction = 0.25f,
            lastOpenedAt = null,
        )
    }
}
