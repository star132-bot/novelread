package com.mkread.app.speech

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BuiltInVoiceProvider(
    filesDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : NarrationVoiceProvider {
    private val promptFile = File(filesDir, BUILT_IN_PROMPT_PATH)
    private val transcriptFile = File(filesDir, BUILT_IN_TRANSCRIPT_PATH)

    override suspend fun resolve(voiceId: String?, styleId: String): Result<NarrationVoice> =
        builtInNeutral()

    override suspend fun builtInNeutral(): Result<NarrationVoice> = withContext(ioDispatcher) {
        runCatching {
            require(promptFile.isFile && transcriptFile.isFile) { "Built-in narration voice is not installed" }
            val transcript = transcriptFile.readText(Charsets.UTF_8).trim()
            require(transcript.isNotEmpty()) { "Built-in narration transcript is blank" }
            WaveValidator.requirePlayable(promptFile)
            NarrationVoice(
                id = BUILT_IN_VOICE_ID,
                packageSha256 = packageHash(promptFile, transcript),
                styleId = NEUTRAL_STYLE,
                reference = VoiceReference(promptFile, transcript),
            )
        }
    }

    private fun packageHash(prompt: File, transcript: String): String {
        val digest = MessageDigest.getInstance(SHA_256)
        prompt.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        digest.update(0)
        digest.update(transcript.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val BUILT_IN_VOICE_ID = "builtin-dev"
        const val BUILT_IN_PROMPT_PATH = "voices/builtin-dev/prompts/neutral.wav"
        const val BUILT_IN_TRANSCRIPT_PATH = "voices/builtin-dev/prompts/neutral.txt"
        const val NEUTRAL_STYLE = "neutral"
        const val SHA_256 = "SHA-256"
    }
}
