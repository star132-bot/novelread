package com.mkread.app.feature.reader

import androidx.media3.common.Player
import com.mkread.app.playback.SentenceId
import kotlinx.coroutines.flow.StateFlow

enum class ReaderPlaybackStatus {
    IDLE,
    PREPARING,
    PLAYING,
    PAUSED,
    FAILED,
}

data class ReaderPlaybackUiState(
    val status: ReaderPlaybackStatus = ReaderPlaybackStatus.IDLE,
    val activeSentence: SentenceId? = null,
    val speed: Float = 1f,
    val emotionEnabled: Boolean = true,
    val message: String? = null,
) {
    val isPlaying: Boolean
        get() = status == ReaderPlaybackStatus.PLAYING

    val isPreparing: Boolean
        get() = status == ReaderPlaybackStatus.PREPARING
}

sealed interface ReaderPlaybackAction {
    data object Toggle : ReaderPlaybackAction
    data object Previous : ReaderPlaybackAction
    data object Next : ReaderPlaybackAction
    data object Replay : ReaderPlaybackAction
    data class SetSpeed(val value: Float) : ReaderPlaybackAction
    data class SetEmotionEnabled(val enabled: Boolean) : ReaderPlaybackAction
}

interface ReaderNarrationController {
    val state: StateFlow<ReaderPlaybackUiState>

    fun start(reader: ReaderUiState.Loaded, from: SentenceRange)

    fun play()

    fun pause()

    fun previous()

    fun next()

    fun replay()

    fun setSpeed(value: Float)

    fun setEmotionEnabled(enabled: Boolean)
}

internal fun Player.resetForNarrationReplacement() {
    pause()
    clearMediaItems()
}

internal data class NarrationQueueRebuildPlan(
    val resumeAfter: SentenceId,
    val removeFromIndex: Int,
)

internal fun planEmotionQueueRebuild(
    expectedBookId: String,
    currentSentence: SentenceId?,
    currentIndex: Int,
    mediaItemCount: Int,
): NarrationQueueRebuildPlan? {
    if (
        currentSentence?.bookId != expectedBookId ||
        currentIndex !in 0 until mediaItemCount
    ) {
        return null
    }
    return NarrationQueueRebuildPlan(
        resumeAfter = currentSentence,
        removeFromIndex = currentIndex + 1,
    )
}
