package com.mkread.app.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import com.mkread.app.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@Composable
fun ReaderRoute(
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    onOpenEditor: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ReaderEvent.OpenEditor -> onOpenEditor(event.chapterId)
                is ReaderEvent.ReadFromHere -> Unit
                is ReaderEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }
    ReaderScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onAction = viewModel::onAction,
        onBack = onBack,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    state: ReaderUiState,
    snackbarHostState: SnackbarHostState,
    onAction: (ReaderAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loaded = state as? ReaderUiState.Loaded
    var menuExpanded by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                modifier = Modifier.testTag("reader-top-bar"),
                title = {
                    Text(
                        text = loaded?.chapter?.title ?: stringResource(R.string.app_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.reader_back),
                        )
                    }
                },
                actions = {
                    if (loaded != null) {
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    Icons.Outlined.MoreVert,
                                    contentDescription = stringResource(R.string.reader_more),
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.reader_edit_chapter)) },
                                    onClick = {
                                        menuExpanded = false
                                        onAction(ReaderAction.OpenEditor)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (loaded != null) {
                ReaderBottomBar(
                    chapterIndex = loaded.chapterIndex,
                    chapterCount = loaded.chapters.size,
                    currentPage = loaded.currentPage,
                    pageCount = loaded.pages.size,
                    paginationComplete = loaded.paginationComplete,
                    onPreviousChapter = { onAction(ReaderAction.PreviousChapter) },
                    onPreviousPage = { onAction(ReaderAction.PreviousPage) },
                    onNextPage = { onAction(ReaderAction.NextPage) },
                    onNextChapter = { onAction(ReaderAction.NextChapter) },
                )
            }
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                ReaderUiState.Loading -> CircularProgressIndicator()
                is ReaderUiState.Error -> ReaderErrorState(state, onAction)
                is ReaderUiState.Loaded -> ReaderLoadedContent(
                    state = state,
                    snackbarHostState = snackbarHostState,
                    onAction = onAction,
                )
            }
        }
    }
}

@Composable
private fun ReaderErrorState(
    state: ReaderUiState.Error,
    onAction: (ReaderAction) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(state.message)
        if (state.retryable) {
            Button(onClick = { onAction(ReaderAction.Retry) }) {
                Text(stringResource(R.string.reader_retry))
            }
        }
    }
}

@Composable
private fun ReaderLoadedContent(
    state: ReaderUiState.Loaded,
    snackbarHostState: SnackbarHostState,
    onAction: (ReaderAction) -> Unit,
) {
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it },
        contentAlignment = Alignment.Center,
    ) {
        if (state.pages.isEmpty()) {
            Column(
                modifier = Modifier.testTag("reader-preparing"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.reader_preparing))
            }
        } else {
            ReaderPager(
                state = state,
                onAction = onAction,
                onCopied = {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = it,
                            withDismissAction = false,
                        )
                    }
                },
            )
        }
    }
    LaunchedEffect(viewportSize, state.paginationSpec) {
        if (viewportSize.width > 0 && viewportSize.height > 0) {
            val updatedSpec = state.paginationSpec.copy(
                widthPx = viewportSize.width,
                heightPx = viewportSize.height,
            )
            if (updatedSpec != state.paginationSpec) {
                onAction(ReaderAction.LayoutChanged(updatedSpec))
            }
        }
    }
}

@Composable
private fun ReaderPager(
    state: ReaderUiState.Loaded,
    onAction: (ReaderAction) -> Unit,
    onCopied: (String) -> Unit,
) {
    val copiedLabel = stringResource(R.string.reader_copied)
    val targetPage = state.currentPage.coerceIn(0, state.pages.lastIndex)
    val pagerState = rememberPagerState(
        initialPage = targetPage,
        pageCount = { state.pages.size },
    )
    LaunchedEffect(targetPage, state.pages.size) {
        if (pagerState.currentPage != targetPage) pagerState.scrollToPage(targetPage)
    }
    LaunchedEffect(pagerState, state.currentPage) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .filter { it != state.currentPage }
            .collect { page ->
                onAction(ReaderAction.GoToPage(page))
            }
    }

    HorizontalPager(
        state = pagerState,
        key = { index -> state.pages[index].index },
        beyondViewportPageCount = 1,
        modifier = Modifier
            .fillMaxSize()
            .testTag("reader-pager"),
    ) { pageIndex ->
        val range = state.pages[pageIndex]
        SelectablePageText(
            chapterText = state.text,
            pageRange = range,
            selectedRange = state.selectedRange,
            activeSentenceRange = state.activeSentenceRange,
            spec = state.paginationSpec,
            onSelectionChanged = { selection ->
                onAction(
                    selection?.let {
                        ReaderAction.SelectionChanged(it.startInclusive, it.endExclusive)
                    } ?: ReaderAction.ClearSelection,
                )
            },
            onEditChapter = { selection ->
                onAction(ReaderAction.SelectionChanged(selection.startInclusive, selection.endExclusive))
                onAction(ReaderAction.OpenEditor)
            },
            onReadFromHere = { selection ->
                onAction(ReaderAction.SelectionChanged(selection.startInclusive, selection.endExclusive))
                onAction(ReaderAction.ReadFromSelection)
            },
            onCopied = { onCopied(copiedLabel) },
            onPageTap = { zone ->
                when (zone) {
                    ReaderPageTap.Previous -> onAction(ReaderAction.PreviousPage)
                    ReaderPageTap.Center -> Unit
                    ReaderPageTap.Next -> onAction(ReaderAction.NextPage)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("reader-page-${range.index}"),
        )
    }
}
