package com.mkread.app.feature.reader

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mkread.app.core.database.BookEntity
import com.mkread.app.core.database.ChapterEntity
import com.mkread.app.core.model.SourceType
import com.mkread.app.ui.theme.MkreadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun navigationControlsDispatchPageAndChapterCommands() {
        val actions = mutableListOf<ReaderAction>()
        launch(state = ready(chapterIndex = 1, chapterCount = 3), actions = actions)

        composeRule.onNodeWithContentDescription("下一页").performClick()
        composeRule.onNodeWithContentDescription("上一章").performClick()
        composeRule.onNodeWithContentDescription("下一章").performClick()

        assertTrue(ReaderAction.NextPage in actions)
        assertTrue(ReaderAction.PreviousChapter in actions)
        assertTrue(ReaderAction.NextChapter in actions)
    }

    @Test
    fun chapterListOpensAndDispatchesStableChapterId() {
        val actions = mutableListOf<ReaderAction>()
        launch(state = ready(chapterIndex = 0, chapterCount = 3), actions = actions)

        composeRule.onNodeWithContentDescription("章节目录").performClick()
        composeRule.onNodeWithText("Chapter 2").performClick()

        assertTrue(ReaderAction.GoToChapter("chapter-1") in actions)
    }

    @Test
    fun preparingIndicatorDisappearsAfterFirstPageBatch() {
        var state by mutableStateOf<ReaderUiState>(paginating(emptyList()))
        launchState { state }
        composeRule.onNodeWithTag("reader-preparing").assertIsDisplayed()

        composeRule.runOnIdle {
            state = paginating(PAGES)
        }

        composeRule.onNodeWithTag("reader-preparing").assertDoesNotExist()
        composeRule.onNodeWithTag("reader-page-0").assertIsDisplayed()
    }

    @Test
    fun horizontalSwipeDispatchesNewPageIndex() {
        val actions = mutableListOf<ReaderAction>()
        launch(state = ready(), actions = actions)

        composeRule.onNodeWithTag("reader-pager").performTouchInput { swipeLeft() }

        composeRule.waitUntil(timeoutMillis = 3_000) {
            actions.any { it == ReaderAction.GoToPage(1) }
        }
    }

    @Test
    fun newlyAvailableRequestedPageBecomesVisible() {
        val tenPages = TEN_PAGE_TEXT.indices.map { index -> PageRange(index, index, index + 1) }
        val actions = mutableListOf<ReaderAction>()
        var state by mutableStateOf<ReaderUiState>(
            paginating(tenPages.take(1), text = TEN_PAGE_TEXT, currentPage = 0),
        )
        launchState(actions) { state }

        composeRule.runOnIdle {
            state = paginating(tenPages, text = TEN_PAGE_TEXT, currentPage = 9)
        }

        composeRule.onNodeWithTag("reader-page-9").assertIsDisplayed()
        assertTrue(actions.none { it == ReaderAction.GoToPage(0) })
    }

    @Test
    fun pageCounterKeepsStableDimensionsForLargestIndex() {
        val tenPages = TEN_PAGE_TEXT.indices.map { index -> PageRange(index, index, index + 1) }
        var state by mutableStateOf<ReaderUiState>(
            ready(tenPages, TEN_PAGE_TEXT, currentPage = 0),
        )
        launchState { state }
        val before = composeRule.onNodeWithTag("reader-page-count").getUnclippedBoundsInRoot()

        composeRule.runOnIdle {
            state = ready(tenPages, TEN_PAGE_TEXT, currentPage = 9)
        }
        val after = composeRule.onNodeWithTag("reader-page-count").getUnclippedBoundsInRoot()

        assertEquals(before.right - before.left, after.right - after.left)
        assertEquals(before.bottom - before.top, after.bottom - after.top)
    }

    @Test
    fun twoHundredPercentFontScaleDoesNotOverlapReaderBands() {
        val actions = mutableListOf<ReaderAction>()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                MkreadTheme {
                    ReaderScreen(
                        state = ready(),
                        snackbarHostState = SnackbarHostState(),
                        onAction = { action -> actions += action },
                        onBack = {},
                    )
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 3_000) {
            actions.any { action ->
                action is ReaderAction.LayoutChanged && action.spec.fontScale == 2f
            }
        }
        val top = composeRule.onNodeWithTag("reader-top-bar").getUnclippedBoundsInRoot()
        val content = composeRule.onNodeWithTag("reader-pager").getUnclippedBoundsInRoot()
        val bottom = composeRule.onNodeWithTag("reader-bottom-bar").getUnclippedBoundsInRoot()

        assertTrue(top.bottom <= content.top)
        assertTrue(content.bottom <= bottom.top)
    }

    private fun launch(
        state: ReaderUiState,
        actions: MutableList<ReaderAction> = mutableListOf(),
    ) = launchState(actions) { state }

    private fun launchState(
        actions: MutableList<ReaderAction> = mutableListOf(),
        state: () -> ReaderUiState,
    ) {
        composeRule.setContent {
            MkreadTheme {
                ReaderScreen(
                    state = state(),
                    snackbarHostState = SnackbarHostState(),
                    onAction = { action -> actions += action },
                    onBack = {},
                )
            }
        }
    }

    private fun ready(
        pages: List<PageRange> = PAGES,
        text: String = TEXT,
        currentPage: Int = 0,
        chapterIndex: Int = 0,
        chapterCount: Int = 1,
    ): ReaderUiState.Ready {
        val chapters = testChapters(chapterCount, text)
        return ReaderUiState.Ready(
        book = BOOK,
        chapters = chapters,
        chapter = chapters[chapterIndex],
        chapterIndex = chapterIndex,
        text = text,
        sentences = emptyList(),
        pageRanges = pages,
        currentPage = currentPage,
        characterOffset = pages.getOrNull(currentPage)?.start ?: 0,
        selectedRange = null,
        activeSentenceRange = null,
        paginationComplete = true,
        undoAvailable = false,
        paginationSpec = SPEC,
        )
    }

    private fun paginating(
        pages: List<PageRange>,
        text: String = TEXT,
        currentPage: Int = 0,
    ): ReaderUiState.Paginating {
        val chapters = testChapters(1, text)
        return ReaderUiState.Paginating(
        book = BOOK,
        chapters = chapters,
        chapter = chapters.single(),
        chapterIndex = 0,
        text = text,
        sentences = emptyList(),
        firstPages = pages,
        currentPage = currentPage,
        characterOffset = pages.getOrNull(currentPage)?.start ?: 0,
        selectedRange = null,
        activeSentenceRange = null,
        undoAvailable = false,
        paginationSpec = SPEC,
        )
    }

    private fun testChapters(count: Int, text: String): List<ChapterEntity> =
        List(count) { index ->
            CHAPTER.copy(
                id = "chapter-$index",
                ordinal = index,
                title = "Chapter ${index + 1}",
                characterCount = text.length,
            )
        }

    private companion object {
        const val TEXT = "AAAAABBBBB"
        const val TEN_PAGE_TEXT = "abcdefghij"
        val PAGES = listOf(PageRange(0, 0, 5), PageRange(1, 5, 10))
        val SPEC = PaginationSpec(
            widthPx = 720,
            heightPx = 900,
            densityDpi = 320,
            fontFamilyId = "sans-serif",
            fontSizeSp = 18f,
            lineSpacingMultiplier = 1.3f,
            horizontalMarginPx = 32,
        )
        val BOOK = BookEntity(
            id = "book-1",
            title = "Reader test",
            author = null,
            sourceType = SourceType.TXT,
            sourceSha256 = "source",
            coverRelativePath = null,
            importedAt = 1L,
            modifiedAt = 1L,
            lastOpenedAt = null,
        )
        val CHAPTER = ChapterEntity(
            id = "chapter-1",
            bookId = BOOK.id,
            ordinal = 0,
            title = "Chapter 1",
            relativePath = "chapter.txt",
            characterCount = TEXT.length,
            contentSha256 = "hash",
        )
    }
}
