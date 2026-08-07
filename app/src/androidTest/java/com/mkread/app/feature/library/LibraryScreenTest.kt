package com.mkread.app.feature.library

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mkread.app.core.database.BookEntity
import com.mkread.app.core.database.ChapterEntity
import com.mkread.app.core.database.ShelfFolderEntity
import com.mkread.app.core.model.BookSummary
import com.mkread.app.core.model.LibrarySort
import com.mkread.app.core.model.SourceType
import com.mkread.app.ui.theme.MkreadTheme
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyShelfShowsSingleImportCommand() {
        launchLibrary(emptyList())

        composeRule.onNodeWithText("书架中还没有小说").assertIsDisplayed()
        composeRule.onNodeWithText("导入小说").assertIsDisplayed()
        composeRule.onAllNodes(
            hasText("导入小说") or hasContentDescription("导入小说"),
        ).assertCountEquals(1)
    }

    @Test
    fun emptyShelfStillShowsCreatedFolders() {
        launchLibrary(
            books = emptyList(),
            folders = listOf(FOLDER),
        )

        composeRule.onNodeWithText(FOLDER.name).assertIsDisplayed()
    }

    @Test
    fun searchInputUpdatesRepositoryAfterDebounce() {
        val harness = launchLibrary(listOf(BOOK))

        composeRule.onNodeWithContentDescription("搜索").performClick()
        composeRule.onNodeWithTag("library-search").performTextInput("星河")

        composeRule.waitUntil(timeoutMillis = 3_000) {
            harness.repository.queries.lastOrNull()?.search == "星河"
        }
    }

    @Test
    fun sortMenuSelectsTitleOrder() {
        val harness = launchLibrary(listOf(BOOK))

        composeRule.onNodeWithContentDescription("排序").performClick()
        composeRule.onNodeWithText("按标题").performClick()

        composeRule.waitUntil(timeoutMillis = 3_000) {
            harness.repository.queries.lastOrNull()?.sort == LibrarySort.TITLE
        }
    }

    @Test
    fun successfulImportShowsSnackbar() {
        val harness = launchLibrary(emptyList())

        composeRule.runOnIdle {
            harness.imports.emit(ImportWorkUpdate.Success(WORK_ID, BOOK.id))
        }

        composeRule.onNodeWithText("小说导入成功").assertIsDisplayed()
    }

    @Test
    fun duplicateImportShowsSnackbar() {
        val harness = launchLibrary(emptyList())

        composeRule.runOnIdle {
            harness.imports.emit(ImportWorkUpdate.Duplicate(WORK_ID, BOOK.id))
        }

        composeRule.onNodeWithText("这本小说已经在书架中").assertIsDisplayed()
    }

    @Test
    fun renamePreservesAuthorAndSavesNewTitle() {
        val harness = launchLibrary(listOf(BOOK))

        openBookMenu()
        composeRule.onNodeWithText("重命名").performClick()
        composeRule.onNodeWithTag("rename-title").performTextClearance()
        composeRule.onNodeWithTag("rename-title").performTextInput("新书名")
        composeRule.onNodeWithText("保存").performClick()

        composeRule.waitUntil(timeoutMillis = 3_000) {
            harness.repository.metadataUpdates.isNotEmpty()
        }
        assertEquals(Triple(BOOK.id, "新书名", BOOK.author), harness.repository.metadataUpdates.single())
    }

    @Test
    fun metadataDialogEditsTitleAndAuthor() {
        val harness = launchLibrary(listOf(BOOK))

        openBookMenu()
        composeRule.onNodeWithText("编辑信息").performClick()
        composeRule.onNodeWithTag("metadata-title").performTextClearance()
        composeRule.onNodeWithTag("metadata-title").performTextInput("远方")
        composeRule.onNodeWithTag("metadata-author").performTextClearance()
        composeRule.onNodeWithTag("metadata-author").performTextInput("新作者")
        composeRule.onNodeWithText("保存").performClick()

        composeRule.waitUntil(timeoutMillis = 3_000) {
            harness.repository.metadataUpdates.isNotEmpty()
        }
        assertEquals(Triple(BOOK.id, "远方", "新作者"), harness.repository.metadataUpdates.single())
    }

    @Test
    fun cancellingRemovalKeepsBook() {
        val harness = launchLibrary(listOf(BOOK))

        openBookMenu()
        composeRule.onNodeWithText("移出书架").performClick()
        composeRule.onNodeWithText("原始小说文件会保留").assertIsDisplayed()
        composeRule.onNodeWithText("取消").performClick()
        composeRule.runOnIdle {
            assertTrue(harness.repository.removedBookIds.isEmpty())
        }
    }

    @Test
    fun confirmingRemovalRemovesBook() {
        val harness = launchLibrary(listOf(BOOK))

        openBookMenu()
        composeRule.onNodeWithText("移出书架").performClick()
        composeRule.onNodeWithText("确认移出").performClick()

        composeRule.waitUntil(timeoutMillis = 3_000) {
            harness.repository.removedBookIds == listOf(BOOK.id)
        }
    }

    @Test
    fun iconOnlyControlsHaveContentDescriptions() {
        launchLibrary(listOf(BOOK), showDebugAction = true)

        composeRule.onNodeWithContentDescription("搜索").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("排序").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("导入小说").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("更多选项").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("《星河》更多操作").assertIsDisplayed()
    }

    private fun openBookMenu() {
        composeRule.onNodeWithContentDescription("《星河》更多操作").performClick()
    }

    private fun launchLibrary(
        books: List<BookSummary>,
        folders: List<ShelfFolderEntity> = emptyList(),
        showDebugAction: Boolean = false,
    ): Harness {
        val repository = FakeBookRepository(books, folders)
        val imports = FakeBookImportManager()
        val viewModel = LibraryViewModel(SavedStateHandle(), repository, imports)
        val harness = Harness(repository, imports)
        composeRule.setContent {
            MkreadTheme {
                LibraryRoute(
                    viewModel = viewModel,
                    onOpenBook = { harness.openedBookIds += it },
                    onOpenSpeechDebug = if (showDebugAction) ({ Unit }) else null,
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 3_000) {
            repository.queries.isNotEmpty()
        }
        if (books.isNotEmpty()) {
            composeRule.onNodeWithText(books.first().title).assertIsDisplayed()
        }
        return harness
    }

    private data class Harness(
        val repository: FakeBookRepository,
        val imports: FakeBookImportManager,
        val openedBookIds: MutableList<String> = mutableListOf(),
    )

    private class FakeBookRepository(
        initialBooks: List<BookSummary>,
        initialFolders: List<ShelfFolderEntity>,
    ) : BookRepository {
        private val books = MutableStateFlow(initialBooks)
        private val folders = MutableStateFlow(initialFolders)
        val queries = mutableListOf<LibraryQuery>()
        val metadataUpdates = mutableListOf<Triple<String, String, String?>>()
        val removedBookIds = mutableListOf<String>()

        override fun observeLibrary(query: LibraryQuery): Flow<List<BookSummary>> {
            queries += query
            return books
        }

        override fun observeFolders(): Flow<List<ShelfFolderEntity>> = folders

        override suspend fun updateMetadata(bookId: String, title: String, author: String?): Boolean {
            normalizeBookMetadata(title, author)
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

        override fun enqueue(uri: Uri): UUID = WORK_ID

        fun emit(update: ImportWorkUpdate) {
            check(mutableUpdates.tryEmit(update))
        }
    }

    private companion object {
        val WORK_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000008")
        val FOLDER = ShelfFolderEntity(
            id = "folder-1",
            name = "TestBooks",
            createdAt = 1_786_000_000_000L,
        )
        val BOOK = BookSummary(
            id = "book-1",
            title = "星河",
            author = "林舟",
            sourceType = SourceType.TXT,
            coverPath = null,
            chapterTitle = "第一章 雨夜",
            progressFraction = 0.32f,
            lastOpenedAt = 1_786_000_000_000L,
        )
    }
}
