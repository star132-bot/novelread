package com.mkread.app.feature.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mkread.app.R
import com.mkread.app.core.files.ImportLimits

data class ChapterEditorUiState(
    val chapterId: String,
    val title: String,
    val originalText: String,
    val draftText: String,
    val undoAvailable: Boolean,
    val isSaving: Boolean = false,
    val isUndoing: Boolean = false,
    val errorMessage: String? = null,
    val saveCompletedToken: Long = 0L,
) {
    val dirty: Boolean get() = draftText != originalText
    val busy: Boolean get() = isSaving || isUndoing
}

@Composable
fun ChapterEditorRoute(
    viewModel: ReaderViewModel,
    chapterId: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editorState by viewModel.editorUiState.collectAsState()
    val readerState by viewModel.uiState.collectAsState()
    val loadedChapterId = (readerState as? ReaderUiState.Loaded)?.chapter?.id

    LaunchedEffect(chapterId, loadedChapterId, editorState?.chapterId) {
        if (editorState?.chapterId != chapterId) {
            viewModel.onAction(ReaderAction.PrepareEditor(chapterId))
        }
    }
    LaunchedEffect(editorState?.saveCompletedToken) {
        if ((editorState?.saveCompletedToken ?: 0L) > 0L) {
            viewModel.onAction(ReaderAction.CloseEditor)
            onClose()
        }
    }

    val current = editorState
    if (current == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.testTag("chapter-editor-loading"),
            )
        }
    } else {
        ChapterEditorScreen(
            state = current,
            onTextChanged = { viewModel.onAction(ReaderAction.EditDraft(it)) },
            onSave = { viewModel.onAction(ReaderAction.SaveEdit(current.draftText)) },
            onUndo = { viewModel.onAction(ReaderAction.Undo) },
            onClose = {
                viewModel.onAction(ReaderAction.CloseEditor)
                onClose()
            },
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterEditorScreen(
    state: ChapterEditorUiState,
    onTextChanged: (String) -> Unit,
    onSave: () -> Unit,
    onUndo: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    maxCharacters: Int = ImportLimits.CHAPTER_CHARACTERS,
) {
    var discardRequested by remember(state.chapterId) { mutableStateOf(false) }
    var validationError by remember(state.chapterId) { mutableStateOf<String?>(null) }
    val blankMessage = stringResource(R.string.chapter_editor_blank)
    val tooLargeMessage = stringResource(R.string.chapter_editor_too_large, maxCharacters)
    val displayedError = validationError ?: state.errorMessage

    fun requestClose() {
        if (state.dirty) discardRequested = true else onClose()
    }

    BackHandler {
        if (!state.busy) requestClose()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = ::requestClose,
                        enabled = !state.busy,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.chapter_editor_close),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onUndo,
                        enabled = state.undoAvailable && !state.dirty && !state.busy,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Undo,
                            contentDescription = stringResource(R.string.chapter_editor_undo),
                        )
                    }
                    if (state.busy) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("chapter-editor-progress"),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            validationError = when {
                                state.draftText.isBlank() -> blankMessage
                                state.draftText.length > maxCharacters -> tooLargeMessage
                                else -> null
                            }
                            if (validationError == null) onSave()
                        },
                        enabled = state.dirty && !state.busy,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Save,
                            contentDescription = stringResource(R.string.chapter_editor_save),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            if (displayedError != null) {
                Text(
                    text = displayedError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            BasicTextField(
                value = state.draftText,
                onValueChange = { text ->
                    validationError = null
                    onTextChanged(text)
                },
                enabled = !state.busy,
                textStyle = MaterialTheme.typography.bodyLarge.merge(
                    TextStyle(color = MaterialTheme.colorScheme.onBackground),
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .testTag("chapter-editor-text"),
            )
        }
    }

    if (discardRequested) {
        AlertDialog(
            onDismissRequest = { discardRequested = false },
            title = { Text(stringResource(R.string.chapter_editor_discard_title)) },
            text = { Text(stringResource(R.string.chapter_editor_discard_message)) },
            confirmButton = {
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.chapter_editor_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { discardRequested = false }) {
                    Text(stringResource(R.string.chapter_editor_continue))
                }
            },
        )
    }
}
