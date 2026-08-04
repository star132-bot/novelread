package com.mkread.app.feature.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.mkread.app.core.database.BookEntity
import com.mkread.app.core.database.ChapterEntity
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

interface ReaderBookSource {
    suspend fun getBook(bookId: String): BookEntity?

    suspend fun markOpened(bookId: String): Boolean
}

class ReaderViewModel(
    savedStateHandle: SavedStateHandle,
    private val bookSource: ReaderBookSource,
    private val contentRepository: ChapterContentRepository,
    private val positionRepository: ReadingPositionRepository,
    private val paginationEngine: PaginationEngine,
    private val chapterEditor: ChapterEditor,
    initialSpec: PaginationSpec,
    private val sentenceSegmenter: SentenceSegmenter = SentenceSegmenter(),
) : ViewModel() {
    private val bookId: String = requireNotNull(savedStateHandle[BOOK_ID_KEY]) {
        "Reader requires a book id"
    }
    private val mutableUiState = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    private val eventChannel = Channel<ReaderEvent>(Channel.BUFFERED)

    private var book: BookEntity? = null
    private var chapters: List<ChapterEntity> = emptyList()
    private var chapterIndex = 0
    private var content: ChapterContent? = null
    private var sentences: List<SentenceRange> = emptyList()
    private var pageRanges: List<PageRange> = emptyList()
    private var currentPage = 0
    private var characterOffset = 0
    private var selectedRange: ReaderTextRange? = null
    private var activeSentenceRange: SentenceRange? = null
    private var paginationComplete = false
    private var undoAvailable = false
    private var paginationSpec = initialSpec
    private var loadJob: Job? = null
    private var paginationJob: Job? = null
    private var paginationRequestId = 0L

    val uiState: StateFlow<ReaderUiState> = mutableUiState.asStateFlow()
    val events: Flow<ReaderEvent> = eventChannel.receiveAsFlow()

    init {
        loadInitialState()
    }

    fun onAction(action: ReaderAction) {
        when (action) {
            ReaderAction.NextPage -> movePage(1)
            ReaderAction.PreviousPage -> movePage(-1)
            is ReaderAction.GoToPage -> goToPage(action.pageIndex)
            ReaderAction.NextChapter -> navigateChapter(chapterIndex + 1, 0)
            ReaderAction.PreviousChapter -> navigateChapter(chapterIndex - 1, Int.MAX_VALUE)
            is ReaderAction.GoToChapter -> {
                val index = chapters.indexOfFirst { it.id == action.chapterId }
                if (index >= 0) navigateChapter(index, 0)
            }
            is ReaderAction.SelectionChanged -> updateSelection(
                action.startInclusive,
                action.endExclusive,
            )
            ReaderAction.ClearSelection -> {
                selectedRange = null
                renderLoaded()
            }
            ReaderAction.ReadFromSelection -> readFromSelection()
            ReaderAction.OpenEditor -> openEditor()
            is ReaderAction.SaveEdit -> saveEdit(action.text)
            ReaderAction.Undo -> undoEdit()
            is ReaderAction.LayoutChanged -> updateLayout(action.spec)
            ReaderAction.Retry -> loadInitialState()
            ReaderAction.Checkpoint -> viewModelScope.launch { checkpointCurrentSafely() }
        }
    }

    private fun loadInitialState() {
        loadJob?.cancel()
        stopPagination()
        mutableUiState.value = ReaderUiState.Loading
        loadJob = viewModelScope.launch {
            try {
                val loadedBook = bookSource.getBook(bookId)
                    ?: return@launch showError(false, "Book was not found")
                try {
                    bookSource.markOpened(bookId)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    eventChannel.trySend(ReaderEvent.ShowMessage("Unable to update recently opened books"))
                }
                val loadedChapters = contentRepository.listChapters(bookId)
                    .sortedWith(compareBy<ChapterEntity> { it.ordinal }.thenBy { it.id })
                if (loadedChapters.isEmpty()) {
                    return@launch showError(false, "Book has no readable chapters")
                }
                val savedPosition = positionRepository.get(bookId)
                val matchedSavedChapterIndex = savedPosition
                    ?.let { saved -> loadedChapters.indexOfFirst { it.id == saved.chapterId } }
                    ?.takeIf { it >= 0 }
                val savedChapterIndex = matchedSavedChapterIndex ?: 0
                val savedOffset = if (matchedSavedChapterIndex == null) {
                    0
                } else {
                    savedPosition.characterOffset
                }
                book = loadedBook
                chapters = loadedChapters
                loadChapter(
                    targetChapterIndex = savedChapterIndex,
                    targetOffset = savedOffset,
                    persistPosition = false,
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                showError(true, failure.message ?: "Unable to open reader")
            }
        }
    }

    private suspend fun loadChapter(
        targetChapterIndex: Int,
        targetOffset: Int,
        persistPosition: Boolean,
    ) {
        val target = chapters.getOrNull(targetChapterIndex) ?: return
        val loadedContent = contentRepository.load(target.id)
        chapters = chapters.toMutableList().also { it[targetChapterIndex] = loadedContent.chapter }
        chapterIndex = targetChapterIndex
        content = loadedContent
        sentences = sentenceSegmenter.segment(loadedContent.text)
        pageRanges = emptyList()
        currentPage = 0
        characterOffset = targetOffset.coerceIn(0, loadedContent.text.length)
        selectedRange = null
        activeSentenceRange = null
        paginationComplete = false
        undoAvailable = chapterEditor.hasUndo(loadedContent.chapter.id)
        renderLoaded()
        startPagination()
        if (persistPosition) checkpointCurrentSafely()
    }

    private fun startPagination() {
        val loadedContent = content ?: return
        stopPagination()
        val requestId = paginationRequestId
        pageRanges = emptyList()
        currentPage = 0
        paginationComplete = false
        renderLoaded()
        val key = PaginationKey(
            chapterId = loadedContent.chapter.id,
            contentSha256 = loadedContent.contentSha256,
            spec = paginationSpec,
        )
        paginationJob = viewModelScope.launch {
            try {
                paginationEngine.paginate(key, loadedContent.text, paginationSpec).collect { batch ->
                    if (requestId != paginationRequestId || content?.chapter?.id != loadedContent.chapter.id) {
                        return@collect
                    }
                    pageRanges = batch.ranges
                    paginationComplete = batch.complete
                    currentPage = pageForOffset(
                        offset = characterOffset,
                        ranges = pageRanges,
                        textLength = loadedContent.text.length,
                        complete = batch.complete,
                    )
                    renderLoaded()
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (requestId == paginationRequestId) {
                    showError(true, failure.message ?: "Unable to paginate chapter")
                }
            }
        }
    }

    private fun movePage(delta: Int) {
        if (pageRanges.isEmpty()) return
        val target = currentPage + delta
        when {
            target in pageRanges.indices -> goToPage(target)
            delta > 0 && paginationComplete -> navigateChapter(chapterIndex + 1, 0)
            delta < 0 -> navigateChapter(chapterIndex - 1, Int.MAX_VALUE)
        }
    }

    private fun goToPage(pageIndex: Int) {
        val range = pageRanges.getOrNull(pageIndex) ?: return
        currentPage = pageIndex
        characterOffset = range.start
        selectedRange = null
        renderLoaded()
        viewModelScope.launch { checkpointCurrentSafely() }
    }

    private fun navigateChapter(targetIndex: Int, targetOffset: Int) {
        if (targetIndex !in chapters.indices || targetIndex == chapterIndex) return
        loadJob?.cancel()
        stopPagination()
        loadJob = viewModelScope.launch {
            try {
                checkpointCurrentSafely()
                loadChapter(targetIndex, targetOffset, persistPosition = true)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                showError(true, failure.message ?: "Unable to open chapter")
            }
        }
    }

    private fun updateSelection(start: Int, endExclusive: Int) {
        val textLength = content?.text?.length ?: return
        val normalizedStart = minOf(start, endExclusive).coerceIn(0, textLength)
        val normalizedEnd = maxOf(start, endExclusive).coerceIn(0, textLength)
        selectedRange = if (normalizedEnd > normalizedStart) {
            ReaderTextRange(normalizedStart, normalizedEnd)
        } else {
            null
        }
        renderLoaded()
    }

    private fun readFromSelection() {
        val start = selectedRange?.startInclusive ?: characterOffset
        val sentence = sentences.nearestBoundary(start) ?: return
        activeSentenceRange = sentence
        characterOffset = sentence.startInclusive
        renderLoaded()
        content?.chapter?.id?.let { chapterId ->
            eventChannel.trySend(ReaderEvent.ReadFromHere(chapterId, sentence))
        }
    }

    private fun openEditor() {
        val loadedContent = content ?: return
        eventChannel.trySend(
            ReaderEvent.OpenEditor(
                chapterId = loadedContent.chapter.id,
                text = loadedContent.text,
                undoAvailable = undoAvailable,
            ),
        )
    }

    private fun saveEdit(text: String) {
        val loadedContent = content ?: return
        loadJob?.cancel()
        stopPagination()
        loadJob = viewModelScope.launch {
            try {
                val result = chapterEditor.save(
                    chapterId = loadedContent.chapter.id,
                    newText = text,
                    currentOffset = characterOffset,
                )
                refreshCurrentChapter(result.mappedOffset)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                eventChannel.trySend(ReaderEvent.ShowMessage(failure.message ?: "Unable to save chapter"))
                renderLoaded()
            }
        }
    }

    private fun undoEdit() {
        val loadedContent = content ?: return
        loadJob?.cancel()
        stopPagination()
        loadJob = viewModelScope.launch {
            try {
                val result = chapterEditor.undo(loadedContent.chapter.id, characterOffset)
                if (result == null) {
                    undoAvailable = false
                    renderLoaded()
                } else {
                    refreshCurrentChapter(result.mappedOffset)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                eventChannel.trySend(ReaderEvent.ShowMessage(failure.message ?: "Unable to undo edit"))
                renderLoaded()
            }
        }
    }

    private suspend fun refreshCurrentChapter(mappedOffset: Int) {
        val refreshedChapters = contentRepository.listChapters(bookId)
            .sortedWith(compareBy<ChapterEntity> { it.ordinal }.thenBy { it.id })
        val currentChapterId = content?.chapter?.id ?: return
        val refreshedIndex = refreshedChapters.indexOfFirst { it.id == currentChapterId }
        if (refreshedIndex < 0) {
            showError(false, "Edited chapter was not found")
            return
        }
        chapters = refreshedChapters
        loadChapter(refreshedIndex, mappedOffset, persistPosition = true)
    }

    private fun updateLayout(spec: PaginationSpec) {
        if (spec == paginationSpec || content == null) return
        characterOffset = pageRanges.getOrNull(currentPage)?.start ?: characterOffset
        paginationSpec = spec
        startPagination()
    }

    private suspend fun checkpointCurrent() {
        val loadedContent = content ?: return
        val sentenceIndex = sentences.nearestBoundary(characterOffset)?.index ?: 0
        positionRepository.checkpoint(
            position = ReadingPosition(
                bookId = bookId,
                chapterId = loadedContent.chapter.id,
                characterOffset = characterOffset,
                pageIndex = currentPage,
                sentenceIndex = sentenceIndex,
                updatedAt = 0L,
            ),
            chapterLength = loadedContent.text.length,
        )
    }

    private suspend fun checkpointCurrentSafely() {
        try {
            checkpointCurrent()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            eventChannel.trySend(ReaderEvent.ShowMessage("Unable to save reading position"))
        }
    }

    private fun stopPagination() {
        paginationRequestId += 1
        paginationJob?.cancel()
        paginationJob = null
    }

    private fun renderLoaded() {
        val loadedBook = book ?: return
        val loadedContent = content ?: return
        mutableUiState.value = if (paginationComplete) {
            ReaderUiState.Ready(
                book = loadedBook,
                chapters = chapters,
                chapter = loadedContent.chapter,
                chapterIndex = chapterIndex,
                text = loadedContent.text,
                sentences = sentences,
                pageRanges = pageRanges,
                currentPage = currentPage,
                characterOffset = characterOffset,
                selectedRange = selectedRange,
                activeSentenceRange = activeSentenceRange,
                paginationComplete = true,
                undoAvailable = undoAvailable,
            )
        } else {
            ReaderUiState.Paginating(
                book = loadedBook,
                chapters = chapters,
                chapter = loadedContent.chapter,
                chapterIndex = chapterIndex,
                text = loadedContent.text,
                sentences = sentences,
                firstPages = pageRanges,
                currentPage = currentPage,
                characterOffset = characterOffset,
                selectedRange = selectedRange,
                activeSentenceRange = activeSentenceRange,
                undoAvailable = undoAvailable,
            )
        }
    }

    private fun showError(retryable: Boolean, message: String) {
        mutableUiState.value = ReaderUiState.Error(retryable, message)
    }

    private fun pageForOffset(
        offset: Int,
        ranges: List<PageRange>,
        textLength: Int,
        complete: Boolean,
    ): Int {
        if (ranges.isEmpty()) return 0
        if (offset >= textLength && complete) return ranges.lastIndex
        val containing = ranges.indexOfFirst { range -> offset in range.start until range.endExclusive }
        if (containing >= 0) return containing
        if (offset >= ranges.last().endExclusive) return ranges.lastIndex
        return ranges.indexOfLast { it.start <= offset }.coerceAtLeast(0)
    }

    companion object {
        const val BOOK_ID_KEY = "bookId"
    }
}

class ReaderViewModelFactory(
    private val bookId: String,
    private val bookSource: ReaderBookSource,
    private val contentRepository: ChapterContentRepository,
    private val positionRepository: ReadingPositionRepository,
    private val paginationEngine: PaginationEngine,
    private val chapterEditor: ChapterEditor,
    private val initialSpec: PaginationSpec,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        require(modelClass.isAssignableFrom(ReaderViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        val savedStateHandle = extras.createSavedStateHandle().apply {
            if (get<String>(ReaderViewModel.BOOK_ID_KEY) == null) {
                set(ReaderViewModel.BOOK_ID_KEY, bookId)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return ReaderViewModel(
            savedStateHandle = savedStateHandle,
            bookSource = bookSource,
            contentRepository = contentRepository,
            positionRepository = positionRepository,
            paginationEngine = paginationEngine,
            chapterEditor = chapterEditor,
            initialSpec = initialSpec,
        ) as T
    }
}
