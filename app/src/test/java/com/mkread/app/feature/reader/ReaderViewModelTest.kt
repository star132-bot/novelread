package com.mkread.app.feature.reader

import androidx.lifecycle.SavedStateHandle
import com.mkread.app.core.database.BookEntity
import com.mkread.app.core.database.ChapterEntity
import com.mkread.app.core.model.SourceType
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
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
    fun initialSavedPositionLoadsMatchingChapterAndMapsOffsetToPage() = runTest(dispatcher) {
        val harness = Harness(
            savedPosition = ReadingPosition(
                bookId = BOOK.id,
                chapterId = CHAPTER_2.id,
                characterOffset = 7,
                pageIndex = 99,
                sentenceIndex = 0,
                updatedAt = 1L,
            ),
        )
        val viewModel = harness.viewModel()
        runCurrent()

        val request = harness.pagination.requests.single()
        assertEquals(CHAPTER_2_TEXT, request.text)
        request.emit(
            PaginationBatch(
                ranges = listOf(
                    PageRange(0, 0, 5),
                    PageRange(1, 5, CHAPTER_2_TEXT.length),
                ),
                complete = true,
            ),
        )
        runCurrent()

        val state = viewModel.uiState.value as ReaderUiState.Ready
        assertEquals(CHAPTER_2.id, state.chapter.id)
        assertEquals(7, state.characterOffset)
        assertEquals(1, state.currentPage)
    }

    @Test
    fun missingPositionStartsAtFirstChapterAndOffsetZero() = runTest(dispatcher) {
        val harness = Harness()
        val viewModel = harness.viewModel()
        runCurrent()

        val state = viewModel.uiState.value as ReaderUiState.Paginating
        assertEquals(CHAPTER_1.id, state.chapter.id)
        assertEquals(0, state.characterOffset)
        assertEquals(CHAPTER_1_TEXT, harness.pagination.requests.single().text)
    }

    @Test
    fun positionForMissingChapterFallsBackToFirstChapterOffsetZero() = runTest(dispatcher) {
        val harness = Harness(
            savedPosition = ReadingPosition(
                bookId = BOOK.id,
                chapterId = "removed-chapter",
                characterOffset = 8,
                pageIndex = 1,
                sentenceIndex = 1,
                updatedAt = 1L,
            ),
        )
        val viewModel = harness.viewModel()
        runCurrent()

        val state = viewModel.uiState.value as ReaderUiState.Paginating
        assertEquals(CHAPTER_1.id, state.chapter.id)
        assertEquals(0, state.characterOffset)
    }

    @Test
    fun openingReaderMarksBookAsRecentlyOpened() = runTest(dispatcher) {
        val harness = Harness()

        harness.viewModel()
        runCurrent()

        assertEquals(listOf(BOOK.id), harness.bookSource.openedBookIds)
    }

    @Test
    fun firstPaginationBatchIsDisplayedBeforePaginationCompletes() = runTest(dispatcher) {
        val harness = Harness()
        val viewModel = harness.viewModel()
        runCurrent()
        val firstPage = PageRange(0, 0, 5)

        harness.pagination.requests.single().emit(
            PaginationBatch(listOf(firstPage), complete = false),
        )
        runCurrent()

        val state = viewModel.uiState.value as ReaderUiState.Paginating
        assertEquals(listOf(firstPage), state.firstPages)
        assertEquals(0, state.currentPage)
    }

    @Test
    fun pageTurnForcesSemanticPositionCheckpoint() = runTest(dispatcher) {
        val harness = Harness()
        val viewModel = harness.viewModel()
        runCurrent()
        harness.pagination.requests.single().emit(
            PaginationBatch(chapterOnePages(), complete = true),
        )
        runCurrent()

        viewModel.onAction(ReaderAction.NextPage)
        runCurrent()

        val checkpoint = harness.positions.checkpoints.single()
        assertEquals(CHAPTER_1.id, checkpoint.chapterId)
        assertEquals(5, checkpoint.characterOffset)
        assertEquals(1, checkpoint.pageIndex)
    }

    @Test
    fun exitCheckpointsBeforeRequestingReaderClose() = runTest(dispatcher) {
        val harness = Harness()
        val viewModel = harness.viewModel()
        runCurrent()
        harness.pagination.requests.single().emit(
            PaginationBatch(chapterOnePages(), complete = true),
        )
        runCurrent()
        viewModel.onAction(ReaderAction.NextPage)
        runCurrent()
        harness.positions.checkpoints.clear()
        val closeEvent = async { viewModel.events.first { it is ReaderEvent.CloseReader } }

        viewModel.onAction(ReaderAction.Exit)
        runCurrent()

        val checkpoint = harness.positions.checkpoints.single()
        assertEquals(5, checkpoint.characterOffset)
        assertTrue(closeEvent.await() is ReaderEvent.CloseReader)
    }

    @Test
    fun repeatedExitRequestsCheckpointAndCloseOnlyOnce() = runTest(dispatcher) {
        val harness = Harness()
        val viewModel = harness.viewModel()
        runCurrent()
        harness.pagination.requests.single().emit(
            PaginationBatch(chapterOnePages(), complete = true),
        )
        runCurrent()
        harness.positions.checkpoints.clear()
        val closeEvents = mutableListOf<ReaderEvent.CloseReader>()
        val collector = backgroundScope.launch {
            viewModel.events.collect { event ->
                if (event is ReaderEvent.CloseReader) closeEvents += event
            }
        }
        runCurrent()

        viewModel.onAction(ReaderAction.Exit)
        viewModel.onAction(ReaderAction.Exit)
        runCurrent()

        assertEquals(1, harness.positions.checkpoints.size)
        assertEquals(1, closeEvents.size)
        collector.cancel()
    }

    @Test
    fun exitCheckpointFailureStillClosesOnlyOnce() = runTest(dispatcher) {
        val harness = Harness()
        val viewModel = harness.viewModel()
        runCurrent()
        harness.pagination.requests.single().emit(
            PaginationBatch(chapterOnePages(), complete = true),
        )
        runCurrent()
        harness.positions.checkpoints.clear()
        harness.positions.failCheckpoints = true
        val closeEvent = async { viewModel.events.first { it is ReaderEvent.CloseReader } }

        viewModel.onAction(ReaderAction.Exit)
        viewModel.onAction(ReaderAction.Exit)
        runCurrent()

        assertEquals(1, harness.positions.checkpoints.size)
        assertTrue(closeEvent.await() is ReaderEvent.CloseReader)
    }

    @Test
    fun pageNavigationCrossesChapterBoundariesInBothDirections() = runTest(dispatcher) {
        val harness = Harness()
        val viewModel = harness.viewModel()
        runCurrent()
        harness.pagination.requests.single().emit(
            PaginationBatch(listOf(PageRange(0, 0, CHAPTER_1_TEXT.length)), complete = true),
        )
        runCurrent()

        viewModel.onAction(ReaderAction.NextPage)
        runCurrent()
        assertEquals(CHAPTER_2_TEXT, harness.pagination.requests.last().text)
        harness.pagination.requests.last().emit(
            PaginationBatch(listOf(PageRange(0, 0, CHAPTER_2_TEXT.length)), complete = true),
        )
        runCurrent()
        assertEquals(CHAPTER_2.id, (viewModel.uiState.value as ReaderUiState.Ready).chapter.id)

        viewModel.onAction(ReaderAction.PreviousPage)
        runCurrent()
        val previousRequest = harness.pagination.requests.last()
        assertEquals(CHAPTER_1_TEXT, previousRequest.text)
        previousRequest.emit(PaginationBatch(chapterOnePages(), complete = true))
        runCurrent()

        val restored = viewModel.uiState.value as ReaderUiState.Ready
        assertEquals(CHAPTER_1.id, restored.chapter.id)
        assertEquals(chapterOnePages().lastIndex, restored.currentPage)
        assertEquals(CHAPTER_1_TEXT.length, restored.characterOffset)
    }

    @Test
    fun checkpointFailureDoesNotBlockNavigationToNextChapter() = runTest(dispatcher) {
        val harness = Harness()
        val viewModel = harness.viewModel()
        runCurrent()
        harness.pagination.requests.single().emit(
            PaginationBatch(listOf(PageRange(0, 0, CHAPTER_1_TEXT.length)), complete = true),
        )
        runCurrent()
        harness.positions.failCheckpoints = true

        viewModel.onAction(ReaderAction.NextPage)
        runCurrent()

        assertEquals(CHAPTER_2_TEXT, harness.pagination.requests.last().text)
        assertTrue(viewModel.uiState.value is ReaderUiState.Paginating)
    }

    @Test
    fun layoutChangePreservesPageStartOffsetAndIgnoresLateBatch() = runTest(dispatcher) {
        val harness = Harness()
        val viewModel = harness.viewModel()
        runCurrent()
        val oldRequest = harness.pagination.requests.single()
        oldRequest.emit(PaginationBatch(chapterOnePages(), complete = true))
        runCurrent()
        viewModel.onAction(ReaderAction.NextPage)
        runCurrent()

        val newSpec = SPEC.copy(widthPx = 900)
        viewModel.onAction(ReaderAction.LayoutChanged(newSpec))
        runCurrent()
        val newRequest = harness.pagination.requests.last()
        assertEquals(newSpec, newRequest.spec)

        oldRequest.emit(
            PaginationBatch(listOf(PageRange(0, 0, CHAPTER_1_TEXT.length)), complete = true),
        )
        runCurrent()
        assertTrue(viewModel.uiState.value is ReaderUiState.Paginating)

        newRequest.emit(
            PaginationBatch(
                listOf(
                    PageRange(0, 0, 6),
                    PageRange(1, 6, CHAPTER_1_TEXT.length),
                ),
                complete = true,
            ),
        )
        runCurrent()

        val state = viewModel.uiState.value as ReaderUiState.Ready
        assertEquals(5, state.characterOffset)
        assertEquals(0, state.currentPage)
    }

    @Test
    fun layoutChangeBeforeSavedOffsetPageArrivesPreservesSavedCharacterOffset() =
        runTest(dispatcher) {
            val harness = Harness(
                savedPosition = ReadingPosition(
                    bookId = BOOK.id,
                    chapterId = CHAPTER_1.id,
                    characterOffset = 7,
                    pageIndex = 99,
                    sentenceIndex = 1,
                    updatedAt = 1L,
                ),
            )
            val viewModel = harness.viewModel()
            runCurrent()
            harness.pagination.requests.single().emit(
                PaginationBatch(listOf(PageRange(0, 0, 5)), complete = false),
            )
            runCurrent()

            viewModel.onAction(ReaderAction.LayoutChanged(SPEC.copy(heightPx = 500)))
            runCurrent()

            val state = viewModel.uiState.value as ReaderUiState.Paginating
            assertEquals(7, state.characterOffset)
        }

    @Test
    fun layoutChangeAfterSavedOffsetPageArrivesPreservesExactSavedCharacterOffset() =
        runTest(dispatcher) {
            val harness = Harness(
                savedPosition = ReadingPosition(
                    bookId = BOOK.id,
                    chapterId = CHAPTER_1.id,
                    characterOffset = 7,
                    pageIndex = 99,
                    sentenceIndex = 1,
                    updatedAt = 1L,
                ),
            )
            val viewModel = harness.viewModel()
            runCurrent()
            harness.pagination.requests.single().emit(
                PaginationBatch(
                    listOf(PageRange(0, 0, CHAPTER_1_TEXT.length)),
                    complete = true,
                ),
            )
            runCurrent()

            viewModel.onAction(ReaderAction.LayoutChanged(SPEC.copy(heightPx = 500)))
            runCurrent()
            harness.pagination.requests.last().emit(
                PaginationBatch(chapterOnePages(), complete = true),
            )
            runCurrent()

            val state = viewModel.uiState.value as ReaderUiState.Ready
            assertEquals(7, state.characterOffset)
            assertEquals(1, state.currentPage)
        }

    @Test
    fun selectionStartMapsToContainingSentenceForReadFromHere() = runTest(dispatcher) {
        val harness = Harness()
        val viewModel = harness.viewModel()
        runCurrent()
        harness.pagination.requests.single().emit(
            PaginationBatch(chapterOnePages(), complete = true),
        )
        runCurrent()

        viewModel.onAction(ReaderAction.SelectionChanged(8, 6))
        viewModel.onAction(ReaderAction.ReadFromSelection)
        runCurrent()

        val state = viewModel.uiState.value as ReaderUiState.Ready
        assertEquals(5, state.activeSentenceRange?.startInclusive)
        assertEquals(5, state.characterOffset)
        assertEquals(ReaderTextRange(6, 8), state.selectedRange)
        val checkpoint = harness.positions.checkpoints.single()
        assertEquals(CHAPTER_1.id, checkpoint.chapterId)
        assertEquals(5, checkpoint.characterOffset)
    }

    @Test
    fun tappedTextOffsetStartsNarrationFromContainingSentence() = runTest(dispatcher) {
        val harness = Harness()
        val viewModel = harness.viewModel()
        runCurrent()
        harness.pagination.requests.single().emit(
            PaginationBatch(chapterOnePages(), complete = true),
        )
        runCurrent()

        val readEvent = async { viewModel.events.first { it is ReaderEvent.ReadFromHere } }
        viewModel.onAction(ReaderAction.ReadFromOffset(8))
        runCurrent()

        val state = viewModel.uiState.value as ReaderUiState.Ready
        assertEquals(5, state.activeSentenceRange?.startInclusive)
        assertEquals(5, state.characterOffset)
        assertEquals(null, state.selectedRange)
        val event = readEvent.await() as ReaderEvent.ReadFromHere
        assertEquals(CHAPTER_1.id, event.chapterId)
        assertEquals(5, event.sentence.startInclusive)
    }

    @Test
    fun playbackSentenceMovesHighlightPageAndSemanticCheckpoint() = runTest(dispatcher) {
        val harness = Harness()
        val viewModel = harness.viewModel()
        runCurrent()
        harness.pagination.requests.single().emit(
            PaginationBatch(chapterOnePages(), complete = true),
        )
        runCurrent()

        viewModel.onAction(
            ReaderAction.PlaybackSentenceChanged(
                com.mkread.app.playback.SentenceId(
                    bookId = BOOK.id,
                    chapterId = CHAPTER_1.id,
                    index = 1,
                    start = 5,
                    end = CHAPTER_1_TEXT.length,
                ),
            ),
        )
        runCurrent()

        val state = viewModel.uiState.value as ReaderUiState.Ready
        assertEquals(SentenceRange(1, 5, CHAPTER_1_TEXT.length), state.activeSentenceRange)
        assertEquals(5, state.characterOffset)
        assertEquals(1, state.currentPage)
        assertEquals(5, harness.positions.checkpoints.last().characterOffset)
    }

    @Test
    fun playbackCrossingAChapterBoundaryLoadsHighlightsAndCheckpointsTheNewChapter() =
        runTest(dispatcher) {
            val harness = Harness()
            val viewModel = harness.viewModel()
            runCurrent()
            harness.pagination.requests.single().emit(
                PaginationBatch(chapterOnePages(), complete = true),
            )
            runCurrent()
            harness.positions.checkpoints.clear()
            val target = SentenceSegmenter().segment(CHAPTER_2_TEXT).last()

            viewModel.onAction(
                ReaderAction.PlaybackSentenceChanged(
                    com.mkread.app.playback.SentenceId(
                        bookId = BOOK.id,
                        chapterId = CHAPTER_2.id,
                        index = target.index,
                        start = target.startInclusive,
                        end = target.endExclusive,
                    ),
                ),
            )
            runCurrent()

            val chapterTwoRequest = harness.pagination.requests.last()
            assertEquals(CHAPTER_2_TEXT, chapterTwoRequest.text)
            chapterTwoRequest.emit(
                PaginationBatch(
                    listOf(
                        PageRange(0, 0, target.startInclusive),
                        PageRange(1, target.startInclusive, CHAPTER_2_TEXT.length),
                    ),
                    complete = true,
                ),
            )
            runCurrent()

            val state = viewModel.uiState.value as ReaderUiState.Ready
            assertEquals(CHAPTER_2.id, state.chapter.id)
            assertEquals(target, state.activeSentenceRange)
            assertEquals(target.startInclusive, state.characterOffset)
            assertEquals(1, state.currentPage)
            assertEquals(CHAPTER_2.id, harness.positions.checkpoints.last().chapterId)
            assertEquals(target.startInclusive, harness.positions.checkpoints.last().characterOffset)
        }

    @Test
    fun manualChapterNavigationDetachesTheVisibleReaderFromBackgroundPlayback() =
        runTest(dispatcher) {
            val harness = Harness()
            val viewModel = harness.viewModel()
            runCurrent()
            harness.pagination.requests.single().emit(
                PaginationBatch(chapterOnePages(), complete = true),
            )
            runCurrent()

            viewModel.onAction(ReaderAction.GoToChapter(CHAPTER_2.id))
            runCurrent()
            val chapterTwoRequest = harness.pagination.requests.last()
            chapterTwoRequest.emit(
                PaginationBatch(listOf(PageRange(0, 0, CHAPTER_2_TEXT.length)), complete = true),
            )
            runCurrent()
            val requestCount = harness.pagination.requests.size

            viewModel.onAction(
                ReaderAction.PlaybackSentenceChanged(
                    com.mkread.app.playback.SentenceId(
                        bookId = BOOK.id,
                        chapterId = CHAPTER_1.id,
                        index = 0,
                        start = 0,
                        end = 5,
                    ),
                ),
            )
            runCurrent()

            assertEquals(CHAPTER_2.id, (viewModel.uiState.value as ReaderUiState.Ready).chapter.id)
            assertEquals(requestCount, harness.pagination.requests.size)
        }

    @Test
    fun editSaveReloadsContentAndUsesRemappedOffset() = runTest(dispatcher) {
        val harness = Harness()
        val viewModel = harness.viewModel()
        runCurrent()
        harness.pagination.requests.single().emit(
            PaginationBatch(chapterOnePages(), complete = true),
        )
        runCurrent()
        viewModel.onAction(ReaderAction.NextPage)
        runCurrent()

        val edited = "X$CHAPTER_1_TEXT"
        viewModel.onAction(ReaderAction.SaveEdit(edited))
        runCurrent()
        val editedRequest = harness.pagination.requests.last()
        assertEquals(edited, editedRequest.text)
        editedRequest.emit(
            PaginationBatch(listOf(PageRange(0, 0, edited.length)), complete = true),
        )
        runCurrent()

        val state = viewModel.uiState.value as ReaderUiState.Ready
        assertEquals(6, state.characterOffset)
        assertEquals(edited, state.text)
        assertTrue(state.undoAvailable)
    }

    @Test
    fun openingEditorCreatesDraftStateAndTracksUnsavedChanges() = runTest(dispatcher) {
        val harness = Harness()
        val viewModel = harness.viewModel()
        runCurrent()

        viewModel.onAction(ReaderAction.OpenEditor)
        val initial = checkNotNull(viewModel.editorUiState.value)
        assertEquals(CHAPTER_1.id, initial.chapterId)
        assertEquals(CHAPTER_1_TEXT, initial.draftText)
        assertFalse(initial.dirty)

        viewModel.onAction(ReaderAction.EditDraft("中文 English"))

        val changed = checkNotNull(viewModel.editorUiState.value)
        assertEquals("中文 English", changed.draftText)
        assertTrue(changed.dirty)
    }

    @Test
    fun staleEditorCannotSaveOverChapterLoadedAfterNavigation() = runTest(dispatcher) {
        val harness = Harness()
        val viewModel = harness.viewModel()
        runCurrent()
        viewModel.onAction(ReaderAction.OpenEditor)
        viewModel.onAction(ReaderAction.EditDraft("第一章尚未保存的草稿"))

        viewModel.onAction(ReaderAction.GoToChapter(CHAPTER_2.id))
        runCurrent()
        assertEquals(CHAPTER_2.id, (viewModel.uiState.value as ReaderUiState.Paginating).chapter.id)

        viewModel.onAction(ReaderAction.SaveEdit("第一章尚未保存的草稿"))
        runCurrent()

        val editorState = checkNotNull(viewModel.editorUiState.value)
        assertEquals(CHAPTER_1.id, editorState.chapterId)
        assertEquals("第一章尚未保存的草稿", editorState.draftText)
        assertEquals("当前章节已切换，请关闭编辑器后重试", editorState.errorMessage)
        assertTrue(harness.editor.savedChapterIds.isEmpty())
        assertEquals(CHAPTER_2_TEXT, harness.content.text(CHAPTER_2.id))
    }

    @Test
    fun staleEditorCannotUndoChapterLoadedAfterNavigation() = runTest(dispatcher) {
        val harness = Harness()
        val viewModel = harness.viewModel()
        runCurrent()
        viewModel.onAction(ReaderAction.OpenEditor)

        viewModel.onAction(ReaderAction.GoToChapter(CHAPTER_2.id))
        runCurrent()
        viewModel.onAction(ReaderAction.Undo)
        runCurrent()

        val editorState = checkNotNull(viewModel.editorUiState.value)
        assertEquals(CHAPTER_1.id, editorState.chapterId)
        assertEquals("当前章节已切换，请关闭编辑器后重试", editorState.errorMessage)
        assertTrue(harness.editor.undoneChapterIds.isEmpty())
    }

    @Test
    fun chapterNavigationHidesReaderActionsAndRejectsEditorUntilContentLoads() = runTest(dispatcher) {
        val harness = Harness()
        val viewModel = harness.viewModel()
        runCurrent()

        viewModel.onAction(ReaderAction.GoToChapter(CHAPTER_2.id))
        assertEquals(ReaderUiState.Loading, viewModel.uiState.value)
        viewModel.onAction(ReaderAction.OpenEditor)

        assertNull(viewModel.editorUiState.value)
    }

    @Test
    fun prepareEditorLoadsRouteChapterWhenRestoredPositionPointsElsewhere() = runTest(dispatcher) {
        val harness = Harness()
        val viewModel = harness.viewModel()
        runCurrent()
        assertEquals(CHAPTER_1.id, (viewModel.uiState.value as ReaderUiState.Paginating).chapter.id)
        harness.positions.failCheckpoints = true

        viewModel.onAction(ReaderAction.PrepareEditor(CHAPTER_2.id))
        assertEquals(ReaderUiState.Loading, viewModel.uiState.value)
        runCurrent()
        viewModel.onAction(ReaderAction.PrepareEditor(CHAPTER_2.id))

        val editorState = checkNotNull(viewModel.editorUiState.value)
        assertEquals(CHAPTER_2.id, editorState.chapterId)
        assertEquals(CHAPTER_2_TEXT, editorState.draftText)
    }

    @Test
    fun blankEditorSaveIsRejectedBeforeTransactionStarts() = runTest(dispatcher) {
        val harness = Harness()
        val viewModel = harness.viewModel()
        runCurrent()
        viewModel.onAction(ReaderAction.OpenEditor)
        viewModel.onAction(ReaderAction.EditDraft(" \r\n "))

        viewModel.onAction(ReaderAction.SaveEdit(" \r\n "))
        runCurrent()

        val editorState = checkNotNull(viewModel.editorUiState.value)
        assertEquals("章节内容不能为空", editorState.errorMessage)
        assertEquals(" \r\n ", editorState.draftText)
        assertEquals(0, harness.editor.saveCalls)
        assertFalse(editorState.isSaving)
    }

    @Test
    fun overLimitEditorSaveIsRejectedBeforeTransactionStarts() = runTest(dispatcher) {
        val harness = Harness()
        val viewModel = harness.viewModel()
        runCurrent()
        viewModel.onAction(ReaderAction.OpenEditor)
        val tooLarge = "x".repeat(5_000_001)

        viewModel.onAction(ReaderAction.SaveEdit(tooLarge))

        val editorState = checkNotNull(viewModel.editorUiState.value)
        assertEquals("章节内容不能超过 5000000 个字符", editorState.errorMessage)
        assertEquals(tooLarge.length, editorState.draftText.length)
        assertEquals(0, harness.editor.saveCalls)
        assertFalse(editorState.isSaving)
    }

    @Test
    fun fileSaveFailureKeepsDraftAndShowsStorageRecoveryMessage() = runTest(dispatcher) {
        val harness = Harness()
        harness.editor.saveFailure = ChapterEditException(ChapterEditFailure.FILE_IO, "injected")
        val viewModel = harness.viewModel()
        runCurrent()
        viewModel.onAction(ReaderAction.OpenEditor)
        viewModel.onAction(ReaderAction.EditDraft("保留草稿"))

        viewModel.onAction(ReaderAction.SaveEdit("保留草稿"))
        assertTrue(checkNotNull(viewModel.editorUiState.value).isSaving)
        runCurrent()

        val editorState = checkNotNull(viewModel.editorUiState.value)
        assertEquals("保留草稿", editorState.draftText)
        assertEquals("无法写入章节文件，请检查存储空间后重试", editorState.errorMessage)
        assertFalse(editorState.isSaving)
    }

    @Test
    fun roomSaveFailureKeepsDraftAndShowsDatabaseRecoveryMessage() = runTest(dispatcher) {
        val harness = Harness()
        harness.editor.saveFailure = ChapterEditException(ChapterEditFailure.DATABASE, "injected")
        val viewModel = harness.viewModel()
        runCurrent()
        viewModel.onAction(ReaderAction.OpenEditor)
        viewModel.onAction(ReaderAction.EditDraft("数据库失败草稿"))

        viewModel.onAction(ReaderAction.SaveEdit("数据库失败草稿"))
        runCurrent()

        val editorState = checkNotNull(viewModel.editorUiState.value)
        assertEquals("数据库失败草稿", editorState.draftText)
        assertEquals("无法更新章节数据库，修改未保存，请重试", editorState.errorMessage)
        assertFalse(editorState.isSaving)
    }

    @Test
    fun successfulEditorSavePublishesCompletionAfterContentRefresh() = runTest(dispatcher) {
        val harness = Harness()
        val viewModel = harness.viewModel()
        runCurrent()
        val originalPaginationKey = checkNotNull(harness.pagination.requests.single().key)
        viewModel.onAction(ReaderAction.OpenEditor)
        val edited = "新增中文 and English."
        viewModel.onAction(ReaderAction.EditDraft(edited))

        viewModel.onAction(ReaderAction.SaveEdit(edited))
        runCurrent()

        val editorState = checkNotNull(viewModel.editorUiState.value)
        assertEquals(edited, editorState.originalText)
        assertEquals(edited, editorState.draftText)
        assertTrue(editorState.saveCompletedToken > 0)
        assertTrue(editorState.undoAvailable)
        assertFalse(editorState.isSaving)
        val refreshedRequest = harness.pagination.requests.last()
        assertEquals(edited, refreshedRequest.text)
        assertEquals(edited.readerTestSha256(), refreshedRequest.key?.contentSha256)
        assertTrue(originalPaginationKey != refreshedRequest.key)
    }

    @Test
    fun undoReloadsPreviousContentAndConsumesSnapshot() = runTest(dispatcher) {
        val harness = Harness()
        val viewModel = harness.viewModel()
        runCurrent()
        harness.pagination.requests.single().emit(
            PaginationBatch(chapterOnePages(), complete = true),
        )
        runCurrent()
        viewModel.onAction(ReaderAction.NextPage)
        runCurrent()

        val edited = "X$CHAPTER_1_TEXT"
        viewModel.onAction(ReaderAction.SaveEdit(edited))
        runCurrent()
        harness.pagination.requests.last().emit(
            PaginationBatch(listOf(PageRange(0, 0, edited.length)), complete = true),
        )
        runCurrent()
        viewModel.onAction(ReaderAction.Undo)
        runCurrent()
        val undoRequest = harness.pagination.requests.last()
        assertEquals(CHAPTER_1_TEXT, undoRequest.text)
        undoRequest.emit(PaginationBatch(chapterOnePages(), complete = true))
        runCurrent()

        val state = viewModel.uiState.value as ReaderUiState.Ready
        assertEquals(CHAPTER_1_TEXT, state.text)
        assertEquals(5, state.characterOffset)
        assertFalse(state.undoAvailable)
    }

    @Test
    fun reconstructedViewModelRestoresRepositoryCheckpoint() = runTest(dispatcher) {
        val harness = Harness()
        val first = harness.viewModel()
        runCurrent()
        harness.pagination.requests.single().emit(
            PaginationBatch(chapterOnePages(), complete = true),
        )
        runCurrent()
        first.onAction(ReaderAction.NextPage)
        runCurrent()

        val second = harness.viewModel()
        runCurrent()
        val reconstructionRequest = harness.pagination.requests.last()
        reconstructionRequest.emit(PaginationBatch(chapterOnePages(), complete = true))
        runCurrent()

        val state = second.uiState.value as ReaderUiState.Ready
        assertEquals(CHAPTER_1.id, state.chapter.id)
        assertEquals(5, state.characterOffset)
        assertEquals(1, state.currentPage)
    }

    private class Harness(
        savedPosition: ReadingPosition? = null,
    ) {
        val content = FakeChapterContentRepository(
            linkedMapOf(
                CHAPTER_1 to CHAPTER_1_TEXT,
                CHAPTER_2 to CHAPTER_2_TEXT,
            ),
        )
        val positions = FakeReadingPositionRepository(savedPosition)
        val pagination = ControllablePaginationEngine()
        val editor = FakeChapterEditor(content)
        val bookSource = FakeReaderBookSource()

        fun viewModel() = ReaderViewModel(
            savedStateHandle = SavedStateHandle(mapOf(ReaderViewModel.BOOK_ID_KEY to BOOK.id)),
            bookSource = bookSource,
            contentRepository = content,
            positionRepository = positions,
            paginationEngine = pagination,
            chapterEditor = editor,
            initialSpec = SPEC,
        )
    }

    private class FakeReaderBookSource : ReaderBookSource {
        val openedBookIds = mutableListOf<String>()

        override suspend fun getBook(bookId: String): BookEntity? = BOOK.takeIf { it.id == bookId }

        override suspend fun markOpened(bookId: String): Boolean {
            openedBookIds += bookId
            return true
        }
    }

    private class FakeChapterContentRepository(
        chapters: LinkedHashMap<ChapterEntity, String>,
    ) : ChapterContentRepository {
        private val contentById = chapters.mapKeys { it.key.id }.toMutableMap()
        private val chaptersById = chapters.keys.associateBy { it.id }.toMutableMap()

        override suspend fun load(chapterId: String): ChapterContent {
            val chapter = checkNotNull(chaptersById[chapterId])
            val text = checkNotNull(contentById[chapterId])
            return ChapterContent(chapter, text, text.readerTestSha256())
        }

        override suspend fun listChapters(bookId: String): List<ChapterEntity> =
            chaptersById.values.filter { it.bookId == bookId }.sortedBy { it.ordinal }

        fun text(chapterId: String): String = checkNotNull(contentById[chapterId])

        fun update(chapterId: String, newText: String) {
            val chapter = checkNotNull(chaptersById[chapterId])
            contentById[chapterId] = newText
            chaptersById[chapterId] = chapter.copy(
                characterCount = newText.length,
                contentSha256 = newText.readerTestSha256(),
            )
        }
    }

    private class FakeReadingPositionRepository(
        initial: ReadingPosition?,
    ) : ReadingPositionRepository {
        var current = initial
        val checkpoints = mutableListOf<ReadingPosition>()
        var failCheckpoints = false

        override suspend fun get(bookId: String): ReadingPosition? = current?.takeIf { it.bookId == bookId }

        override suspend fun save(
            position: ReadingPosition,
            chapterLength: Int,
            force: Boolean,
        ): Boolean {
            current = position.copy(characterOffset = position.characterOffset.coerceIn(0, chapterLength))
            return true
        }

        override suspend fun checkpoint(position: ReadingPosition, chapterLength: Int): Boolean {
            checkpoints += position
            if (failCheckpoints) error("injected checkpoint failure")
            return save(position, chapterLength, force = true)
        }

        override suspend fun clear(bookId: String) {
            if (current?.bookId == bookId) current = null
        }
    }

    private class ControllablePaginationEngine : PaginationEngine {
        val requests = mutableListOf<Request>()

        override fun paginate(text: String, spec: PaginationSpec): Flow<PaginationBatch> =
            newRequest(key = null, text = text, spec = spec).batches.receiveAsFlow()

        override fun paginate(
            key: PaginationKey,
            text: String,
            spec: PaginationSpec,
        ): Flow<PaginationBatch> = newRequest(key, text, spec).batches.receiveAsFlow()

        private fun newRequest(key: PaginationKey?, text: String, spec: PaginationSpec): Request =
            Request(key, text, spec).also(requests::add)

        data class Request(
            val key: PaginationKey?,
            val text: String,
            val spec: PaginationSpec,
            val batches: Channel<PaginationBatch> = Channel(Channel.UNLIMITED),
        ) {
            fun emit(batch: PaginationBatch) {
                check(batches.trySend(batch).isSuccess)
            }
        }
    }

    private class FakeChapterEditor(
        private val content: FakeChapterContentRepository,
    ) : ChapterEditor {
        private val snapshots = mutableMapOf<String, String>()
        var saveFailure: ChapterEditException? = null
        var saveCalls: Int = 0
        val savedChapterIds = mutableListOf<String>()
        val undoneChapterIds = mutableListOf<String>()

        override suspend fun save(
            chapterId: String,
            newText: String,
            currentOffset: Int,
        ): ChapterEditResult {
            saveCalls += 1
            savedChapterIds += chapterId
            saveFailure?.let { throw it }
            val oldText = content.text(chapterId)
            snapshots[chapterId] = oldText
            content.update(chapterId, newText)
            return editResult(chapterId, oldText, newText, currentOffset, undoAvailable = true)
        }

        override suspend fun undo(chapterId: String, currentOffset: Int): ChapterEditResult? {
            undoneChapterIds += chapterId
            val restored = snapshots.remove(chapterId) ?: return null
            val oldText = content.text(chapterId)
            content.update(chapterId, restored)
            return editResult(chapterId, oldText, restored, currentOffset, undoAvailable = false)
        }

        override suspend fun hasUndo(chapterId: String): Boolean = chapterId in snapshots

        private fun editResult(
            chapterId: String,
            oldText: String,
            newText: String,
            currentOffset: Int,
            undoAvailable: Boolean,
        ) = ChapterEditResult(
            chapterId = chapterId,
            oldContentSha256 = oldText.readerTestSha256(),
            newContentSha256 = newText.readerTestSha256(),
            characterCount = newText.length,
            mappedOffset = PositionRemapper.remap(oldText, newText, currentOffset),
            undoAvailable = undoAvailable,
            cleanupRecorded = false,
        )
    }

    private fun chapterOnePages() = listOf(
        PageRange(0, 0, 5),
        PageRange(1, 5, CHAPTER_1_TEXT.length),
    )

    private companion object {
        val BOOK = BookEntity(
            id = "book-1",
            title = "Test book",
            author = "Author",
            sourceType = SourceType.TXT,
            sourceSha256 = "source-hash",
            coverRelativePath = null,
            importedAt = 1L,
            modifiedAt = 1L,
            lastOpenedAt = null,
        )
        const val CHAPTER_1_TEXT = "One. Two."
        const val CHAPTER_2_TEXT = "Alpha. Beta."
        val CHAPTER_1 = ChapterEntity(
            id = "chapter-1",
            bookId = BOOK.id,
            ordinal = 0,
            title = "Chapter 1",
            relativePath = "chapter-1.txt",
            characterCount = CHAPTER_1_TEXT.length,
            contentSha256 = "hash-1",
        )
        val CHAPTER_2 = ChapterEntity(
            id = "chapter-2",
            bookId = BOOK.id,
            ordinal = 1,
            title = "Chapter 2",
            relativePath = "chapter-2.txt",
            characterCount = CHAPTER_2_TEXT.length,
            contentSha256 = "hash-2",
        )
        val SPEC = PaginationSpec(
            widthPx = 1080,
            heightPx = 1600,
            densityDpi = 420,
            fontFamilyId = "sans-serif",
            fontSizeSp = 18f,
            lineSpacingMultiplier = 1.4f,
            horizontalMarginPx = 48,
        )
    }
}

private fun String.readerTestSha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
