package com.mkread.app.feature.reader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalContext
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
import com.mkread.app.playback.SentenceId
import com.mkread.app.ui.theme.ThemeMode
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
    displaySettings: ReaderDisplaySettings = ReaderDisplaySettings(),
    onDisplaySettingsChange: ((ReaderDisplaySettings) -> Unit)? = null,
    themeMode: ThemeMode? = null,
    onThemeModeChange: ((ThemeMode) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val playbackState = narrationController?.state?.collectAsState()?.value
        ?: ReaderPlaybackUiState()
    val latestState by rememberUpdatedState(state)
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    // Android 13+ hides the playback notification (and lock-screen controls) until the user allows it.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val requestNotificationsOnce = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
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
    // While narration is running, jumping to another chapter continues narration there instead of
    // leaving the voice reading a chapter that is no longer on screen.
    var narrateChapterOnLoad by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state, narrateChapterOnLoad) {
        val targetChapterId = narrateChapterOnLoad ?: return@LaunchedEffect
        val loaded = state as? ReaderUiState.Loaded ?: return@LaunchedEffect
        if (loaded.chapter.id != targetChapterId || loaded.pages.isEmpty()) return@LaunchedEffect
        narrateChapterOnLoad = null
        val first = loaded.sentences.firstOrNull() ?: return@LaunchedEffect
        viewModel.onAction(ReaderAction.ReadFromOffset(first.startInclusive))
    }
    val narrationActive = playbackState.status == ReaderPlaybackStatus.PLAYING ||
        playbackState.status == ReaderPlaybackStatus.PREPARING
    val onReaderAction: (ReaderAction) -> Unit = { action ->
        val loaded = state as? ReaderUiState.Loaded
        val targetChapterId = when (action) {
            is ReaderAction.GoToChapter -> action.chapterId
            ReaderAction.NextChapter -> loaded?.chapters?.getOrNull(loaded.chapterIndex + 1)?.id
            ReaderAction.PreviousChapter -> loaded?.chapters?.getOrNull(loaded.chapterIndex - 1)?.id
            else -> null
        }
        if (targetChapterId != null && narrationActive && targetChapterId != loaded?.chapter?.id) {
            narrateChapterOnLoad = targetChapterId
        }
        viewModel.onAction(action)
    }
    LaunchedEffect(viewModel, narrationController) {
        viewModel.events.collect { event ->
            when (event) {
                is ReaderEvent.OpenEditor -> onOpenEditor(event.chapterId)
                is ReaderEvent.ReadFromHere -> {
                    val loaded = latestState as? ReaderUiState.Loaded
                    if (loaded != null && loaded.chapter.id == event.chapterId) {
                        requestNotificationsOnce()
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
        onAction = onReaderAction,
        displaySettings = displaySettings,
        onDisplaySettingsChange = onDisplaySettingsChange,
        themeMode = themeMode,
        onThemeModeChange = onThemeModeChange,
        onPlaybackAction = { action ->
            val controller = narrationController ?: return@ReaderScreen
            val loaded = state as? ReaderUiState.Loaded
            when (action) {
                ReaderPlaybackAction.Toggle -> when {
                    playbackState.isPlaying -> controller.pause()
                    // Resume only when the paused sentence is still what the reader shows; after the
                    // reader turned to another page or chapter, read from there instead.
                    playbackState.status == ReaderPlaybackStatus.PAUSED &&
                        (loaded == null || playbackState.activeSentence.isOnCurrentPage(loaded)) ->
                        controller.play()
                    loaded != null -> {
                        val sentence = loaded.sentences.firstOnPage(loaded)
                        if (sentence != null) {
                            requestNotificationsOnce()
                            viewModel.onAction(ReaderAction.ReadFromOffset(sentence.startInclusive))
                        }
                    }
                    else -> Unit
                }
                // Sentence skipping restarts narration from the neighbouring sentence, so it also
                // works before narration has started and when the next sentence is not generated yet.
                ReaderPlaybackAction.Previous, ReaderPlaybackAction.Next -> if (loaded != null) {
                    val delta = if (action == ReaderPlaybackAction.Next) 1 else -1
                    val current = loaded.currentNarrationSentence(playbackState.activeSentence)
                    val position = current?.let(loaded.sentences::indexOf) ?: -1
                    val target = loaded.sentences.getOrNull(position + delta)
                    val nextChapter = loaded.chapters.getOrNull(loaded.chapterIndex + 1)
                    when {
                        target != null -> {
                            requestNotificationsOnce()
                            viewModel.onAction(ReaderAction.ReadFromOffset(target.startInclusive))
                        }
                        delta > 0 && nextChapter != null -> {
                            narrateChapterOnLoad = nextChapter.id
                            viewModel.onAction(ReaderAction.NextChapter)
                        }
                        current != null -> {
                            requestNotificationsOnce()
                            viewModel.onAction(ReaderAction.ReadFromOffset(current.startInclusive))
                        }
                    }
                } else {
                    Unit
                }
                ReaderPlaybackAction.Replay -> when {
                    playbackState.status == ReaderPlaybackStatus.PLAYING ||
                        playbackState.status == ReaderPlaybackStatus.PAUSED -> controller.replay()
                    loaded != null -> loaded.currentNarrationSentence(playbackState.activeSentence)?.let {
                        requestNotificationsOnce()
                        viewModel.onAction(ReaderAction.ReadFromOffset(it.startInclusive))
                    }
                    else -> Unit
                }
                is ReaderPlaybackAction.SetSpeed -> controller.setSpeed(action.value)
                is ReaderPlaybackAction.SetEmotionEnabled -> {
                    controller.setEmotionEnabled(action.enabled)
                }
                is ReaderPlaybackAction.SetVoice -> controller.setVoice(action.voiceId)
                is ReaderPlaybackAction.DownloadVoicePack -> controller.downloadVoicePack(action.modelId)
                ReaderPlaybackAction.RefreshVoicePacks -> controller.refreshVoicePacks()
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
    displaySettings: ReaderDisplaySettings = ReaderDisplaySettings(),
    onDisplaySettingsChange: ((ReaderDisplaySettings) -> Unit)? = null,
    themeMode: ThemeMode? = null,
    onThemeModeChange: ((ThemeMode) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val loaded = state as? ReaderUiState.Loaded
    var menuExpanded by remember { mutableStateOf(false) }
    var chapterListVisible by remember { mutableStateOf(false) }
    var displaySheetVisible by remember { mutableStateOf(false) }
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
                                if (onDisplaySettingsChange != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.reader_display_settings)) },
                                        onClick = {
                                            menuExpanded = false
                                            displaySheetVisible = true
                                        },
                                    )
                                }
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
                    displaySettings = displaySettings,
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
    if (displaySheetVisible && onDisplaySettingsChange != null) {
        ReaderDisplaySheet(
            settings = displaySettings,
            onSettingsChange = onDisplaySettingsChange,
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            onDismiss = { displaySheetVisible = false },
        )
    }
}

/** The sentence narration is on, or the first sentence of the visible page when narration is elsewhere. */
private fun ReaderUiState.Loaded.currentNarrationSentence(active: SentenceId?): SentenceRange? =
    active
        ?.takeIf { it.isOnCurrentPage(this) }
        ?.let { id -> sentences.firstOrNull { id.start in it.startInclusive until it.endExclusive } }
        ?: sentences.firstOnPage(this)

private fun SentenceId?.isOnCurrentPage(reader: ReaderUiState.Loaded): Boolean {
    if (this == null || bookId != reader.book.id || chapterId != reader.chapter.id) return false
    val page = reader.pages.getOrNull(reader.currentPage) ?: return true
    return start < page.endExclusive && end > page.start
}

private fun List<SentenceRange>.firstOnPage(reader: ReaderUiState.Loaded): SentenceRange? {
    val page = reader.pages.getOrNull(reader.currentPage)
    return if (page == null) {
        nearestBoundary(reader.characterOffset)
    } else {
        firstOrNull { it.endExclusive > page.start && it.startInclusive < page.endExclusive }
            ?: nearestBoundary(page.start)
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
    displaySettings: ReaderDisplaySettings,
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
    LaunchedEffect(viewportSize, state.paginationSpec, density.fontScale, displaySettings) {
        if (viewportSize.width > 0 && viewportSize.height > 0) {
            val updatedSpec = state.paginationSpec.copy(
                widthPx = viewportSize.width,
                heightPx = viewportSize.height,
                fontScale = density.fontScale,
            ).withDisplaySettings(displaySettings, density.density)
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
            onTextTap = { characterOffset ->
                onAction(ReaderAction.ReadFromOffset(characterOffset))
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
