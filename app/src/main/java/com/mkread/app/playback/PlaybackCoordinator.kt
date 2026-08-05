package com.mkread.app.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.mkread.app.speech.WaveValidator
import java.io.File

class PlaybackCoordinator(
    private val player: Player,
    private val reducer: PlaybackReducer = PlaybackReducer(),
) {
    var state: PlaybackState = PlaybackState.Idle
        private set

    fun dispatch(event: PlaybackEvent) {
        val reduction = reducer.reduce(state, event)
        state = reduction.state
        reduction.effects.forEach(::apply)
    }

    private fun apply(effect: PlaybackEffect) {
        when (effect) {
            is PlaybackEffect.SetPlayerItems -> {
                val items = effect.sentences.mapNotNull { sentence -> sentence.toMediaItemOrNull() }
                if (items.isNotEmpty()) {
                    player.setMediaItems(items)
                    player.prepare()
                }
            }
            PlaybackEffect.PlayPlayer -> player.play()
            PlaybackEffect.PausePlayer -> player.pause()
            PlaybackEffect.StopService -> player.stop()
            is PlaybackEffect.PersistCheckpoint,
            is PlaybackEffect.RequestGeneration,
            is PlaybackEffect.ShowError,
            -> Unit
        }
    }

    private fun QueuedSentence.toMediaItemOrNull(): MediaItem? {
        val path = audioPath ?: return null
        val file = File(path)
        if (!file.isFile || runCatching { WaveValidator.requirePlayable(file) }.isFailure) return null
        return MediaItem.Builder()
            .setMediaId(SentenceMediaItemFactory.mediaId(id))
            .setUri(file.toURI().toString())
            .build()
    }
}
