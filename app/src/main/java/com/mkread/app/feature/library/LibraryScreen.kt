package com.mkread.app.feature.library

import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mkread.app.R
import com.mkread.app.core.model.BookSummary
import com.mkread.app.core.model.LibrarySort
import com.mkread.app.core.database.ShelfFolderEntity
import com.mkread.app.speech.InstalledVoiceProvider
import com.mkread.app.speech.InstalledVoiceSummary
import com.mkread.app.speech.VoiceImportActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface LibraryDialog {
    val book: BookSummary

    data class Rename(override val book: BookSummary) : LibraryDialog
    data class Metadata(override val book: BookSummary) : LibraryDialog
    data class Remove(override val book: BookSummary) : LibraryDialog
}

private data class FolderNameDialogState(val folder: ShelfFolderEntity?)

@Composable
fun LibraryRoute(
    viewModel: LibraryViewModel,
    onOpenBook: ((String) -> Unit)?,
    onOpenSpeechDebug: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.uiState.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val selectedFolderId by viewModel.selectedFolderId.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<LibraryDialog?>(null) }
    var voiceDialogVisible by remember { mutableStateOf(false) }
    var installedVoices by remember { mutableStateOf<List<InstalledVoiceSummary>>(emptyList()) }
    var selectedVoiceId by remember { mutableStateOf(InstalledVoiceProvider.BUILT_IN_VOICE_ID) }
    var folderNameDialog by remember { mutableStateOf<FolderNameDialogState?>(null) }
    var movingBook by remember { mutableStateOf<BookSummary?>(null) }
    var deletingFolder by remember { mutableStateOf<ShelfFolderEntity?>(null) }
    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.onDocumentPicked(uri)
    }
    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            context.startActivity(
                Intent(context, VoiceImportActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setDataAndType(uri, VOICE_PACKAGE_MIME_TYPE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
    }
    val directoryScanner = remember(context) { AndroidBookDirectoryScanner(context) }
    val directoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { directoryScanner.scan(uri) }
                    .onSuccess(viewModel::importDirectory)
                    .onFailure {
                        snackbarHostState.showSnackbar(context.getString(R.string.import_folder_failed))
                    }
            }
        }
    }
    fun openVoiceLibrary() {
        scope.launch {
            val provider = InstalledVoiceProvider(context.filesDir)
            installedVoices = provider.installedVoices()
            selectedVoiceId = provider.selectedVoiceId()
            voiceDialogVisible = true
        }
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
        folders = folders,
        selectedFolderId = selectedFolderId,
        onSearchQueryChange = viewModel::onSearchQueryChanged,
        onSortSelected = viewModel::onSortSelected,
        onImport = viewModel::requestImport,
        onImportDirectory = { directoryLauncher.launch(null) },
        onCreateFolder = { folderNameDialog = FolderNameDialogState(null) },
        onRenameFolder = { folder -> folderNameDialog = FolderNameDialogState(folder) },
        onDeleteFolder = { folder -> deletingFolder = folder },
        onSelectFolder = viewModel::selectFolder,
        onImportVoice = {
            voiceLauncher.launch(arrayOf(VOICE_PACKAGE_MIME_TYPE, "application/zip", "application/octet-stream"))
        },
        onManageVoices = ::openVoiceLibrary,
        onOpenBook = onOpenBook,
        onRename = viewModel::requestRename,
        onEditMetadata = viewModel::requestEditMetadata,
        onMoveToFolder = { book -> movingBook = book },
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
    if (voiceDialogVisible) {
        VoiceLibraryDialog(
            voices = installedVoices,
            selectedVoiceId = selectedVoiceId,
            onSelect = { voiceId ->
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            InstalledVoiceProvider.select(context.filesDir, voiceId)
                        }
                    }.onSuccess {
                        selectedVoiceId = voiceId
                        voiceDialogVisible = false
                        snackbarHostState.showSnackbar(context.getString(R.string.voice_selected))
                    }.onFailure {
                        snackbarHostState.showSnackbar(context.getString(R.string.voice_selection_failed))
                    }
                }
            },
            onDismiss = { voiceDialogVisible = false },
        )
    }
    folderNameDialog?.let { prompt ->
        FolderNameDialog(
            initialName = prompt.folder?.name.orEmpty(),
            title = if (prompt.folder == null) {
                stringResource(R.string.create_folder)
            } else {
                stringResource(R.string.rename_folder)
            },
            onConfirm = { name ->
                prompt.folder?.let { viewModel.renameFolder(it.id, name) }
                    ?: viewModel.createFolder(name)
                folderNameDialog = null
            },
            onDismiss = { folderNameDialog = null },
        )
    }
    movingBook?.let { book ->
        MoveBookDialog(
            book = book,
            folders = folders,
            onMove = { folderId ->
                viewModel.moveBook(book.id, folderId)
                movingBook = null
            },
            onDismiss = { movingBook = null },
        )
    }
    deletingFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { deletingFolder = null },
            title = { Text(stringResource(R.string.delete_folder_title, folder.name)) },
            text = { Text(stringResource(R.string.delete_folder_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFolder(folder.id)
                    deletingFolder = null
                }) { Text(stringResource(R.string.delete_folder)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingFolder = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    snackbarHostState: SnackbarHostState,
    dialog: LibraryDialog?,
    folders: List<ShelfFolderEntity>,
    selectedFolderId: String?,
    onSearchQueryChange: (String) -> Unit,
    onSortSelected: (LibrarySort) -> Unit,
    onImport: () -> Unit,
    onImportDirectory: () -> Unit,
    onCreateFolder: () -> Unit,
    onRenameFolder: (ShelfFolderEntity) -> Unit,
    onDeleteFolder: (ShelfFolderEntity) -> Unit,
    onSelectFolder: (String?) -> Unit,
    onImportVoice: () -> Unit,
    onManageVoices: () -> Unit,
    onOpenBook: ((String) -> Unit)?,
    onRename: (BookSummary) -> Unit,
    onEditMetadata: (BookSummary) -> Unit,
    onMoveToFolder: (BookSummary) -> Unit,
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
                                text = { Text(stringResource(R.string.create_folder)) },
                                onClick = {
                                    debugExpanded = false
                                    onCreateFolder()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_folder)) },
                                onClick = {
                                    debugExpanded = false
                                    onImportDirectory()
                                },
                            )
                            folders.firstOrNull { it.id == selectedFolderId }?.let { selectedFolder ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rename_folder)) },
                                    onClick = {
                                        debugExpanded = false
                                        onRenameFolder(selectedFolder)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete_folder)) },
                                    onClick = {
                                        debugExpanded = false
                                        onDeleteFolder(selectedFolder)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.voice_library)) },
                                onClick = {
                                    debugExpanded = false
                                    onManageVoices()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.voice_import_action)) },
                                onClick = {
                                    debugExpanded = false
                                    onImportVoice()
                                },
                            )
                            if (onOpenSpeechDebug != null) {
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
                folders = folders,
                selectedFolderId = selectedFolderId,
                onSelectFolder = onSelectFolder,
                onOpenBook = onOpenBook,
                onRename = onRename,
                onEditMetadata = onEditMetadata,
                onMoveToFolder = onMoveToFolder,
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
private fun FolderNameDialog(
    initialName: String,
    title: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.folder_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun MoveBookDialog(
    book: BookSummary,
    folders: List<ShelfFolderEntity>,
    onMove: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_to_folder)) },
        text = {
            LazyColumn {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = book.folderId == null, onClick = { onMove(null) })
                        Text(stringResource(R.string.unfiled_books))
                    }
                }
                items(count = folders.size, key = { folders[it].id }) { index ->
                    val folder = folders[index]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = book.folderId == folder.id,
                            onClick = { onMove(folder.id) },
                        )
                        Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun VoiceLibraryDialog(
    voices: List<InstalledVoiceSummary>,
    selectedVoiceId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        InstalledVoiceSummary(
            id = InstalledVoiceProvider.BUILT_IN_VOICE_ID,
            displayName = stringResource(R.string.voice_builtin),
            languages = listOf("zh-CN", "en"),
            emotions = listOf("neutral"),
        ),
    ) + voices
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_library)) },
        text = {
            LazyColumn {
                items(count = options.size, key = { options[it].id }) { index ->
                    val voice = options[index]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = voice.id == selectedVoiceId,
                            onClick = { onSelect(voice.id) },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(voice.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                voice.emotions.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun LibraryBody(
    state: LibraryUiState,
    onImport: () -> Unit,
    folders: List<ShelfFolderEntity>,
    selectedFolderId: String?,
    onSelectFolder: (String?) -> Unit,
    onOpenBook: ((String) -> Unit)?,
    onRename: (BookSummary) -> Unit,
    onEditMetadata: (BookSummary) -> Unit,
    onMoveToFolder: (BookSummary) -> Unit,
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
        is LibraryUiState.Empty -> Column(modifier = modifier.fillMaxWidth()) {
            if (folders.isNotEmpty()) {
                FolderFilterRow(
                    folders = folders,
                    selectedFolderId = selectedFolderId,
                    onSelectFolder = onSelectFolder,
                )
            }
            EmptyLibrary(
                query = state.query,
                onImport = onImport,
                modifier = Modifier.weight(1f),
            )
        }
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
            val visibleBooks = if (selectedFolderId == null) {
                state.books
            } else {
                state.books.filter { it.folderId == selectedFolderId }
            }
            FolderFilterRow(
                folders = folders,
                selectedFolderId = selectedFolderId,
                onSelectFolder = onSelectFolder,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "我的书架 · ${visibleBooks.size}",
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
                    count = visibleBooks.size,
                    key = { index -> visibleBooks[index].id },
                ) { index ->
                    BookRow(
                        book = visibleBooks[index],
                        onOpenBook = onOpenBook,
                        onRename = onRename,
                        onEditMetadata = onEditMetadata,
                        onMoveToFolder = onMoveToFolder,
                        onRemove = onRemove,
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderFilterRow(
    folders: List<ShelfFolderEntity>,
    selectedFolderId: String?,
    onSelectFolder: (String?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = selectedFolderId == null,
                onClick = { onSelectFolder(null) },
                label = { Text(stringResource(R.string.all_books)) },
            )
        }
        items(count = folders.size, key = { folders[it].id }) { index ->
            val folder = folders[index]
            FilterChip(
                selected = selectedFolderId == folder.id,
                onClick = { onSelectFolder(folder.id) },
                label = { Text(folder.name, maxLines = 1) },
            )
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

private const val VOICE_PACKAGE_MIME_TYPE = "application/vnd.mkread.voice"
