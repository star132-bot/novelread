package com.mkread.app.feature.library

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.mkread.app.core.model.BookSummary
import com.mkread.app.core.model.LibrarySort
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

interface BookImportManager {
    val updates: Flow<ImportWorkUpdate>

    fun enqueue(uri: Uri): UUID
}

sealed interface ImportWorkUpdate {
    val workId: UUID

    data object Idle : ImportWorkUpdate {
        override val workId: UUID = UUID(0L, 0L)
    }

    data class Running(override val workId: UUID) : ImportWorkUpdate

    data class Success(
        override val workId: UUID,
        val bookId: String,
    ) : ImportWorkUpdate

    data class Duplicate(
        override val workId: UUID,
        val existingBookId: String?,
    ) : ImportWorkUpdate

    data class Failure(
        override val workId: UUID,
        val message: String,
    ) : ImportWorkUpdate
}

enum class LibraryImportState {
    Idle,
    Importing,
}

sealed interface LibraryUiState {
    val query: String
    val sort: LibrarySort
    val importState: LibraryImportState

    data class Loading(
        override val query: String,
        override val sort: LibrarySort,
        override val importState: LibraryImportState,
    ) : LibraryUiState

    data class Empty(
        override val query: String,
        override val sort: LibrarySort,
        override val importState: LibraryImportState,
    ) : LibraryUiState

    data class Content(
        val books: List<BookSummary>,
        override val query: String,
        override val sort: LibrarySort,
        override val importState: LibraryImportState,
    ) : LibraryUiState

    data class Error(
        val message: String,
        override val query: String,
        override val sort: LibrarySort,
        override val importState: LibraryImportState,
    ) : LibraryUiState
}

sealed interface LibraryEvent {
    data object OpenDocumentPicker : LibraryEvent

    data class ShowSnackbar(val message: String) : LibraryEvent

    data class RenameBook(val book: BookSummary) : LibraryEvent

    data class EditMetadata(val book: BookSummary) : LibraryEvent

