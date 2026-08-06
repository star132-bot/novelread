package com.mkread.app.feature.reader

import com.mkread.app.core.database.ChapterEntity
import com.mkread.app.playback.SentenceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class PlannedNarrationSentence(
    val chapter: ChapterEntity,
    val text: String,
    val sentences: List<SentenceRange>,
    val range: SentenceRange,
)

class ReaderNarrationQueueSource(
    private val contentRepository: ChapterContentRepository,
    private val segmenter: SentenceSegmenter,
) {
    fun sentences(
        reader: ReaderUiState.Loaded,
        initialSentence: SentenceRange,
        resumeAfter: SentenceId?,
    ): Flow<PlannedNarrationSentence> = flow {
        val readerChapterIndex = reader.chapters.indexOfFirst { it.id == reader.chapter.id }
            .takeIf { it >= 0 }
            ?: reader.chapterIndex.takeIf { it in reader.chapters.indices }
            ?: error("The current chapter is not in the reader chapter list")
        val startChapterIndex = resumeAfter?.let { sentence ->
            require(sentence.bookId == reader.book.id) { "Playback belongs to another book" }
            reader.chapters.indexOfFirst { it.id == sentence.chapterId }
                .takeIf { it >= 0 }
                ?: error("The playback chapter is not in the reader chapter list")
        } ?: readerChapterIndex

        for (chapterIndex in startChapterIndex until reader.chapters.size) {
            val snapshot = if (chapterIndex == readerChapterIndex) {
                ChapterSnapshot(reader.chapter, reader.text, reader.sentences)
            } else {
                val loaded = contentRepository.load(reader.chapters[chapterIndex].id)
                ChapterSnapshot(
                    chapter = loaded.chapter,
                    text = loaded.text,
                    sentences = segmenter.segment(loaded.text),
                )
            }
            val firstSentenceIndex = when {
                chapterIndex != startChapterIndex -> 0
                resumeAfter != null -> snapshot.sentences.indexAfter(resumeAfter)
                else -> snapshot.sentences.indexOfFirst { it == initialSentence }
                    .takeIf { it >= 0 }
                    ?: error("The selected sentence is not in the current chapter")
            }
            snapshot.sentences.drop(firstSentenceIndex).forEach { range ->
                emit(
                    PlannedNarrationSentence(
                        chapter = snapshot.chapter,
                        text = snapshot.text,
                        sentences = snapshot.sentences,
                        range = range,
                    ),
                )
            }
        }
    }

    private fun List<SentenceRange>.indexAfter(sentence: SentenceId): Int {
        val exact = indexOfFirst { range ->
            range.index == sentence.index &&
                range.startInclusive == sentence.start &&
                range.endExclusive == sentence.end
        }
        if (exact >= 0) return exact + 1
        return indexOfFirst { it.startInclusive >= sentence.end }
            .takeIf { it >= 0 }
            ?: size
    }

    private data class ChapterSnapshot(
        val chapter: ChapterEntity,
        val text: String,
        val sentences: List<SentenceRange>,
    )
}
