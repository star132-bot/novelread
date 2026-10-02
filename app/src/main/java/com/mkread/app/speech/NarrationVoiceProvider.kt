package com.mkread.app.speech

data class NarrationVoice(
    val id: String,
    val packageSha256: String,
    val styleId: String,
    val reference: VoiceReference?,
    val model: VoiceModel = VoiceModel.ZIPVOICE,
    val speakerId: Int = 0,
)

interface NarrationVoiceProvider {
    suspend fun resolve(voiceId: String?, styleId: String): Result<NarrationVoice>

    suspend fun builtInNeutral(): Result<NarrationVoice>
}
