package com.mkread.app.feature.reader

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import com.mkread.app.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@Composable
fun ReaderRoute(
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    onOpenEditor: (String) -> Unit,
    narrationController: ReaderNarrationController? = null,
    nightModeEnabled: Boolean = false,
    onToggleNightMode: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val playbackState = narrationController?.state?.collectAsState()?.value
        ?: ReaderPlaybackUiState()
    val latestState by rememberUpdatedState(state)
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.onAction(ReaderAction.Checkpoint)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler { viewModel.onAction(ReaderAction.Exit) }
    LaunchedEffect(viewModel, narrationController) {
        viewModel.events.collect { event ->
            when (event) {
                is ReaderEvent.OpenEditor -> onOpenEditor(event.chapterId)
                is ReaderEvent.ReadFromHere -> {
                    val loaded = latestState as? ReaderUiState.Loaded
                    if (loaded != null && loaded.chapter.id == event.chapterId) {
                        narrationController?.start(loaded, event.sentence)
                    }
                }
                is ReaderEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
                ReaderEvent.CloseReader -> onBack()
            }
        }
    }
    LaunchedEffect(playbackState.activeSentence) {
        viewModel.onAction(ReaderAction.PlaybackSentenceChanged(playbackState.activeSentence))
    }
    LaunchedEffect(playbackState.message) {
        playbackState.message?.let { snackbarHostState.showSnackbar(it) }
    }
    ReaderScreen(
        state = state,
        playbackState = playbackState,
        nightModeEnabled = nightModeEnabled,
        onToggleNightMode = onToggleNightMode,
        snackbarHostState = snackbarHostState,
        onAction = viewModel::onAction,
        onPlaybackAction = { action ->
            val controller = narrationController ?: return@ReaderScreen
            when (action) {
                ReaderPlaybackAction.Toggle -> when {
                    playbackState.isPlaying -> controller.pause()
                    playbackState.status == ReaderPlaybackStatus.PAUSED -> controller.play()
                    else -> {
                        val loaded = state as? ReaderUiState.Loaded
                        val sentence = loaded?.sentences?.nearestBoundary(loaded.characterOffset)
                        if (loaded != null && sentence != null) controller.start(loaded, sentence)
                    }
                }
                ReaderPlaybackAction.Previous -> controller.previous()
                ReaderPlaybackAction.Next -> controller.next()
                ReaderPlaybackAction.Replay -> controller.replay()
                is ReaderPlaybackAction.SetSpeed -> controller.setSpeed(action.value)
                is ReaderPlaybackAction.SetEmotionEnabled -> {
                    controller.setEmotionEnabled(action.enabled)
                }
            }
        },
        onBack = { viewModel.onAction(ReaderAction.Exit) },
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
    playbackState: ReaderPlaybackUiState = ReaderPlaybackUiState(),
    onPlaybackAction: (ReaderPlaybackAction) -> Unit = {},
    nightModeEnabled: Boolean = false,
    onToggleNightMode: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val loaded = state as? ReaderUiState.Loaded
    var menuExpanded by remember { mutableStateOf(false) }
    var chapterListVisible by remember { mutableStateOf(false) }
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
                        IconButton(onClick = { chapterListVisible = true }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.FormatListBulleted,
                                contentDescription = stringResource(R.string.chapter_list_title),
                            )
                        }
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
                                if (onToggleNightMode != null) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (nightModeEnabled) {
                                                    stringResource(R.string.reader_night_mode_on)
                                                } else {
                                                    stringResource(R.string.reader_night_mode)
                                                },
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            onToggleNightMode()
                                        },
                                    )
                                }
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
                    playbackState = playbackState,
                    onPlaybackAction = onPlaybackAction,
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
    if (chapterListVisible && loaded != null) {
        ChapterListSheet(
            chapters = loaded.chapters,
            currentChapterId = loaded.chapter.id,
            onSelectChapter = { chapterId ->
                chapterListVisible = false
                onAction(ReaderAction.GoToChapter(chapterId))
            },
            onDismiss = { chapterListVisible = false },
        )
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
    val density = LocalDensity.current
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
    LaunchedEffect(viewportSize, state.paginationSpec, density.fontScale) {
        if (viewportSize.width > 0 && viewportSize.height > 0) {
            val updatedSpec = state.paginationSpec.copy(
                widthPx = viewportSize.width,
                heightPx = viewportSize.height,
                fontScale = density.fontScale,
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
    LaunchedEffect(pagerState, targetPage) {
        if (pagerState.currentPage != targetPage) pagerState.scrollToPage(targetPage)
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .filter { it != targetPage }
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
