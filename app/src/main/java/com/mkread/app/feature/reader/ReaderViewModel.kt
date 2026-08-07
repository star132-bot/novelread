package com.mkread.app.feature.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.mkread.app.core.database.BookEntity
import com.mkread.app.core.database.ChapterEntity
import com.mkread.app.core.files.ImportLimits
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
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
    private val mutableEditorUiState = MutableStateFlow<ChapterEditorUiState?>(null)
    private val eventChannel = Channel<ReaderEvent>(Channel.BUFFERED)
    private val exitRequested = AtomicBoolean(false)

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
    private var pendingChapterId: String? = null
    private var pendingPlaybackSentence: com.mkread.app.playback.SentenceId? = null
    private var followPlayback = true

    val uiState: StateFlow<ReaderUiState> = mutableUiState.asStateFlow()
    val editorUiState: StateFlow<ChapterEditorUiState?> = mutableEditorUiState.asStateFlow()
    val events: Flow<ReaderEvent> = eventChannel.receiveAsFlow()

    init {
        loadInitialState()
    }

    fun onAction(action: ReaderAction) {
        when (action) {
            ReaderAction.NextPage -> {
                detachFromPlayback()
                movePage(1)
            }
            ReaderAction.PreviousPage -> {
                detachFromPlayback()
                movePage(-1)
            }
            is ReaderAction.GoToPage -> {
                detachFromPlayback()
                goToPage(action.pageIndex)
            }
            ReaderAction.NextChapter -> {
                detachFromPlayback()
                navigateChapter(chapterIndex + 1, 0)
            }
            ReaderAction.PreviousChapter -> {
                detachFromPlayback()
                navigateChapter(chapterIndex - 1, Int.MAX_VALUE)
            }
            is ReaderAction.GoToChapter -> {
                detachFromPlayback()
                val index = chapters.indexOfFirst { it.id == action.chapterId }
                if (index >= 0) navigateChapter(index, 0)
            }
            is ReaderAction.SelectionChanged -> {
                detachFromPlayback()
                updateSelection(action.startInclusive, action.endExclusive)
            }
            ReaderAction.ClearSelection -> {
                detachFromPlayback()
                selectedRange = null
                renderLoaded()
            }
            ReaderAction.ReadFromSelection -> readFromSelection()
            is ReaderAction.ReadFromOffset -> readFromOffset(action.characterOffset, clearSelection = true)
            is ReaderAction.PlaybackSentenceChanged -> updatePlaybackSentence(action.sentenceId)
            ReaderAction.OpenEditor -> {
                detachFromPlayback()
                openEditor()
            }
            is ReaderAction.PrepareEditor -> prepareEditor(action.chapterId)
            is ReaderAction.EditDraft -> updateEditorDraft(action.text)
            ReaderAction.CloseEditor -> mutableEditorUiState.value = null
            is ReaderAction.SaveEdit -> saveEdit(action.text)
            ReaderAction.Undo -> undoEdit()
            is ReaderAction.LayoutChanged -> updateLayout(action.spec)
            ReaderAction.Retry -> {
                followPlayback = true
                loadInitialState()
            }
            ReaderAction.Checkpoint -> viewModelScope.launch { checkpointCurrentSafely() }
            ReaderAction.Exit -> exitReader()
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
        val playbackSentence = pendingPlaybackSentence
            ?.takeIf { it.chapterId == loadedContent.chapter.id }
            ?.let(::findPlaybackSentence)
        if (pendingPlaybackSentence?.chapterId == loadedContent.chapter.id) {
            pendingPlaybackSentence = null
        }
        pageRanges = emptyList()
        currentPage = 0
        characterOffset = (playbackSentence?.startInclusive ?: targetOffset)
            .coerceIn(0, loadedContent.text.length)
        selectedRange = null
        activeSentenceRange = playbackSentence
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

    private fun navigateChapter(
        targetIndex: Int,
        targetOffset: Int,
        retainPendingPlayback: Boolean = false,
    ) {
        if (!retainPendingPlayback) pendingPlaybackSentence = null
        if (targetIndex !in chapters.indices || targetIndex == chapterIndex) return
        val targetChapterId = chapters[targetIndex].id
        if (pendingChapterId == targetChapterId) return
        loadJob?.cancel()
        stopPagination()
        pendingChapterId = targetChapterId
        mutableUiState.value = ReaderUiState.Loading
        loadJob = viewModelScope.launch {
            try {
                checkpointCurrentSafely()
                loadChapter(targetIndex, targetOffset, persistPosition = true)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                showError(true, failure.message ?: "Unable to open chapter")
            } finally {
                if (pendingChapterId == targetChapterId) pendingChapterId = null
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
        readFromOffset(start, clearSelection = false)
    }

    private fun readFromOffset(offset: Int, clearSelection: Boolean) {
        followPlayback = true
        val textLength = content?.text?.length ?: return
        val sentence = sentences.nearestBoundary(offset.coerceIn(0, textLength)) ?: return
        if (clearSelection) selectedRange = null
        activeSentenceRange = sentence
        characterOffset = sentence.startInclusive
        renderLoaded()
        viewModelScope.launch { checkpointCurrentSafely() }
        content?.chapter?.id?.let { chapterId ->
            eventChannel.trySend(ReaderEvent.ReadFromHere(chapterId, sentence))
        }
    }

    private fun updatePlaybackSentence(sentenceId: com.mkread.app.playback.SentenceId?) {
        if (sentenceId == null) {
            pendingPlaybackSentence = null
            activeSentenceRange = null
            renderLoaded()
            return
        }
        if (!followPlayback) return
        val loadedContent = content ?: return
        if (sentenceId.bookId != bookId) return
        if (sentenceId.chapterId != loadedContent.chapter.id) {
            val targetIndex = chapters.indexOfFirst { it.id == sentenceId.chapterId }
            if (targetIndex < 0) return
            pendingPlaybackSentence = sentenceId
            navigateChapter(
                targetIndex = targetIndex,
                targetOffset = sentenceId.start,
                retainPendingPlayback = true,
            )
            return
        }
        pendingPlaybackSentence = null
        val sentence = findPlaybackSentence(sentenceId) ?: return
        activeSentenceRange = sentence
        characterOffset = sentence.startInclusive
        if (pageRanges.isNotEmpty()) {
            currentPage = pageForOffset(
                offset = characterOffset,
                ranges = pageRanges,
                textLength = loadedContent.text.length,
                complete = paginationComplete,
            )
        }
        renderLoaded()
        viewModelScope.launch { checkpointCurrentSafely() }
    }

    private fun findPlaybackSentence(
        sentenceId: com.mkread.app.playback.SentenceId,
    ): SentenceRange? = sentences.getOrNull(sentenceId.index)
        ?.takeIf {
            it.startInclusive == sentenceId.start && it.endExclusive == sentenceId.end
        }
        ?: sentences.firstOrNull {
            it.startInclusive == sentenceId.start && it.endExclusive == sentenceId.end
        }

    private fun detachFromPlayback() {
        followPlayback = false
        pendingPlaybackSentence = null
        activeSentenceRange = null
    }

    private fun openEditor() {
        if (pendingChapterId != null) return
        val loadedContent = content ?: return
        mutableEditorUiState.value = createEditorState(loadedContent)
        eventChannel.trySend(
            ReaderEvent.OpenEditor(
                chapterId = loadedContent.chapter.id,
                text = loadedContent.text,
                undoAvailable = undoAvailable,
            ),
        )
    }

    private fun prepareEditor(chapterId: String) {
        val loadedContent = content ?: return
        if (loadedContent.chapter.id == chapterId) {
            if (mutableEditorUiState.value?.chapterId != chapterId) {
                mutableEditorUiState.value = createEditorState(loadedContent)
            }
            return
        }
        val requestedIndex = chapters.indexOfFirst { it.id == chapterId }
        if (requestedIndex >= 0 && pendingChapterId != chapterId) {
            navigateChapter(requestedIndex, 0)
        }
    }

    private fun updateEditorDraft(text: String) {
        mutableEditorUiState.value = mutableEditorUiState.value?.copy(
            draftText = text,
            errorMessage = null,
        )
    }

    private fun saveEdit(text: String) {
        val loadedContent = content ?: return
        val editorState = mutableEditorUiState.value
        if (editorState != null && editorState.chapterId != loadedContent.chapter.id) {
            mutableEditorUiState.value = editorState.copy(
                draftText = text,
                isSaving = false,
                errorMessage = EDITOR_CHAPTER_CHANGED_MESSAGE,
            )
            return
        }
        val targetChapterId = editorState?.chapterId ?: loadedContent.chapter.id
        val normalizedText = text.normalizeEditorLineEndings()
        val validationMessage = when {
            normalizedText.isBlank() -> "章节内容不能为空"
            normalizedText.length > ImportLimits.CHAPTER_CHARACTERS ->
                "章节内容不能超过 ${ImportLimits.CHAPTER_CHARACTERS} 个字符"
            else -> null
        }
        if (validationMessage != null) {
            mutableEditorUiState.value = mutableEditorUiState.value?.copy(
                draftText = text,
                isSaving = false,
                errorMessage = validationMessage,
            )
            if (mutableEditorUiState.value == null) {
                eventChannel.trySend(ReaderEvent.ShowMessage(validationMessage))
            }
            return
        }
        mutableEditorUiState.value = mutableEditorUiState.value?.copy(
            draftText = text,
            isSaving = true,
            errorMessage = null,
        )
        loadJob?.cancel()
        stopPagination()
        loadJob = viewModelScope.launch {
            try {
                val result = chapterEditor.save(
                    chapterId = targetChapterId,
                    newText = normalizedText,
                    currentOffset = characterOffset,
                )
                refreshCurrentChapter(result.mappedOffset)
                val refreshed = content
                mutableEditorUiState.value = mutableEditorUiState.value
                    ?.takeIf { it.chapterId == refreshed?.chapter?.id }
                    ?.let { editor ->
                        editor.copy(
                            title = refreshed?.chapter?.title ?: editor.title,
                            originalText = refreshed?.text ?: normalizedText,
                            draftText = refreshed?.text ?: normalizedText,
                            undoAvailable = undoAvailable,
                            isSaving = false,
                            errorMessage = null,
                            saveCompletedToken = editor.saveCompletedToken + 1,
                        )
                    }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                val message = failure.toEditorMessage(saving = true)
                mutableEditorUiState.value = mutableEditorUiState.value?.copy(
                    draftText = text,
                    isSaving = false,
                    errorMessage = message,
                )
                if (mutableEditorUiState.value == null) {
                    eventChannel.trySend(ReaderEvent.ShowMessage(message))
                }
                renderLoaded()
            }
        }
    }

    private fun undoEdit() {
        val loadedContent = content ?: return
        val editorState = mutableEditorUiState.value
        if (editorState != null && editorState.chapterId != loadedContent.chapter.id) {
            mutableEditorUiState.value = editorState.copy(
                isUndoing = false,
                errorMessage = EDITOR_CHAPTER_CHANGED_MESSAGE,
            )
            return
        }
        val targetChapterId = editorState?.chapterId ?: loadedContent.chapter.id
        if (editorState?.dirty == true) {
            mutableEditorUiState.value = editorState.copy(
                errorMessage = "请先保存或放弃当前修改，再撤销上次保存",
            )
            return
        }
        mutableEditorUiState.value = editorState?.copy(
            isUndoing = true,
            errorMessage = null,
        )
        loadJob?.cancel()
        stopPagination()
        loadJob = viewModelScope.launch {
            try {
                val result = chapterEditor.undo(targetChapterId, characterOffset)
                if (result == null) {
                    undoAvailable = false
                    mutableEditorUiState.value = mutableEditorUiState.value?.copy(
                        undoAvailable = false,
                        isUndoing = false,
                    )
                    renderLoaded()
                } else {
                    refreshCurrentChapter(result.mappedOffset)
                    val refreshed = content
                    mutableEditorUiState.value = mutableEditorUiState.value
                        ?.takeIf { it.chapterId == refreshed?.chapter?.id }
                        ?.let { editor ->
                            editor.copy(
                                title = refreshed?.chapter?.title ?: editor.title,
                                originalText = refreshed?.text ?: editor.originalText,
                                draftText = refreshed?.text ?: editor.draftText,
                                undoAvailable = undoAvailable,
                                isUndoing = false,
                                errorMessage = null,
                            )
                        }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                val message = failure.toEditorMessage(saving = false)
                mutableEditorUiState.value = mutableEditorUiState.value?.copy(
                    isUndoing = false,
                    errorMessage = message,
                )
                if (mutableEditorUiState.value == null) {
                    eventChannel.trySend(ReaderEvent.ShowMessage(message))
                }
                renderLoaded()
            }
        }
    }

    private fun createEditorState(loadedContent: ChapterContent) = ChapterEditorUiState(
        chapterId = loadedContent.chapter.id,
        title = loadedContent.chapter.title,
        originalText = loadedContent.text,
        draftText = loadedContent.text,
        undoAvailable = undoAvailable,
    )

    private fun Throwable.toEditorMessage(saving: Boolean): String = when (
        (this as? ChapterEditException)?.failure
    ) {
        ChapterEditFailure.BLANK_CHAPTER -> "章节内容不能为空"
        ChapterEditFailure.TOO_LARGE -> "章节内容不能超过 ${ImportLimits.CHAPTER_CHARACTERS} 个字符"
        ChapterEditFailure.DATABASE -> "无法更新章节数据库，修改未保存，请重试"
        ChapterEditFailure.FILE_IO -> "无法写入章节文件，请检查存储空间后重试"
        ChapterEditFailure.CORRUPT_CURRENT -> "章节文件校验失败，请重新导入小说后再编辑"
        ChapterEditFailure.NOT_FOUND -> "找不到当前章节，请返回书架后重新打开"
        ChapterEditFailure.UNSAFE_PATH -> "章节存储路径无效，已阻止修改"
        ChapterEditFailure.CLEANUP_MARKER -> "章节已写入，但缓存清理失败，请重新打开小说"
        null -> if (saving) "保存章节失败，请重试" else "撤销章节修改失败，请重试"
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

    private fun exitReader() {
        if (!exitRequested.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                checkpointCurrent()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                // Leaving the reader must remain possible when a checkpoint cannot be written.
            }
            eventChannel.send(ReaderEvent.CloseReader)
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
                paginationSpec = paginationSpec,
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
                paginationSpec = paginationSpec,
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

private fun String.normalizeEditorLineEndings(): String = replace("\r\n", "\n").replace('\r', '\n')

private const val EDITOR_CHAPTER_CHANGED_MESSAGE = "当前章节已切换，请关闭编辑器后重试"

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
