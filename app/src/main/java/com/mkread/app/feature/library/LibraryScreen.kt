package com.mkread.app.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mkread.app.R
import com.mkread.app.core.model.BookSummary
import com.mkread.app.core.model.LibrarySort

sealed interface LibraryDialog {
    val book: BookSummary

    data class Rename(override val book: BookSummary) : LibraryDialog
    data class Metadata(override val book: BookSummary) : LibraryDialog
    data class Remove(override val book: BookSummary) : LibraryDialog
}

@Composable
fun LibraryRoute(
    viewModel: LibraryViewModel,
    onOpenBook: ((String) -> Unit)?,
    onOpenSpeechDebug: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<LibraryDialog?>(null) }
    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.onDocumentPicked(uri)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                LibraryEvent.OpenDocumentPicker -> documentLauncher.launch(
                    BookImportScheduler.SUPPORTED_MIME_TYPES.toTypedArray(),
                )
                is LibraryEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                is LibraryEvent.RenameBook -> dialog = LibraryDialog.Rename(event.book)
                is LibraryEvent.EditMetadata -> dialog = LibraryDialog.Metadata(event.book)
                is LibraryEvent.ConfirmRemoval -> dialog = LibraryDialog.Remove(event.book)
            }
        }
    }

    LibraryScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        dialog = dialog,
        onSearchQueryChange = viewModel::onSearchQueryChanged,
        onSortSelected = viewModel::onSortSelected,
        onImport = viewModel::requestImport,
        onOpenBook = onOpenBook,
        onRename = viewModel::requestRename,
        onEditMetadata = viewModel::requestEditMetadata,
        onRemove = viewModel::requestRemove,
        onConfirmRename = { book, title ->
            dialog = null
            viewModel.confirmRename(book, title)
        },
        onConfirmMetadata = { book, title, author ->
            dialog = null
            viewModel.confirmMetadataEdit(book.id, title, author)
        },
        onConfirmRemove = { book ->
            dialog = null
            viewModel.confirmRemove(book.id)
        },
        onDismissDialog = { dialog = null },
        onOpenSpeechDebug = onOpenSpeechDebug,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    snackbarHostState: SnackbarHostState,
    dialog: LibraryDialog?,
    onSearchQueryChange: (String) -> Unit,
    onSortSelected: (LibrarySort) -> Unit,
    onImport: () -> Unit,
    onOpenBook: ((String) -> Unit)?,
    onRename: (BookSummary) -> Unit,
    onEditMetadata: (BookSummary) -> Unit,
    onRemove: (BookSummary) -> Unit,
    onConfirmRename: (BookSummary, String) -> Unit,
    onConfirmMetadata: (BookSummary, String, String?) -> Unit,
    onConfirmRemove: (BookSummary) -> Unit,
    onDismissDialog: () -> Unit,
    onOpenSpeechDebug: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var searchVisible by rememberSaveable { mutableStateOf(state.query.isNotEmpty()) }
    var sortExpanded by remember { mutableStateOf(false) }
    var debugExpanded by remember { mutableStateOf(false) }
    val searchLabel = stringResource(R.string.search)
    val closeSearchLabel = stringResource(R.string.close_search)
    val sortLabel = stringResource(R.string.sort)
    val importLabel = stringResource(R.string.import_book)
    val moreLabel = stringResource(R.string.more_options)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (searchVisible) {
                        TextField(
                            value = state.query,
                            onValueChange = onSearchQueryChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("library-search"),
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            singleLine = true,
                        )
                    } else {
                        Text("MKread")
                    }
                },
                actions = {
                    LibraryTooltipIconButton(
                        label = if (searchVisible) closeSearchLabel else searchLabel,
                        onClick = {
                            if (searchVisible) onSearchQueryChange("")
                            searchVisible = !searchVisible
                        },
                    ) {
                        Icon(
                            imageVector = if (searchVisible) Icons.Outlined.Close else Icons.Outlined.Search,
                            contentDescription = null,
                        )
                    }
                    Box {
                        LibraryTooltipIconButton(
                            label = sortLabel,
                            onClick = { sortExpanded = true },
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = sortExpanded,
                            onDismissRequest = { sortExpanded = false },
                        ) {
                            SortMenuItem(
                                label = stringResource(R.string.sort_last_opened),
                                selected = state.sort == LibrarySort.LAST_OPENED,
                                onClick = {
                                    sortExpanded = false
                                    onSortSelected(LibrarySort.LAST_OPENED)
                                },
                            )
                            SortMenuItem(
                                label = stringResource(R.string.sort_title),
                                selected = state.sort == LibrarySort.TITLE,
                                onClick = {
                                    sortExpanded = false
                                    onSortSelected(LibrarySort.TITLE)
                                },
                            )
                            SortMenuItem(
                                label = stringResource(R.string.sort_imported),
                                selected = state.sort == LibrarySort.IMPORTED,
                                onClick = {
                                    sortExpanded = false
                                    onSortSelected(LibrarySort.IMPORTED)
                                },
                            )
                        }
                    }
                    if (onOpenSpeechDebug != null) {
                        Box {
                            LibraryTooltipIconButton(
                                label = moreLabel,
                                onClick = { debugExpanded = true },
                            ) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = debugExpanded,
                                onDismissRequest = { debugExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.debug_speech)) },
                                    onClick = {
                                        debugExpanded = false
                                        onOpenSpeechDebug()
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (state !is LibraryUiState.Empty || state.query.isNotBlank()) {
                FloatingActionButton(
                    onClick = onImport,
                    modifier = Modifier.semantics { contentDescription = importLabel },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                }
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            if (state.importState == LibraryImportState.Importing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "正在导入小说" },
                )
            }
            LibraryBody(
                state = state,
                onImport = onImport,
                onOpenBook = onOpenBook,
                onRename = onRename,
                onEditMetadata = onEditMetadata,
                onRemove = onRemove,
                modifier = Modifier.weight(1f),
            )
        }
    }

    when (dialog) {
        null -> Unit
        is LibraryDialog.Rename -> RenameDialog(
            dialog = dialog,
            onConfirm = onConfirmRename,
            onDismiss = onDismissDialog,
        )
        is LibraryDialog.Metadata -> MetadataDialog(
            dialog = dialog,
            onConfirm = onConfirmMetadata,
            onDismiss = onDismissDialog,
        )
        is LibraryDialog.Remove -> RemoveDialog(
            dialog = dialog,
            onConfirm = onConfirmRemove,
            onDismiss = onDismissDialog,
        )
    }
}

