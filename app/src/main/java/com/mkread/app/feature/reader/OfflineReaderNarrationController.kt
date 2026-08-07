package com.mkread.app.feature.reader

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.mkread.app.playback.PlaybackService
import com.mkread.app.playback.SentenceId
import com.mkread.app.playback.SentenceMediaItemFactory
import com.mkread.app.speech.AudioCacheRepository
import com.mkread.app.speech.ContextEmotionAnalyzer
import com.mkread.app.speech.EmotionContext
import com.mkread.app.speech.GenerationState
import com.mkread.app.speech.InstalledVoiceProvider
import com.mkread.app.speech.NarrationSentence
import com.mkread.app.speech.NarrationSettings
import com.mkread.app.speech.SpeechAssetInstaller
import com.mkread.app.speech.SpeechGenerationCoordinator
import com.mkread.app.speech.SpeechQuality
import com.mkread.app.speech.TextNormalizer
import com.mkread.app.speech.ZipVoicePaths
import com.mkread.app.speech.ZipVoiceSpeechEngine
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class OfflineReaderNarrationController(
    context: Context,
    cache: AudioCacheRepository,
    chapterContentRepository: ChapterContentRepository,
    private val scope: CoroutineScope,
) : ReaderNarrationController {
    private val applicationContext = context.applicationContext
    private val mutableState = MutableStateFlow(ReaderPlaybackUiState())
    private val generationCounter = AtomicLong(0L)
    private val connectionMutex = Mutex()
    private val assetInstaller = SpeechAssetInstaller(applicationContext)
    private val emotionAnalyzer = applicationContext.assets
        .open(EMOTION_LEXICON_ASSET)
        .use(ContextEmotionAnalyzer::fromInputStream)
    private val queueSource = ReaderNarrationQueueSource(
        contentRepository = chapterContentRepository,
        segmenter = SentenceSegmenter(),
    )
    private val generationCoordinator = SpeechGenerationCoordinator(
        engine = ZipVoiceSpeechEngine(ZipVoicePaths.fromFilesDir(applicationContext.filesDir)),
        cache = cache,
        normalizer = TextNormalizer.fromAsset { path -> applicationContext.assets.open(path) },
        voiceProvider = InstalledVoiceProvider(applicationContext.filesDir),
        cacheDirectory = applicationContext.cacheDir,
        availableBytes = { applicationContext.cacheDir.usableSpace },
        nowMillis = System::currentTimeMillis,
    )

    private var mediaController: MediaController? = null
    private var generationJob: Job? = null
    private var autoPlayRequested = false
    private var activeSession: NarrationSession? = null
    private var starvationResumeIndex: Int? = null

    override val state: StateFlow<ReaderPlaybackUiState> = mutableState.asStateFlow()

    override fun start(reader: ReaderUiState.Loaded, from: SentenceRange) {
        val session = NarrationSession(reader, from)
        activeSession = session
        autoPlayRequested = true
        mutableState.value = mutableState.value.copy(
            status = ReaderPlaybackStatus.PREPARING,
            activeSentence = from.toSentenceId(reader),
            message = null,
        )
        launchGeneration(session, preserveCurrent = false)
    }

    override fun play() {
        autoPlayRequested = true
        withController { controller ->
            val resumeAt = starvationResumeIndex
            if (resumeAt != null && resumeAt in 0 until controller.mediaItemCount) {
                starvationResumeIndex = null
                controller.seekTo(resumeAt, 0L)
                controller.prepare()
            } else if (controller.playbackState == Player.STATE_ENDED) {
                controller.seekTo(0L)
            }
            controller.play()
            mutableState.value = mutableState.value.copy(status = ReaderPlaybackStatus.PLAYING)
        }
    }

    override fun pause() {
        autoPlayRequested = false
        withController { controller ->
            controller.pause()
            mutableState.value = mutableState.value.copy(status = ReaderPlaybackStatus.PAUSED)
        }
    }

    override fun previous() = withController { controller ->
        if (controller.hasPreviousMediaItem()) {
            controller.seekToPreviousMediaItem()
        } else {
            controller.seekTo(0L)
        }
    }

    override fun next() = withController { controller ->
        if (controller.hasNextMediaItem()) controller.seekToNextMediaItem()
    }

    override fun replay() = withController { controller ->
        autoPlayRequested = true
        controller.seekTo(0L)
        controller.play()
    }

    override fun setSpeed(value: Float) {
        val speed = value.coerceIn(MIN_SPEED, MAX_SPEED)
        mutableState.value = mutableState.value.copy(speed = speed)
        withController { controller -> controller.setPlaybackSpeed(speed) }
    }

    override fun setEmotionEnabled(enabled: Boolean) {
        val current = mutableState.value
        if (current.emotionEnabled == enabled) return
        mutableState.value = current.copy(emotionEnabled = enabled, message = null)
        val session = activeSession ?: return
        if (current.status in REBUILDABLE_STATUSES) {
            launchGeneration(session, preserveCurrent = true)
        }
    }

    private fun launchGeneration(
        session: NarrationSession,
        preserveCurrent: Boolean,
    ) {
        val generationId = generationCounter.incrementAndGet()
        generationJob?.cancel()
        generationJob = scope.launch {
            try {
                val controller = connect()
                if (generationId != generationCounter.get()) return@launch
                val resumeAfter = if (preserveCurrent) {
                    currentSentenceAndPruneFuture(controller, session.reader.book.id)
                } else {
                    withContext(Dispatchers.Main.immediate) {
                        controller.resetForNarrationReplacement()
                    }
                    null
                }
                val preserveQueue = resumeAfter != null
                if (preserveCurrent && !preserveQueue) {
                    mutableState.value = mutableState.value.copy(
                        status = ReaderPlaybackStatus.PREPARING,
                        activeSentence = session.initialSentence.toSentenceId(session.reader),
                    )
                    withContext(Dispatchers.Main.immediate) {
                        controller.resetForNarrationReplacement()
                    }
                }
                assetInstaller.install()
                var queueAvailable = preserveQueue
                val startupBuffer = NarrationStartupBuffer<MediaItem>(STARTUP_BUFFER_SENTENCES)
                queueSource.sentences(
                    reader = session.reader,
                    initialSentence = session.initialSentence,
                    resumeAfter = resumeAfter,
                ).takeWhile { planned ->
                    if (generationId != generationCounter.get()) return@takeWhile false
                    val generated = generationCoordinator.prepare(
                        queue = listOf(planned.toNarrationSentence(session.reader, generationId)),
                        currentIndex = 0,
                        settings = NarrationSettings(
                            voiceId = null,
                            styleId = styleFor(planned),
                            quality = SpeechQuality.FLUENT,
                        ),
                    ).singleOrNull()
                    when (generated) {
                        is GenerationState.Ready -> {
                            val item = generated.toMediaItem()
                            if (queueAvailable) {
                                appendToQueue(controller, item)
                            } else {
                                startupBuffer.add(item)?.let { initialQueue ->
                                    replaceQueueAndPlay(controller, initialQueue)
                                    queueAvailable = true
                                }
                            }
                            true
                        }
                        is GenerationState.Blocked -> {
                            if (queueAvailable) showMessage(generated.reason) else fail(generated.reason)
                            false
                        }
                        is GenerationState.StorageLow -> {
                            if (queueAvailable) showMessage(generated.reason) else fail(generated.reason)
                            false
                        }
                        null -> false
                    }
                }.collect()
                if (!queueAvailable) {
                    startupBuffer.drain().takeIf(List<MediaItem>::isNotEmpty)?.let { initialQueue ->
                        replaceQueueAndPlay(controller, initialQueue)
                        queueAvailable = true
                    }
                }
                resumeStarvedQueue(controller, force = true)
                if (!queueAvailable && generationId == generationCounter.get()) {
                    fail("No readable sentences remain")
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (generationId == generationCounter.get()) {
                    fail(failure.message ?: "Offline narration failed")
                }
            } finally {
                if (generationId == generationCounter.get()) finishIfPlayerEnded()
            }
        }
    }

    private fun withController(action: (MediaController) -> Unit) {
        scope.launch {
            runCatching {
                val controller = connect()
                withContext(Dispatchers.Main.immediate) { action(controller) }
            }.onFailure { failure ->
                fail(failure.message ?: "Playback service is unavailable")
            }
        }
    }

    private suspend fun connect(): MediaController = connectionMutex.withLock {
        mediaController?.let { return@withLock it }
        val future = withContext(Dispatchers.Main.immediate) {
            MediaController.Builder(
                applicationContext,
                SessionToken(applicationContext, ComponentName(applicationContext, PlaybackService::class.java)),
            ).buildAsync()
        }
        val created = withContext(Dispatchers.IO) {
            future.get(CONTROLLER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        withContext(Dispatchers.Main.immediate) {
            created.addListener(playerListener)
        }
        mediaController = created
        created
    }

    private suspend fun currentSentenceAndPruneFuture(
        controller: MediaController,
        expectedBookId: String,
    ): SentenceId? = withContext(Dispatchers.Main.immediate) {
        val plan = planEmotionQueueRebuild(
            expectedBookId = expectedBookId,
            currentSentence = controller.currentMediaItem?.mediaId
                ?.let(SentenceMediaItemFactory::decode),
            currentIndex = controller.currentMediaItemIndex,
            mediaItemCount = controller.mediaItemCount,
        ) ?: return@withContext null
        if (controller.mediaItemCount > plan.removeFromIndex) {
            controller.removeMediaItems(plan.removeFromIndex, controller.mediaItemCount)
        }
        plan.resumeAfter
    }

    private suspend fun replaceQueueAndPlay(controller: MediaController, items: List<MediaItem>) {
        withContext(Dispatchers.Main.immediate) {
            starvationResumeIndex = null
            controller.setMediaItems(items)
            controller.prepare()
            controller.setPlaybackSpeed(mutableState.value.speed)
            if (autoPlayRequested) controller.play()
        }
    }

    private suspend fun appendToQueue(controller: MediaController, item: MediaItem) {
        withContext(Dispatchers.Main.immediate) {
            val ended = controller.playbackState == Player.STATE_ENDED
            val resumeAt = if (ended && autoPlayRequested) {
                starvationResumeIndex ?: controller.mediaItemCount.also { index ->
                    starvationResumeIndex = index
                    mutableState.value = mutableState.value.copy(status = ReaderPlaybackStatus.PREPARING)
                }
            } else {
                null
            }
            controller.addMediaItem(item)
            if (resumeAt != null && controller.mediaItemCount - resumeAt >= STARVATION_BUFFER_SENTENCES) {
                resumeStarvedQueueOnMain(controller, resumeAt)
            }
        }
    }

    private suspend fun resumeStarvedQueue(controller: MediaController, force: Boolean) {
        withContext(Dispatchers.Main.immediate) {
            val resumeAt = starvationResumeIndex ?: return@withContext
            if (force || controller.mediaItemCount - resumeAt >= STARVATION_BUFFER_SENTENCES) {
                resumeStarvedQueueOnMain(controller, resumeAt)
            }
        }
    }

    private fun resumeStarvedQueueOnMain(controller: MediaController, resumeAt: Int) {
        if (!autoPlayRequested || resumeAt !in 0 until controller.mediaItemCount) return
        starvationResumeIndex = null
        controller.seekTo(resumeAt, 0L)
        controller.prepare()
        controller.play()
    }

    private suspend fun finishIfPlayerEnded() {
        val ended = mediaController?.let { controller ->
            withContext(Dispatchers.Main.immediate) {
                controller.playbackState == Player.STATE_ENDED
            }
        } ?: false
        if (ended) {
            autoPlayRequested = false
            mutableState.value = mutableState.value.copy(
                status = ReaderPlaybackStatus.IDLE,
                activeSentence = null,
            )
        }
    }

    private fun GenerationState.Ready.toMediaItem(): MediaItem = SentenceMediaItemFactory.create(
        sentenceId = SentenceId(
            sentence.bookId,
            sentence.chapterId,
            sentence.index,
            sentence.start,
            sentence.end,
        ),
        waveFile = file,
        bookTitle = sentence.bookTitle,
        chapterTitle = sentence.chapterTitle,
    )

    private fun SentenceRange.toSentenceId(reader: ReaderUiState.Loaded) = SentenceId(
        bookId = reader.book.id,
        chapterId = reader.chapter.id,
        index = index,
        start = startInclusive,
        end = endExclusive,
    )

    private fun PlannedNarrationSentence.toNarrationSentence(
        reader: ReaderUiState.Loaded,
        generationId: Long,
    ) = NarrationSentence(
        bookId = reader.book.id,
        bookContentSha256 = reader.book.sourceSha256,
        chapterId = chapter.id,
        index = range.index,
        start = range.startInclusive,
        end = range.endExclusive,
        rawText = text.substring(range.startInclusive, range.endExclusive),
        bookTitle = reader.book.title,
        chapterTitle = chapter.title,
        queueGenerationId = generationId,
    )

    private fun styleFor(planned: PlannedNarrationSentence): String {
        if (!mutableState.value.emotionEnabled) return NEUTRAL_STYLE
        val index = planned.range.index
        val previous = planned.sentences.getOrNull(index - 1)
        val next = planned.sentences.getOrNull(index + 1)
        return emotionAnalyzer.analyze(
            EmotionContext(
                previous = previous?.let {
                    planned.text.substring(it.startInclusive, it.endExclusive)
                }.orEmpty(),
                current = planned.text.substring(
                    planned.range.startInclusive,
                    planned.range.endExclusive,
                ),
                next = next?.let {
                    planned.text.substring(it.startInclusive, it.endExclusive)
                }.orEmpty(),
            ),
        ).emotion.wireName
    }

    private fun fail(message: String) {
        mutableState.value = mutableState.value.copy(
            status = ReaderPlaybackStatus.FAILED,
            message = message,
        )
    }

    private fun showMessage(message: String) {
        mutableState.value = mutableState.value.copy(message = message)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            autoPlayRequested = updatedAutoPlayRequest(autoPlayRequested, playWhenReady, reason)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val current = mutableState.value
            if (current.status == ReaderPlaybackStatus.PREPARING && !isPlaying) return
            mutableState.value = current.copy(
                status = if (isPlaying) ReaderPlaybackStatus.PLAYING else ReaderPlaybackStatus.PAUSED,
            )
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val sentence = mediaItem?.mediaId?.let(SentenceMediaItemFactory::decode)
            mutableState.value = mutableState.value.copy(activeSentence = sentence)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && generationJob?.isActive != true) {
                autoPlayRequested = false
                mutableState.value = mutableState.value.copy(
                    status = ReaderPlaybackStatus.IDLE,
                    activeSentence = null,
                )
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            fail(error.message ?: "Narration playback failed")
        }
    }

    private data class NarrationSession(
        val reader: ReaderUiState.Loaded,
        val initialSentence: SentenceRange,
    )

    private companion object {
        const val EMOTION_LEXICON_ASSET = "speech/emotion-lexicon-zh.json"
        const val NEUTRAL_STYLE = "neutral"
        const val CONTROLLER_TIMEOUT_SECONDS = 20L
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3f
        const val STARTUP_BUFFER_SENTENCES = 3
        const val STARVATION_BUFFER_SENTENCES = 2
        val REBUILDABLE_STATUSES = setOf(
            ReaderPlaybackStatus.PREPARING,
            ReaderPlaybackStatus.PLAYING,
            ReaderPlaybackStatus.PAUSED,
        )
    }
}

internal fun updatedAutoPlayRequest(
    current: Boolean,
    playWhenReady: Boolean,
    reason: Int,
): Boolean = if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
    playWhenReady
} else {
    current
}
