package com.mkread.app.speech

import android.app.Application
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SpikeState {
    data object Idle : SpikeState

    data object Installing : SpikeState

    data object Generating : SpikeState

    data class Ready(
        val file: File,
        val durationMs: Long,
        val generationMs: Long,
    ) : SpikeState

    data class Failed(val message: String) : SpikeState
}

class SpikeViewModel(application: Application) : AndroidViewModel(application) {
    var state: SpikeState by mutableStateOf(SpikeState.Idle)
        private set

    private val installer = SpeechAssetInstaller(application)
    private var speechEngine: ZipVoiceSpeechEngine? = null
    private var mediaPlayer: MediaPlayer? = null

    fun generateAndPlay(text: String) {
        if (state is SpikeState.Installing || state is SpikeState.Generating) return
        if (text.isBlank()) {
            state = SpikeState.Failed("请输入要朗读的文字")
            return
        }

        viewModelScope.launch {
            try {
                state = SpikeState.Installing
                installer.install()
                val application = getApplication<Application>()
                val reference = withContext(Dispatchers.IO) {
                    val promptDirectory = File(application.filesDir, "voices/builtin-dev/prompts")
                    VoiceReference(
                        audioFile = File(promptDirectory, "neutral.wav"),
                        transcript = File(promptDirectory, "neutral.txt")
                            .readText(Charsets.UTF_8)
                            .trim(),
                    )
                }
                val engine = speechEngine ?: ZipVoiceSpeechEngine(
                    ZipVoicePaths.fromFilesDir(application.filesDir),
                ).also { speechEngine = it }

                state = SpikeState.Generating
                engine.initialize().getOrThrow()
                val result = engine.generate(
                    SpeechRequest(
                        text = text,
                        reference = reference,
                        quality = SpeechQuality.FLUENT,
                        outputFile = File(application.cacheDir, "spike/ui-preview.wav"),
                    ),
                ).getOrThrow()
                val ready = SpikeState.Ready(
                    file = result.file,
                    durationMs = result.sampleCount * 1_000L / result.sampleRate,
                    generationMs = result.generationMillis,
                )
                play(ready.file)
                state = ready
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                state = SpikeState.Failed(
                    error.message?.take(160) ?: "语音生成失败",
                )
            }
        }
    }

    fun replay() {
        val ready = state as? SpikeState.Ready ?: return
        try {
            play(ready.file)
        } catch (error: Throwable) {
            state = SpikeState.Failed(
                error.message?.take(160) ?: "音频播放失败",
            )
        }
    }

    override fun onCleared() {
        mediaPlayer?.release()
        mediaPlayer = null
        speechEngine?.close()
        speechEngine = null
        super.onCleared()
    }

    private fun play(file: File) {
        mediaPlayer?.release()
        mediaPlayer = null
        val player = MediaPlayer()
        try {
            player.setDataSource(file.path)
            player.prepare()
            player.start()
            mediaPlayer = player
        } catch (error: Throwable) {
            player.release()
            throw error
        }
    }
}