    data class ConfirmRemoval(val book: BookSummary) : LibraryEvent
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val repository: BookRepository,
    private val importManager: BookImportManager,
) : ViewModel() {
    private val query = MutableStateFlow(savedStateHandle[QUERY_KEY] ?: "")
    private val sort = MutableStateFlow(
        savedStateHandle.get<String>(SORT_KEY)
            ?.let { value -> runCatching { LibrarySort.valueOf(value) }.getOrNull() }
            ?: LibrarySort.LAST_OPENED,
    )
    private val importState = MutableStateFlow(LibraryImportState.Idle)
    private val _uiState = MutableStateFlow<LibraryUiState>(
        LibraryUiState.Loading(query.value, sort.value, importState.value),
    )
    private val eventChannel = Channel<LibraryEvent>(Channel.BUFFERED)
    private var loadedBooks: List<BookSummary>? = null
    private var loadError: Throwable? = null

    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()
    val events: Flow<LibraryEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            combine(
                query.debounce(SEARCH_DEBOUNCE_MILLIS),
                sort,
            ) { search, selectedSort -> LibraryQuery(search, selectedSort) }
                .distinctUntilChanged()
                .flatMapLatest { libraryQuery ->
                    loadedBooks = null
                    loadError = null
                    render()
                    repository.observeLibrary(libraryQuery)
                        .map { books -> Result.success<List<BookSummary>>(books) }
                        .catch { failure -> emit(Result.failure(failure)) }
                }
                .collect { result ->
                    result
                        .onSuccess { books ->
                            loadedBooks = books
                            loadError = null
                        }
                        .onFailure { failure ->
                            loadedBooks = null
                            loadError = failure
                        }
                    render()
                }
        }
        viewModelScope.launch {
            importManager.updates.collect { update ->
                when (update) {
                    ImportWorkUpdate.Idle -> Unit
                    is ImportWorkUpdate.Running -> importState.value = LibraryImportState.Importing
                    is ImportWorkUpdate.Success -> {
                        importState.value = LibraryImportState.Idle
                        showSnackbar("小说导入成功")
                    }
                    is ImportWorkUpdate.Duplicate -> {
                        importState.value = LibraryImportState.Idle
                        showSnackbar("这本小说已经在书架中")
                    }
                    is ImportWorkUpdate.Failure -> {
                        importState.value = LibraryImportState.Idle
                        showSnackbar(update.message)
                    }
                }
                render()
            }
        }
    }

    fun onSearchQueryChanged(value: String) {
        query.value = value
        savedStateHandle[QUERY_KEY] = value
        render()
    }

    fun onSortSelected(value: LibrarySort) {
        sort.value = value
        savedStateHandle[SORT_KEY] = value.name
        render()
    }

    fun requestImport() {
        eventChannel.trySend(LibraryEvent.OpenDocumentPicker)
    }

    fun onDocumentPicked(uri: Uri) {
        try {
            importManager.enqueue(uri)
        } catch (failure: Exception) {
            showSnackbar("无法开始导入，请重新选择文件")
        }
    }

    fun requestRename(book: BookSummary) {
        eventChannel.trySend(LibraryEvent.RenameBook(book))
    }

    fun requestEditMetadata(book: BookSummary) {
        eventChannel.trySend(LibraryEvent.EditMetadata(book))
    }

    fun confirmRename(book: BookSummary, title: String) {
        updateMetadata(book.id, title, book.author)
    }

    fun confirmMetadataEdit(bookId: String, title: String, author: String?) {
        updateMetadata(bookId, title, author)
    }

    fun requestRemove(book: BookSummary) {
        eventChannel.trySend(LibraryEvent.ConfirmRemoval(book))
    }

    fun confirmRemove(bookId: String) {
        viewModelScope.launch {
            try {
                if (repository.removeBook(bookId)) {
                    showSnackbar("已移出书架，原文件未删除")
                }
            } catch (failure: LibraryException) {
                showSnackbar(failure.userMessage())
            } catch (_: Exception) {
                showSnackbar("移出书架失败")
            }
        }
    }

    private fun updateMetadata(bookId: String, title: String, author: String?) {
        viewModelScope.launch {
            try {
                if (!repository.updateMetadata(bookId, title, author)) {
                    showSnackbar("小说不存在")
                }
            } catch (failure: LibraryException) {
                showSnackbar(failure.userMessage())
            } catch (_: Exception) {
                showSnackbar("保存书籍信息失败")
            }
        }
    }

    private fun showSnackbar(message: String) {
        eventChannel.trySend(LibraryEvent.ShowSnackbar(message))
    }

    private fun render() {
        val currentQuery = query.value
        val currentSort = sort.value
        val currentImportState = importState.value
        _uiState.value = when {
            loadError != null -> LibraryUiState.Error(
                message = loadError?.message ?: "无法加载书架",
                query = currentQuery,
                sort = currentSort,
                importState = currentImportState,
            )
            loadedBooks == null -> LibraryUiState.Loading(
                query = currentQuery,
                sort = currentSort,
                importState = currentImportState,
            )
            loadedBooks.orEmpty().isEmpty() -> LibraryUiState.Empty(
                query = currentQuery,
                sort = currentSort,
                importState = currentImportState,
            )
            else -> LibraryUiState.Content(
                books = loadedBooks.orEmpty(),
                query = currentQuery,
                sort = currentSort,
                importState = currentImportState,
            )
        }
    }

    private fun LibraryException.userMessage(): String = when (failure) {
        LibraryFailure.TITLE_REQUIRED -> "书名不能为空"
        LibraryFailure.TITLE_TOO_LONG -> "书名不能超过 200 个字符"
        LibraryFailure.AUTHOR_TOO_LONG -> "作者不能超过 200 个字符"
        else -> message ?: "保存书籍信息失败"
    }

    companion object {
        const val QUERY_KEY = "library.query"
        const val SORT_KEY = "library.sort"
        const val SEARCH_DEBOUNCE_MILLIS = 200L
    }
}

class LibraryViewModelFactory(
    private val repository: BookRepository,
    private val importManager: BookImportManager,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(
        modelClass: Class<T>,
        extras: CreationExtras,
    ): T {
        require(modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        @Suppress("UNCHECKED_CAST")
        return LibraryViewModel(
            savedStateHandle = extras.createSavedStateHandle(),
            repository = repository,
            importManager = importManager,
        ) as T
    }
}
