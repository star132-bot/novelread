package com.mkread.app.playback

data class SentenceId(
    val bookId: String,
    val chapterId: String,
    val index: Int,
    val start: Int,
    val end: Int,
) {
    init {
        require(bookId.isNotBlank()) { "Book id must not be blank" }
        require(chapterId.isNotBlank()) { "Chapter id must not be blank" }
        require(index >= 0) { "Sentence index must be non-negative" }
        require(start >= 0) { "Sentence start must be non-negative" }
        require(end > start) { "Sentence end must follow its start" }
    }
}

data class QueuedSentence(
    val id: SentenceId,
    val audioPath: String? = null,
) {
    init {
        require(audioPath == null || audioPath.isNotBlank()) { "Audio path must not be blank" }
    }
}

data class PlaybackQueue(
    val generationId: Long,
    val sentences: List<QueuedSentence>,
) {
    init {
        require(generationId >= 0L) { "Queue generation id must be non-negative" }
        require(sentences.isNotEmpty()) { "Playback queue must not be empty" }
        require(sentences.map(QueuedSentence::id).distinct().size == sentences.size) {
            "Playback queue sentence ids must be unique"
        }
    }

    fun sentence(id: SentenceId): QueuedSentence? = sentences.firstOrNull { it.id == id }

    fun replaceAudio(id: SentenceId, audioPath: String): PlaybackQueue = copy(
        sentences = sentences.map { sentence ->
            if (sentence.id == id) sentence.copy(audioPath = audioPath) else sentence
        },
    )
}

enum class PlaybackPauseReason {
    USER,
    INTERRUPTION,
    RESTORED,
}

sealed interface PlaybackState {
    data object Idle : PlaybackState

    data class Preparing(
        val queue: PlaybackQueue,
        val current: SentenceId,
        val playWhenReady: Boolean,
    ) : PlaybackState

    data class Playing(
        val queue: PlaybackQueue,
        val current: SentenceId,
        val stopAfterCurrentSentence: Boolean = false,
    ) : PlaybackState

    data class Paused(
        val queue: PlaybackQueue,
        val current: SentenceId,
        val reason: PlaybackPauseReason = PlaybackPauseReason.USER,
    ) : PlaybackState

    data class WaitingForAudio(
        val queue: PlaybackQueue,
        val current: SentenceId,
        val resumeWhenReady: Boolean,
    ) : PlaybackState

    data class Failed(
        val queue: PlaybackQueue,
        val current: SentenceId,
        val message: String,
    ) : PlaybackState

    data object Completed : PlaybackState
}

sealed interface PlaybackEvent {
    data class Load(
        val queue: PlaybackQueue,
        val from: SentenceId,
        val playWhenReady: Boolean,
    ) : PlaybackEvent

    data class AudioReady(
        val generationId: Long,
        val sentenceId: SentenceId,
        val audioPath: String,
    ) : PlaybackEvent

    data object Play : PlaybackEvent
    data object Pause : PlaybackEvent

    data class MediaEnded(val sentenceId: SentenceId) : PlaybackEvent

    data object Next : PlaybackEvent
    data object Previous : PlaybackEvent
    data object Replay : PlaybackEvent
    data class SeekToSentence(val sentenceId: SentenceId) : PlaybackEvent

    data class QueueReplaced(
        val queue: PlaybackQueue,
        val from: SentenceId,
    ) : PlaybackEvent

    data class GenerationFailed(
        val generationId: Long,
        val sentenceId: SentenceId,
        val message: String,
        val retryable: Boolean,
    ) : PlaybackEvent

    data object FocusLoss : PlaybackEvent
    data object FocusGain : PlaybackEvent
    data object NoisyOutput : PlaybackEvent
    data object TimerExpired : PlaybackEvent

    data class ServiceRestored(
        val queue: PlaybackQueue,
        val checkpoint: PlaybackCheckpoint,
        val resumeRequested: Boolean,
    ) : PlaybackEvent
}

sealed interface PlaybackEffect {
    data class RequestGeneration(
        val generationId: Long,
        val sentenceId: SentenceId,
    ) : PlaybackEffect

    data class SetPlayerItems(val sentences: List<QueuedSentence>) : PlaybackEffect
    data object PlayPlayer : PlaybackEffect
    data object PausePlayer : PlaybackEffect

    data class PersistCheckpoint(
        val sentenceId: SentenceId,
        val queueGenerationId: Long,
        val wasPlaying: Boolean,
    ) : PlaybackEffect

    data object StopService : PlaybackEffect
    data class ShowError(val message: String, val retryable: Boolean) : PlaybackEffect
}

data class PlaybackReduction(
    val state: PlaybackState,
    val effects: List<PlaybackEffect> = emptyList(),
)