@Composable
private fun LibraryBody(
    state: LibraryUiState,
    onImport: () -> Unit,
    onOpenBook: ((String) -> Unit)?,
    onRename: (BookSummary) -> Unit,
    onEditMetadata: (BookSummary) -> Unit,
    onRemove: (BookSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is LibraryUiState.Loading -> Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        is LibraryUiState.Empty -> EmptyLibrary(
            query = state.query,
            onImport = onImport,
            modifier = modifier,
        )
        is LibraryUiState.Error -> Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = state.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        is LibraryUiState.Content -> Column(modifier = modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "我的书架 · ${state.books.size}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = sortName(state.sort),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(
                    count = state.books.size,
                    key = { index -> state.books[index].id },
                ) { index ->
                    BookRow(
                        book = state.books[index],
                        onOpenBook = onOpenBook,
                        onRename = onRename,
                        onEditMetadata = onEditMetadata,
                        onRemove = onRemove,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(
    query: String,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (query.isBlank()) {
                stringResource(R.string.library_empty)
            } else {
                stringResource(R.string.library_no_results)
            },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (query.isBlank()) {
            Button(
                onClick = onImport,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(stringResource(R.string.import_book))
            }
        }
    }
}

@Composable
private fun SortMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        },
        onClick = onClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryTooltipIconButton(
    label: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.semantics { contentDescription = label },
        ) {
            content()
        }
    }
}

@Composable
private fun RenameDialog(
    dialog: LibraryDialog.Rename,
    onConfirm: (BookSummary, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(dialog.book.id) { mutableStateOf(dialog.book.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            TextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rename-title"),
                label = { Text(stringResource(R.string.book_title)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dialog.book, title) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun MetadataDialog(
    dialog: LibraryDialog.Metadata,
    onConfirm: (BookSummary, String, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(dialog.book.id) { mutableStateOf(dialog.book.title) }
    var author by remember(dialog.book.id) { mutableStateOf(dialog.book.author.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("metadata-title"),
                    label = { Text(stringResource(R.string.book_title)) },
                    singleLine = true,
                )
                TextField(
                    value = author,
                    onValueChange = { author = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("metadata-author"),
                    label = { Text(stringResource(R.string.book_author)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dialog.book, title, author) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun RemoveDialog(
    dialog: LibraryDialog.Remove,
    onConfirm: (BookSummary) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移出《${dialog.book.title}》？") },
        text = { Text(stringResource(R.string.source_file_retained)) },
        confirmButton = {
            TextButton(onClick = { onConfirm(dialog.book) }) {
                Text(stringResource(R.string.confirm_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun sortName(sort: LibrarySort): String = when (sort) {
    LibrarySort.LAST_OPENED -> "最近阅读"
    LibrarySort.TITLE -> "按标题"
    LibrarySort.IMPORTED -> "最近导入"
}
