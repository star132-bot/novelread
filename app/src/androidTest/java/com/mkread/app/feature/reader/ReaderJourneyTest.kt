package com.mkread.app.feature.reader

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.mkread.app.MainActivity
import com.mkread.app.MkreadApplication
import com.mkread.app.feature.library.ImportBookWorker
import com.mkread.app.feature.library.ImportTestContentProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderJourneyTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MkreadApplication
        get() = composeRule.activity.application as MkreadApplication

    private lateinit var chapterOneText: String
    private lateinit var chapterTwoText: String
    private lateinit var chapterThreeText: String
    private lateinit var bookId: String
    private lateinit var chapterOneId: String
    private lateinit var chapterTwoId: String
    private lateinit var chapterThreeId: String
    private lateinit var chapterTwoPath: String
    private lateinit var sourceFile: File
    private lateinit var sourceHashBefore: String

    @Before
    fun seedReaderJourney() {
        ImportTestContentProvider.clear()
        chapterOneText = buildChapter(
            label = "夜车",
            markerLine = 20,
            markerSentence = SEMANTIC_SENTENCE,
        )
        chapterTwoText = buildChapter(
            label = "清晨",
            markerLine = 14,
            markerSentence = "第二章导航锚点。Morning chapter marker.",
        )
        chapterThreeText = buildChapter(
            label = "远山",
            markerLine = 10,
            markerSentence = "第三章收束锚点。Final chapter marker.",
        )
        val sourceText = listOf(
            "第一章 夜车",
            chapterOneText,
            "第二章 清晨",
            chapterTwoText,
            "第三章 远山",
            chapterThreeText,
        ).joinToString("\n")

        val sourceBytes = sourceText.toByteArray(Charsets.UTF_8)
        sourceHashBefore = sourceBytes.sha256()
        runBlocking {
            application.container.database.bookDao()
                .getBySourceSha256(sourceHashBefore)
                ?.let { existing -> application.container.repository.removeBook(existing.id) }
        }
        val sourceUri = ImportTestContentProvider.register("reader-phase-3", sourceBytes)
        val importResult = runBlocking {
            TestListenableWorkerBuilder.from(application, ImportBookWorker::class.java)
                .setWorkerFactory(application.container.importWorkerFactory)
                .setInputData(
                    ImportBookWorker.inputData(
                        uri = sourceUri,
                        displayName = "$BOOK_TITLE.txt",
                        mimeType = "text/plain",
                        permissionPersisted = false,
                    ),
                )
                .build()
                .doWork()
        } as ListenableWorker.Result.Success
        assertEquals(
            ImportBookWorker.STATUS_SUCCESS,
            importResult.outputData.getString(ImportBookWorker.KEY_STATUS),
        )
        bookId = requireNotNull(importResult.outputData.getString(ImportBookWorker.KEY_BOOK_ID))

        val chapters = runBlocking {
            application.container.database.chapterDao().getByBookId(bookId)
        }
        assertEquals(listOf("第一章 夜车", "第二章 清晨", "第三章 远山"), chapters.map { it.title })
        chapterOneId = chapters[0].id
        chapterTwoId = chapters[1].id
        chapterThreeId = chapters[2].id
        chapterTwoPath = chapters[1].relativePath

        val bookRoot = File(application.filesDir, "books/$bookId")
        sourceFile = File(bookRoot, "source.txt")
        chapterOneText = File(bookRoot, chapters[0].relativePath).readText(Charsets.UTF_8)
        chapterTwoText = File(bookRoot, chapterTwoPath).readText(Charsets.UTF_8)
        chapterThreeText = File(bookRoot, chapters[2].relativePath).readText(Charsets.UTF_8)
        assertEquals(sourceHashBefore, sourceFile.readBytes().sha256())

        val savedOffset = chapterOneText.indexOf(SEMANTIC_MARKER)
        val savedSentence = requireNotNull(
            SentenceSegmenter().segment(chapterOneText).nearestBoundary(savedOffset),
        )
        runBlocking {
            application.container.readingPositionRepository.checkpoint(
                ReadingPosition(
                    bookId = bookId,
                    chapterId = chapterOneId,
                    characterOffset = savedOffset,
                    pageIndex = 999,
                    sentenceIndex = savedSentence.index,
                    updatedAt = 0L,
                ),
                chapterLength = chapterOneText.length,
            )
        }
    }

    @After
    fun cleanReaderJourney() {
        runCatching {
            composeRule.runOnIdle {
                composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        removeJourneyBook()
        ImportTestContentProvider.clear()
    }

    @Test
    fun multiChapterReaderJourneyRestoresTheSameSemanticSentence() {
        openBookAtSavedSemanticOffset()
        turnPageAndReturnToSavedPage()

        selectCopyAndReadFromVisibleText()
        rotateAndVerifyReaderRemainsUsable()

        navigateToChapterTwo()
        editSaveAndUndoChapterTwo()
        navigateAcrossChaptersAndBack()

        navigateToSemanticMarkerPage()
        val selectedSentence = selectCopyAndReadFromVisibleText()
        waitForPersistedPosition(selectedSentence.sentence.startInclusive)
        composeRule.onNodeWithContentDescription("返回").performClick()
        waitForText(BOOK_TITLE)
        composeRule.activityRule.scenario.recreate()

        openBookAtExpectedRestoredSentence(selectedSentence.sentence.startInclusive)
        assertEquals(sourceHashBefore, sourceFile.readBytes().sha256())

        val restoredPage = visibleReaderTextView().text.toString()
        val restoredPageStart = chapterOneText.indexOf(restoredPage)
        Log.i(
            LOG_TAG,
            "reader-journey savedOffset=${chapterOneText.indexOf(SEMANTIC_MARKER)} " +
                "selectionStart=${selectedSentence.sentence.startInclusive} " +
                "selectionEnd=${selectedSentence.sentence.endExclusive} " +
                "restoredPageStart=$restoredPageStart " +
                "restoredPageEnd=${restoredPageStart + restoredPage.length} " +
                "originalChapterTwoSha=${chapterTwoText.sha256()} " +
                "editedChapterTwoSha=${EDITED_CHAPTER_TWO.sha256()} " +
                "sourceSha=$sourceHashBefore",
        )
    }

    private fun openBookAtSavedSemanticOffset() {
        waitForText(BOOK_TITLE)
        composeRule.onNodeWithText(BOOK_TITLE).performClick()
        waitForContentDescription("阅读选项")
        waitForText("第一章 夜车")
        waitForReaderTextContaining(SEMANTIC_MARKER)

        val persisted = runBlocking {
            application.container.database.readingPositionDao().getByBookId(bookId)
        }
        assertEquals(chapterOneId, persisted?.chapterId)
        assertEquals(chapterOneText.indexOf(SEMANTIC_MARKER), persisted?.characterOffset)
    }

    private fun openBookAtExpectedRestoredSentence(expectedOffset: Int) {
        waitForText(BOOK_TITLE)
        composeRule.onNodeWithText(BOOK_TITLE).performClick()
        waitForContentDescription("阅读选项")
        waitForText("第一章 夜车")
        waitForReaderPageContainingOffset(expectedOffset)
    }

    private fun turnPageAndReturnToSavedPage() {
        val savedPageText = visibleReaderTextView().text.toString()
        composeRule.onNodeWithContentDescription("下一页").performClick()
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            visibleReaderTextOrNull()?.text?.toString()?.let { it != savedPageText } == true
        }
        val nextPageText = visibleReaderTextView().text.toString()
        assertNotEquals(savedPageText, nextPageText)

        composeRule.onNodeWithContentDescription("上一页").performClick()
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            visibleReaderTextOrNull()?.text?.toString() == savedPageText
        }
        waitForReaderTextContaining(SEMANTIC_MARKER)
    }

    private fun selectCopyAndReadFromVisibleText(): SemanticSelection {
        val view = visibleReaderTextView()
        val pageText = view.text.toString()
        val localStart = pageText.indexOf(SEMANTIC_MARKER)
        assertTrue("Saved semantic marker must be on the visible page", localStart >= 0)
        val localEnd = (localStart + SEMANTIC_MARKER.length).coerceAtMost(pageText.length)
        val copiedText = pageText.substring(localStart, localEnd)
        assertEquals(SEMANTIC_MARKER, copiedText)

        val pageStart = chapterOneText.indexOf(pageText)
        assertTrue("Visible page must map uniquely into chapter text", pageStart >= 0)
        val chapterSelectionStart = pageStart + localStart
        val sentence = requireNotNull(
            SentenceSegmenter().segment(chapterOneText).nearestBoundary(chapterSelectionStart),
        )

        composeRule.runOnIdle {
            view.setReaderSelection(localStart, localEnd)
            assertTrue(view.performReaderContextAction(android.R.id.copy))
        }
        waitForText("已复制")
        composeRule.runOnIdle {
            assertTrue(
                view.performReaderContextAction(SelectableReaderTextView.ACTION_READ_FROM_HERE),
            )
        }
        composeRule.waitForIdle()

        val expectedHighlight = ReaderTextRange(
            startInclusive = (sentence.startInclusive - pageStart).coerceAtLeast(0),
            endExclusive = (sentence.endExclusive - pageStart).coerceAtMost(pageText.length),
        )
        assertEquals(expectedHighlight, highlightRange(visibleReaderTextView()))
        return SemanticSelection(sentence = sentence)
    }

    private fun rotateAndVerifyReaderRemainsUsable() {
        composeRule.runOnIdle {
            composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        composeRule.waitUntil(timeoutMillis = ORIENTATION_TIMEOUT_MILLIS) {
            composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        waitForText("第一章 夜车")
        waitForVisibleReaderText()
        assertTrue(visibleReaderTextView().text.isNotEmpty())
        assertTrue(visibleReaderTextView().textSize > 0f)

        composeRule.runOnIdle {
            composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        composeRule.waitUntil(timeoutMillis = ORIENTATION_TIMEOUT_MILLIS) {
            composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }
        waitForText("第一章 夜车")
        waitForVisibleReaderText()
        assertTrue(visibleReaderTextView().text.isNotEmpty())
        assertTrue(visibleReaderTextView().textSize > 0f)
    }

    private fun navigateToChapterTwo() {
        composeRule.onNodeWithContentDescription("章节目录").performClick()
        composeRule.onNodeWithText("第二章 清晨").performClick()
        waitForSheetToClose()
        waitForText("第二章 清晨")
        waitForReaderTextContaining(chapterTwoText.take(FIRST_PAGE_MARKER_LENGTH))
    }

    private fun editSaveAndUndoChapterTwo() {
        openEditor()
        composeRule.onNodeWithTag("chapter-editor-text").performTextReplacement(EDITED_CHAPTER_TWO)
        composeRule.onNodeWithContentDescription("保存章节").performClick()
        waitForContentDescription("阅读选项")
        waitForReaderTextContaining("Chinese and English")

        openEditor()
        composeRule.onNodeWithContentDescription("撤销上次保存").performClick()
        waitForText(chapterTwoText)
        composeRule.onNodeWithContentDescription("关闭编辑器").performClick()
        waitForContentDescription("阅读选项")
        waitForReaderTextContaining(chapterTwoText.take(FIRST_PAGE_MARKER_LENGTH))
        assertEquals(chapterTwoText, File(application.filesDir, "books/$bookId/$chapterTwoPath").readText())
        assertEquals(sourceHashBefore, sourceFile.readBytes().sha256())
    }

    private fun navigateAcrossChaptersAndBack() {
        composeRule.onNodeWithContentDescription("下一章").performClick()
        waitForText("第三章 远山")
        waitForReaderTextContaining(chapterThreeText.take(FIRST_PAGE_MARKER_LENGTH))

        composeRule.onNodeWithContentDescription("章节目录").performClick()
        composeRule.onNodeWithText("第一章 夜车").performClick()
        waitForSheetToClose()
        waitForText("第一章 夜车")
        waitForReaderTextContaining(chapterOneText.take(FIRST_PAGE_MARKER_LENGTH))
    }

    private fun navigateToSemanticMarkerPage() {
        repeat(MAX_MARKER_PAGE_TURNS) {
            if (visibleReaderTextView().text.toString().contains(SEMANTIC_MARKER)) return
            val previous = visibleReaderTextView().text.toString()
            composeRule.onNodeWithContentDescription("下一页").performClick()
            composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
                visibleReaderTextOrNull()?.text?.toString()?.let { it != previous } == true
            }
        }
        error("Semantic marker was not found within $MAX_MARKER_PAGE_TURNS pages")
    }

    private fun waitForPersistedPosition(expectedOffset: Int) {
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            runBlocking {
                application.container.database.readingPositionDao().getByBookId(bookId)
            }?.let { position ->
                position.chapterId == chapterOneId && position.characterOffset == expectedOffset
            } == true
        }
    }

    private fun openEditor() {
        composeRule.onNodeWithContentDescription("阅读选项").performClick()
        composeRule.onNodeWithText("编辑章节").performClick()
        waitForContentDescription("保存章节")
    }

    private fun waitForReaderTextContaining(expected: String) {
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            visibleReaderTextOrNull()?.text?.toString()?.contains(expected) == true
        }
        assertTrue(visibleReaderTextView().text.toString().contains(expected))
    }

    private fun waitForReaderPageContainingOffset(expectedOffset: Int) {
        var lastPageText = ""
        var lastPageStart = -1
        try {
            composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
                lastPageText = visibleReaderTextOrNull()?.text?.toString().orEmpty()
                lastPageStart = chapterOneText.indexOf(lastPageText)
                lastPageText.isNotEmpty() &&
                    expectedOffset in lastPageStart until (lastPageStart + lastPageText.length)
            }
        } catch (failure: Throwable) {
            val persisted = runBlocking {
                application.container.database.readingPositionDao().getByBookId(bookId)
            }
            throw AssertionError(
                "Expected offset $expectedOffset; persisted=$persisted; " +
                    "visibleRange=$lastPageStart..${lastPageStart + lastPageText.length}; " +
                    "visibleLength=${lastPageText.length}",
                failure,
            )
        }
        val visiblePage = visibleReaderTextView().text.toString()
        val visibleStart = chapterOneText.indexOf(visiblePage)
        assertTrue(expectedOffset in visibleStart until (visibleStart + visiblePage.length))
    }

    private fun waitForSheetToClose() {
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag("chapter-list-sheet").fetchSemanticsNodes().isEmpty()
        }
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

    private fun waitForVisibleReaderText() {
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            visibleReaderTextOrNull()?.text?.isNotEmpty() == true
        }
    }

    private fun visibleReaderTextView(): SelectableReaderTextView =
        requireNotNull(visibleReaderTextOrNull()) { "No visible reader text view" }

    private fun visibleReaderTextOrNull(): SelectableReaderTextView? = composeRule.runOnIdle {
        composeRule.activity.window.decorView
            .descendants()
            .filterIsInstance<SelectableReaderTextView>()
            .filter(View::isShown)
            .maxByOrNull { view ->
                Rect().let { bounds ->
                    if (view.getGlobalVisibleRect(bounds)) bounds.width() * bounds.height() else 0
                }
            }
    }

    private fun highlightRange(view: SelectableReaderTextView): ReaderTextRange = composeRule.runOnIdle {
        val text = view.text as Spanned
        val spans = text.getSpans(0, text.length, BackgroundColorSpan::class.java)
        assertEquals(1, spans.size)
        ReaderTextRange(
            startInclusive = text.getSpanStart(spans.single()),
            endExclusive = text.getSpanEnd(spans.single()),
        )
    }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) {
            repeat(childCount) { index ->
                yieldAll(getChildAt(index).descendants())
            }
        }
    }

    private fun removeJourneyBook() {
        runBlocking {
            val id = when {
                ::bookId.isInitialized -> bookId
                ::sourceHashBefore.isInitialized -> application.container.database.bookDao()
                    .getBySourceSha256(sourceHashBefore)
                    ?.id
                else -> null
            }
            id?.let { application.container.repository.removeBook(it) }
        }
    }

    private fun buildChapter(
        label: String,
        markerLine: Int,
        markerSentence: String,
    ): String = List(CHAPTER_LINE_COUNT) { index ->
        if (index == markerLine) {
            markerSentence
        } else {
            "%02d %s lamp beside the window; stable pagination line %02d continues."
                .format(index, label, index)
        }
    }.joinToString("\n")

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class SemanticSelection(
        val sentence: SentenceRange,
    )

    private companion object {
        const val BOOK_TITLE = "MKread Phase 3 reader journey"
        const val SEMANTIC_MARKER = "语义锚点"
        const val SEMANTIC_SENTENCE = "语义锚点保持在这里，雨声渐强又恢复平静。Semantic restore stays here."
        const val EDITED_CHAPTER_TWO = "第二章已经编辑。Chinese and English rise together, then settle."
        const val CHAPTER_LINE_COUNT = 24
        const val FIRST_PAGE_MARKER_LENGTH = 12
        const val UI_TIMEOUT_MILLIS = 30_000L
        const val ORIENTATION_TIMEOUT_MILLIS = 20_000L
        const val MAX_MARKER_PAGE_TURNS = 40
        const val LOG_TAG = "MKreadReaderGate"
    }
}
