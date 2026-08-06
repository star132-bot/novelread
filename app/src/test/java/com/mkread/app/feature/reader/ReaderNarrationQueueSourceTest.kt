package com.mkread.app.feature.reader

import com.mkread.app.core.database.BookEntity
import com.mkread.app.core.database.ChapterEntity
import com.mkread.app.core.model.SourceType
import com.mkread.app.playback.SentenceId
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderNarrationQueueSourceTest {
    private val segmenter = SentenceSegmenter()

    @Test
    fun startsAtSelectionAndContinuesIntoFollowingChapter() = runTest {
        val repository = FakeChapterContentRepository()
        val source = ReaderNarrationQueueSource(repository, segmenter)
        val reader = loadedReader()

        val planned = source.sentences(
            reader = reader,
            initialSentence = reader.sentences[1],
            resumeAfter = null,
        ).toList()

        assertEquals(
            listOf(
                CHAPTER_1.id to "Second.",
                CHAPTER_2.id to "Alpha.",
                CHAPTER_2.id to "Beta.",
            ),
            planned.map {
                it.chapter.id to
                    it.text.substring(it.range.startInclusive, it.range.endExclusive).trim()
            },
        )
        assertEquals(listOf(CHAPTER_2.id), repository.loadedChapterIds)
    }

    @Test
    fun resumeAfterCurrentSentenceRebuildsOnlyUnplayedTailAcrossChapters() = runTest {
        val repository = FakeChapterContentRepository()
        val source = ReaderNarrationQueueSource(repository, segmenter)
        val reader = loadedReader()
        val current = reader.sentences.first()

        val planned = source.sentences(
            reader = reader,
            initialSentence = current,
            resumeAfter = SentenceId(
                bookId = BOOK.id,
                chapterId = CHAPTER_1.id,
                index = current.index,
                start = current.startInclusive,
                end = current.endExclusive,
            ),
        ).toList()

        assertEquals(
            listOf("Second.", "Alpha.", "Beta."),
            planned.map {
                it.text.substring(it.range.startInclusive, it.range.endExclusive).trim()
            },
        )
        assertEquals(CHAPTER_2.id, planned.last().chapter.id)
    }

    @Test
    fun resumeAfterSentenceInFollowingChapterDoesNotRequeueEarlierChapters() = runTest {
        val repository = FakeChapterContentRepository()
        val source = ReaderNarrationQueueSource(repository, segmenter)
        val reader = loadedReader()
        val chapterTwoSentences = segmenter.segment(CHAPTER_2_TEXT)
        val current = chapterTwoSentences.first()

        val planned = source.sentences(
            reader = reader,
            initialSentence = reader.sentences.first(),
            resumeAfter = SentenceId(
                bookId = BOOK.id,
                chapterId = CHAPTER_2.id,
                index = current.index,
                start = current.startInclusive,
                end = current.endExclusive,
            ),
        ).toList()

        assertEquals(
            listOf(CHAPTER_2.id to "Beta."),
            planned.map {
                it.chapter.id to
                    it.text.substring(it.range.startInclusive, it.range.endExclusive).trim()
            },
        )
        assertEquals(listOf(CHAPTER_2.id), repository.loadedChapterIds)
    }

    private fun loadedReader(): ReaderUiState.Ready {
        val sentences = segmenter.segment(CHAPTER_1_TEXT)
        return ReaderUiState.Ready(
            book = BOOK,
            chapters = listOf(CHAPTER_1, CHAPTER_2),
            chapter = CHAPTER_1,
            chapterIndex = 0,
            text = CHAPTER_1_TEXT,
            sentences = sentences,
            pageRanges = listOf(PageRange(0, 0, CHAPTER_1_TEXT.length)),
            currentPage = 0,
            characterOffset = 0,
            selectedRange = null,
            activeSentenceRange = null,
            paginationComplete = true,
            undoAvailable = false,
            paginationSpec = PaginationSpec(
                widthPx = 1080,
                heightPx = 1600,
                densityDpi = 420,
                fontFamilyId = "sans-serif",
                fontSizeSp = 18f,
                lineSpacingMultiplier = 1.4f,
                horizontalMarginPx = 48,
            ),
        )
    }

    private class FakeChapterContentRepository : ChapterContentRepository {
        val loadedChapterIds = mutableListOf<String>()

        override suspend fun load(chapterId: String): ChapterContent {
            loadedChapterIds += chapterId
            check(chapterId == CHAPTER_2.id)
            return ChapterContent(CHAPTER_2, CHAPTER_2_TEXT, CHAPTER_2.contentSha256)
        }

        override suspend fun listChapters(bookId: String): List<ChapterEntity> =
            listOf(CHAPTER_1, CHAPTER_2)
    }

    private companion object {
        const val CHAPTER_1_TEXT = "First. Second."
        const val CHAPTER_2_TEXT = "Alpha. Beta."
        val BOOK = BookEntity(
            id = "book",
            title = "Book",
            author = null,
            sourceType = SourceType.TXT,
            sourceSha256 = "source-hash",
            coverRelativePath = null,
            importedAt = 1L,
            modifiedAt = 1L,
            lastOpenedAt = null,
        )
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
    }
}
