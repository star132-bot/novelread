package com.mkread.app.playback

const val MKREAD_PREFERENCES_FILE_NAME = "mkread.preferences_pb"

data class PlaybackCheckpoint(
    val sentenceId: SentenceId,
    val queueGenerationId: Long,
    val speed: Float,
    val wasPlaying: Boolean,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(queueGenerationId >= 0L) { "Queue generation id must be non-negative" }
        require(speed.isFinite() && speed in MIN_PLAYBACK_SPEED..MAX_PLAYBACK_SPEED) {
            "Playback speed must be between $MIN_PLAYBACK_SPEED and $MAX_PLAYBACK_SPEED"
        }
        require(updatedAtEpochMillis >= 0L) { "Checkpoint update time must be non-negative" }
    }
}

interface PlaybackCheckpointStore {
    suspend fun read(): PlaybackCheckpoint?
    suspend fun save(checkpoint: PlaybackCheckpoint)
    suspend fun clear()
}

const val MIN_PLAYBACK_SPEED = 0.5f
const val MAX_PLAYBACK_SPEED = 2.0f
