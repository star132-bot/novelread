package com.mkread.app.feature.reader

import android.view.View
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.mkread.app.MainActivity
import com.mkread.app.MkreadApplication
import com.mkread.app.core.database.BookEntity
import com.mkread.app.core.database.ChapterEntity
import com.mkread.app.core.model.SourceType
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue

class ChapterEditorNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MkreadApplication
        get() = composeRule.activity.application as MkreadApplication

    @Before
    fun seedBook() {
        removeBook()
        val bookRoot = File(application.filesDir, "books/$BOOK_ID").apply { mkdirs() }
        val chapterOne = "第一章正文。Night train."
        val chapterTwo = "第二章正文。Morning light."
        val sourceText = "第一章 夜车\n$chapterOne\n第二章 清晨\n$chapterTwo"
        File(bookRoot, "source.txt").writeText(sourceText, Charsets.UTF_8)
        File(bookRoot, "chapter-1.txt").writeText(chapterOne, Charsets.UTF_8)
        File(bookRoot, "chapter-2.txt").writeText(chapterTwo, Charsets.UTF_8)
        runBlocking {
            application.container.database.bookDao().insertBookWithChapters(
                BookEntity(
                    id = BOOK_ID,
                    title = BOOK_TITLE,
                    author = "MKread test",
                    sourceType = SourceType.TXT,
                    sourceSha256 = sourceText.sha256(),
                    coverRelativePath = null,
                    importedAt = System.currentTimeMillis(),
                    modifiedAt = System.currentTimeMillis(),
                    lastOpenedAt = null,
                ),
                listOf(
                    chapter("chapter-nav-1", 0, "第一章 夜车", "chapter-1.txt", chapterOne),
                    chapter("chapter-nav-2", 1, "第二章 清晨", "chapter-2.txt", chapterTwo),
                ),
            )
        }
    }

    @After
    fun cleanBook() {
        removeBook()
    }

    @Test
    fun readerEditCommandNavigatesToChapterEditor() {
        openBook()
        openEditor()

        composeRule.onNodeWithContentDescription("保存章节").assertIsDisplayed()
        composeRule.onNodeWithText("第一章正文。Night train.").assertIsDisplayed()
    }

    @Test
    fun chapterTwoEditPersistsAcrossRecreationUndoRestoresAndSourceStaysUnchanged() {
        val sourceFile = File(application.filesDir, "books/$BOOK_ID/source.txt")
        val sourceHashBefore = sourceFile.readBytes().sha256()
        openBook()
        composeRule.onNodeWithContentDescription("章节目录").performClick()
        composeRule.onNodeWithText("第二章 清晨").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithTag("chapter-list-sheet").fetchSemanticsNodes().isEmpty()
        }
        waitForText("第二章 清晨")
        assertReaderText("第二章正文。Morning light.")

        openEditor()
        composeRule.onNodeWithTag("chapter-editor-text").performTextReplacement(EDITED_CHAPTER_TWO)
        composeRule.onNodeWithContentDescription("保存章节").performClick()
        waitForContentDescription("阅读选项")
        assertReaderText(EDITED_CHAPTER_TWO)

        composeRule.activityRule.scenario.recreate()
        waitForText("第二章 清晨")
        assertReaderText(EDITED_CHAPTER_TWO)

        openEditor()
        composeRule.onNodeWithContentDescription("撤销上次保存").performClick()
        waitForText("第二章正文。Morning light.")
        composeRule.onNodeWithContentDescription("关闭编辑器").performClick()
        waitForContentDescription("阅读选项")
        assertReaderText("第二章正文。Morning light.")

        val sourceHashAfter = sourceFile.readBytes().sha256()
        org.junit.Assert.assertEquals(sourceHashBefore, sourceHashAfter)
    }

    private fun openBook() {
        waitForText(BOOK_TITLE)
        composeRule.onNodeWithText(BOOK_TITLE).performClick()
        waitForContentDescription("阅读选项")
    }

    private fun openEditor() {
        composeRule.onNodeWithContentDescription("阅读选项").performClick()
        composeRule.onNodeWithText("编辑章节").performClick()
        waitForContentDescription("保存章节")
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForContentDescription(description: String) {
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertReaderText(text: String) {
        composeRule.onNodeWithTag("reader-page-0").assertIsDisplayed()
        composeRule.runOnIdle {
            val matches = ArrayList<View>()
            composeRule.activity.window.decorView.findViewsWithText(
                matches,
                text,
                View.FIND_VIEWS_WITH_TEXT,
            )
            assertTrue(
                matches.any { view ->
                    view is SelectableReaderTextView && view.text.toString() == text && view.isShown
                },
            )
        }
    }

    private fun chapter(
        id: String,
        ordinal: Int,
        title: String,
        path: String,
        text: String,
    ) = ChapterEntity(
        id = id,
        bookId = BOOK_ID,
        ordinal = ordinal,
        title = title,
        relativePath = path,
        characterCount = text.length,
        contentSha256 = text.sha256(),
    )

    private fun removeBook() {
        runBlocking {
            application.container.database.bookDao().deleteById(BOOK_ID)
        }
        File(application.filesDir, "books/$BOOK_ID").deleteRecursively()
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val BOOK_ID = "book-editor-navigation"
        const val BOOK_TITLE = "MKread editor navigation test"
        const val EDITED_CHAPTER_TWO = "第二章已编辑。Chinese emotion rises, then settles."
    }
}
