package com.mkread.app.speech

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class InstalledVoiceProvider(
    private val filesDir: File,
    private val fallback: NarrationVoiceProvider = BuiltInVoiceProvider(filesDir),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : NarrationVoiceProvider {
    private val voicesRoot = File(filesDir, VOICES_DIRECTORY)

    override suspend fun resolve(voiceId: String?, styleId: String): Result<NarrationVoice> {
        val selectedId = voiceId ?: selectedVoiceId()
        if (selectedId == null || selectedId == BUILT_IN_VOICE_ID) {
            return fallback.resolve(voiceId, styleId)
        }
        val installed = withContext(ioDispatcher) {
            runCatching { loadInstalledVoice(selectedId, styleId) }
        }
        return if (installed.isSuccess) installed else fallback.resolve(null, styleId)
    }

    override suspend fun builtInNeutral(): Result<NarrationVoice> = fallback.builtInNeutral()

    private suspend fun selectedVoiceId(): String? = withContext(ioDispatcher) {
        File(voicesRoot, SELECTION_FILE_NAME)
            .takeIf(File::isFile)
            ?.readText(Charsets.UTF_8)
            ?.trim()
            ?.takeIf(VOICE_ID::matches)
    }

    private fun loadInstalledVoice(voiceId: String, requestedStyle: String): NarrationVoice {
        require(VOICE_ID.matches(voiceId)) { "Voice id is invalid" }
        val root = File(voicesRoot, voiceId)
        require(root.isDirectory) { "Voice is not installed" }
        val manifestFile = File(root, MANIFEST_FILE_NAME)
        val checksumsFile = File(root, CHECKSUMS_FILE_NAME)
        require(manifestFile.isFile && checksumsFile.isFile) { "Voice metadata is incomplete" }
        val manifest = Json.parseToJsonElement(manifestFile.readText(Charsets.UTF_8)) as? JsonObject
            ?: error("Voice manifest is invalid")
        require(manifest.string("id") == voiceId) { "Voice manifest id does not match" }
        val styles = manifest["styles"] as? JsonArray ?: error("Voice styles are missing")
        val selected = styles
            .map { it as? JsonObject ?: error("Voice style is invalid") }
            .firstOrNull { it.string("emotion") == requestedStyle }
            ?: styles
                .map { it as? JsonObject ?: error("Voice style is invalid") }
                .firstOrNull { it.string("emotion") == NEUTRAL_STYLE }
            ?: error("Voice has no neutral style")
        val style = selected.string("emotion")
        val audio = root.resolveSafe(selected.string("audio"))
        val transcript = root.resolveSafe(selected.string("transcript"))
        require(audio.isFile && transcript.isFile) { "Voice prompt is incomplete" }
        WaveValidator.requirePlayable(audio)
        val transcriptText = transcript.readText(Charsets.UTF_8).trim()
        require(transcriptText.isNotEmpty()) { "Voice transcript is blank" }
        return NarrationVoice(
            id = voiceId,
            packageSha256 = packageHash(manifestFile, checksumsFile),
            styleId = style,
            reference = VoiceReference(audio, transcriptText),
        )
    }

    private fun File.resolveSafe(relativePath: String): File {
        require(relativePath.isNotBlank() && !relativePath.startsWith('/')) { "Voice path is invalid" }
        val resolved = File(this, relativePath).canonicalFile
        require(resolved.toPath().startsWith(canonicalFile.toPath())) { "Voice path escapes its package" }
        return resolved
    }

    private fun packageHash(vararg files: File): String {
        val digest = MessageDigest.getInstance(SHA_256)
        files.forEach { file ->
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            digest.update(0)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
    }

    private fun JsonObject.string(name: String): String =
        (this[name] as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
            ?: error("Voice manifest field is invalid: $name")

    companion object {
        const val SELECTION_FILE_NAME = "selected-voice.id"
        private const val VOICES_DIRECTORY = "voices"
        private const val BUILT_IN_VOICE_ID = "builtin-dev"
        private const val MANIFEST_FILE_NAME = "manifest.json"
        private const val CHECKSUMS_FILE_NAME = "checksums.json"
        private const val NEUTRAL_STYLE = "neutral"
        private const val SHA_256 = "SHA-256"
        private val VOICE_ID = Regex("[a-z0-9]+(?:[.-][a-z0-9]+)+")

        fun select(filesDir: File, voiceId: String) {
            require(VOICE_ID.matches(voiceId)) { "Voice id is invalid" }
            val voicesRoot = File(filesDir, VOICES_DIRECTORY)
            require(File(voicesRoot, voiceId).isDirectory) { "Voice is not installed" }
            require(voicesRoot.isDirectory || voicesRoot.mkdirs()) { "Voice directory is unavailable" }
            File(voicesRoot, SELECTION_FILE_NAME).writeText(voiceId, Charsets.UTF_8)
        }
    }
}
