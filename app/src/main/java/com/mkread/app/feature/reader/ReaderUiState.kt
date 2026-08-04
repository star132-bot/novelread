package com.mkread.app.feature.reader

import com.mkread.app.core.database.BookEntity
import com.mkread.app.core.database.ChapterEntity

data class ReaderTextRange(
    val startInclusive: Int,
    val endExclusive: Int,
) {
    init {
        require(startInclusive >= 0) { "Selection start must be non-negative" }
        require(endExclusive > startInclusive) { "Selection must be non-empty" }
    }
}

sealed interface ReaderUiState {
    data object Loading : ReaderUiState

    sealed interface Loaded : ReaderUiState {
        val book: BookEntity
        val chapters: List<ChapterEntity>
        val chapter: ChapterEntity
        val chapterIndex: Int
        val text: String
        val sentences: List<SentenceRange>
        val pages: List<PageRange>
        val currentPage: Int
        val characterOffset: Int
        val selectedRange: ReaderTextRange?
        val activeSentenceRange: SentenceRange?
        val paginationComplete: Boolean
        val undoAvailable: Boolean
    }

    data class Paginating(
        override val book: BookEntity,
        override val chapters: List<ChapterEntity>,
        override val chapter: ChapterEntity,
        override val chapterIndex: Int,
        override val text: String,
        override val sentences: List<SentenceRange>,
        val firstPages: List<PageRange>,
        override val currentPage: Int,
        override val characterOffset: Int,
        override val selectedRange: ReaderTextRange?,
        override val activeSentenceRange: SentenceRange?,
        override val undoAvailable: Boolean,
    ) : Loaded {
        override val pages: List<PageRange> = firstPages
        override val paginationComplete: Boolean = false
    }

    data class Ready(
        override val book: BookEntity,
        override val chapters: List<ChapterEntity>,
        override val chapter: ChapterEntity,
        override val chapterIndex: Int,
        override val text: String,
        override val sentences: List<SentenceRange>,
        val pageRanges: List<PageRange>,
        override val currentPage: Int,
        override val characterOffset: Int,
        override val selectedRange: ReaderTextRange?,
        override val activeSentenceRange: SentenceRange?,
        override val paginationComplete: Boolean,
        override val undoAvailable: Boolean,
    ) : Loaded {
        override val pages: List<PageRange> = pageRanges
    }

    data class Error(
        val retryable: Boolean,
        val message: String,
    ) : ReaderUiState
}
