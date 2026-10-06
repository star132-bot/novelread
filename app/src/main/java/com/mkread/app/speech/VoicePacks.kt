package com.mkread.app.speech

import java.io.File

/** A voice pack offered for download (everything except the bundled Matcha voice). */
data class RemoteVoicePack(
    val id: String,
    val revision: Int,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

interface VoicePackSource {
    suspend fun available(): List<RemoteVoicePack>

    /** Downloads [pack] to [target], verifying size and SHA-256. */
    suspend fun download(pack: RemoteVoicePack, target: File, onProgress: (Float) -> Unit)
}

sealed interface VoicePackStatus {
    data object Installed : VoicePackStatus
    data class Available(val sizeBytes: Long) : VoicePackStatus
    data class Downloading(val progress: Float) : VoicePackStatus
    data class Unavailable(val message: String) : VoicePackStatus
}
