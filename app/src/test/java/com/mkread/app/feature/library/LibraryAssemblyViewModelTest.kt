package com.mkread.app.feature.library

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.mkread.app.core.database.BookEntity
import com.mkread.app.core.database.ChapterEntity
import com.mkread.app.core.model.BookSummary
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryAssemblyViewModelTest {
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
    fun successfulAssemblyNotifiesAndOpensTheNewBook() = runTest(dispatcher) {
        val assembler = RecordingAssembler()
        val viewModel = LibraryViewModel(
            savedStateHandle = SavedStateHandle(),
            repository = EmptyBookRepository(),
            importManager = EmptyImportManager(),
            fragmentAssembler = assembler,
        )
        advanceTimeBy(LibraryViewModel.SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        val events = async { viewModel.events.take(2).toList() }
        runCurrent()
        val fragments = listOf(
            BookFragmentSource("one", "01 第一章"),
            BookFragmentSource("two", "02 第二章"),
        )

        viewModel.assembleFragments("完整小说", "folder-first", fragments)
        runCurrent()

        assertEquals(
            BookAssemblyRequest("完整小说", "folder-first", fragments),
            assembler.request,
        )
        assertEquals(
            listOf(
                LibraryEvent.OpenBook("assembled-book"),
                LibraryEvent.ShowSnackbar("已编排为一本书，共 2 章"),
            ),
            events.await(),
        )
        assertEquals(LibraryImportState.Idle, viewModel.uiState.value.importState)
    }

    @Test
    fun assemblyDoesNotStartWhileFolderImportsAreRunning() = runTest(dispatcher) {
        val imports = ControllableImportManager()
        val assembler = RecordingAssembler()
        val viewModel = LibraryViewModel(
            savedStateHandle = SavedStateHandle(),
            repository = EmptyBookRepository(),
            importManager = imports,
            fragmentAssembler = assembler,
        )
        advanceTimeBy(LibraryViewModel.SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        imports.emit(ImportWorkUpdate.Running(UUID.randomUUID()))
        runCurrent()

        viewModel.assembleFragments(
            "完整小说",
            "folder-first",
            listOf(BookFragmentSource("one", "01 第一章"), BookFragmentSource("two", "02 第二章")),
        )
        runCurrent()

        assertEquals(null, assembler.request)
        assertEquals(LibraryImportState.Importing, viewModel.uiState.value.importState)
    }

    @Test
    fun repeatedConfirmationStartsOnlyOneAssembly() = runTest(dispatcher) {
        val assembler = BlockingAssembler()
        val viewModel = LibraryViewModel(
            savedStateHandle = SavedStateHandle(),
            repository = EmptyBookRepository(),
            importManager = EmptyImportManager(),
            fragmentAssembler = assembler,
        )
        advanceTimeBy(LibraryViewModel.SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        val fragments = listOf(
            BookFragmentSource("one", "01 第一章"),
            BookFragmentSource("two", "02 第二章"),
        )

        viewModel.assembleFragments("完整小说", "folder-first", fragments)
        runCurrent()
        viewModel.assembleFragments("完整小说", "folder-first", fragments)
        runCurrent()

        assertEquals(1, assembler.requests.size)
        assembler.release.complete(Unit)
        runCurrent()
    }

    private class RecordingAssembler : BookFragmentAssembler {
        var request: BookAssemblyRequest? = null

        override suspend fun assemble(request: BookAssemblyRequest): BookAssemblyResult {
            this.request = request
            return BookAssemblyResult(
                bookId = "assembled-book",
                chapterCount = 2,
                removedFragmentCount = 2,
            )
        }
    }

    private class BlockingAssembler : BookFragmentAssembler {
        val requests = mutableListOf<BookAssemblyRequest>()
        val release = CompletableDeferred<Unit>()

        override suspend fun assemble(request: BookAssemblyRequest): BookAssemblyResult {
            requests += request
            release.await()
            return BookAssemblyResult("assembled-book", chapterCount = 2, removedFragmentCount = 2)
        }
    }

    private class EmptyBookRepository : BookRepository {
        override fun observeLibrary(query: LibraryQuery): Flow<List<BookSummary>> =
            MutableStateFlow(emptyList())

        override suspend fun updateMetadata(bookId: String, title: String, author: String?): Boolean = true

        override suspend fun markOpened(bookId: String): Boolean = true

        override suspend fun removeBook(bookId: String): Boolean = true

        override suspend fun reconcile() = Unit

        override suspend fun findBookIdBySourceHash(sourceSha256: String): String? = null

        override suspend fun commitImportedBook(book: BookEntity, chapters: List<ChapterEntity>) = Unit
    }

    private class EmptyImportManager : BookImportManager {
        override val updates: Flow<ImportWorkUpdate> = MutableSharedFlow()

        override fun enqueue(uri: Uri): UUID = UUID.randomUUID()
    }

    private class ControllableImportManager : BookImportManager {
        private val mutableUpdates = MutableSharedFlow<ImportWorkUpdate>(extraBufferCapacity = 4)
        override val updates: Flow<ImportWorkUpdate> = mutableUpdates

        override fun enqueue(uri: Uri): UUID = UUID.randomUUID()

        fun emit(update: ImportWorkUpdate) {
            check(mutableUpdates.tryEmit(update))
        }
    }
}
