package com.mkread.app.feature.library

import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.mkread.app.MainActivity
import com.mkread.app.MkreadApplication
import com.mkread.app.core.database.ShelfFolderEntity
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FragmentAssemblyJourneyTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MkreadApplication
        get() = composeRule.activity.application as MkreadApplication

    private val runId = System.nanoTime().toString(36).lowercase(Locale.ROOT)
    private val folderName = "章节编排-$runId"
    private lateinit var folder: ShelfFolderEntity
    private val fragmentBookIds = mutableListOf<String>()
    private val sourceUris = mutableListOf<Uri>()
    private val sourcePayloads = mutableListOf<ByteArray>()
    private var assembledBookId: String? = null

    @Before
    fun seedFragmentBooks() {
        ImportTestContentProvider.clear()
        folder = runBlocking { application.container.repository.getOrCreateFolder(folderName) }
        fragmentBookIds += importFragment(
            key = "assembly-$runId-two",
            displayName = "02 第二章 回声.txt",
            text = "第一卷\n第二章 回声\n\n第二章正文，窗外的雨声逐渐清晰。",
        )
        fragmentBookIds += importFragment(
            key = "assembly-$runId-one",
            displayName = "01 第一章 起点.txt",
            text = "第一卷\n第一章 起点\n\n第一章正文，列车从清晨的站台出发。",
        )
    }

    @After
    fun cleanUpFragmentBooks() {
        runBlocking {
            (fragmentBookIds + listOfNotNull(assembledBookId)).distinct().forEach { bookId ->
                runCatching { application.container.repository.removeBook(bookId) }
            }
            if (::folder.isInitialized) runCatching { application.container.repository.deleteFolder(folder.id) }
        }
        ImportTestContentProvider.clear()
    }

    @Test
    fun fragmentsBecomeOneBookAndNextChapterNavigates() {
        waitForText(folderName)
        composeRule.onNodeWithText(folderName).performClick()
        waitForText("01 第一章 起点")
        waitForText("02 第二章 回声")

        composeRule.onNodeWithContentDescription("更多选项").performClick()
        composeRule.onNodeWithText("编排成一本书").performClick()
        composeRule.onNodeWithText("开始编排").performClick()

        waitForText("第一章 起点")
        waitForContentDescription("下一章")
        composeRule.onNodeWithContentDescription("下一章").performClick()
        waitForText("第二章 回声")

        val chapters = runBlocking {
            val assembled = application.container.repository
                .observeLibrary(LibraryQuery())
                .first()
                .single { book -> book.folderId == folder.id && book.id !in fragmentBookIds }
            assembledBookId = assembled.id
            fragmentBookIds.forEach { sourceId ->
                assertNull(application.container.database.bookDao().getById(sourceId))
            }
            application.container.database.chapterDao().getByBookId(assembled.id)
        }
        assertEquals(listOf("第一章 起点", "第二章 回声"), chapters.map { it.title })
        sourceUris.zip(sourcePayloads).forEach { (uri, expected) ->
            val actual = requireNotNull(application.contentResolver.openInputStream(uri)).use { it.readBytes() }
            assertArrayEquals(expected, actual)
        }
    }

    private fun importFragment(key: String, displayName: String, text: String): String {
        val payload = text.toByteArray(Charsets.UTF_8)
        val uri = ImportTestContentProvider.register(key, payload)
        sourceUris += uri
        sourcePayloads += payload
        val result = runBlocking {
            TestListenableWorkerBuilder.from(application, ImportBookWorker::class.java)
                .setWorkerFactory(application.container.importWorkerFactory)
                .setInputData(
                    ImportBookWorker.inputData(
                        uri = uri,
                        displayName = displayName,
                        mimeType = "text/plain",
                        permissionPersisted = false,
                        folderId = folder.id,
                    ),
                )
                .build()
                .doWork()
        } as ListenableWorker.Result.Success
        assertEquals(
            ImportBookWorker.STATUS_SUCCESS,
            result.outputData.getString(ImportBookWorker.KEY_STATUS),
        )
        return requireNotNull(result.outputData.getString(ImportBookWorker.KEY_BOOK_ID))
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForContentDescription(description: String) {
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val UI_TIMEOUT_MILLIS = 30_000L
    }
}
