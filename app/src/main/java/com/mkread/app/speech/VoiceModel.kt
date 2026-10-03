package com.mkread.app.speech

import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * An offline narration model shipped as a pack under `files/models/<directory>`.
 *
 * Preset models speak with built-in speakers and are fast enough for real-time narration
 * on phones; ZipVoice clones a reference recording and is much slower.
 */
enum class VoiceModel(
    val id: String,
    val directory: String,
    val sampleRate: Int,
    val clonesVoices: Boolean,
    /** Sentences to buffer before playback starts; slower models need more headroom. */
    val startupBufferSentences: Int,
    /** Bumped whenever the model files or its synthesis settings change. */
    val revision: Int,
) {
    MATCHA("matcha-zh-en", "matcha-zh-en", 16_000, clonesVoices = false, startupBufferSentences = 1, revision = 1),
    MELO("melo-zh-en", "melo-zh-en", 44_100, clonesVoices = false, startupBufferSentences = 1, revision = 1),
    KOKORO("kokoro-zh-en", "kokoro-zh-en", 24_000, clonesVoices = false, startupBufferSentences = 2, revision = 1),
    ZIPVOICE("zipvoice", "zipvoice", 24_000, clonesVoices = true, startupBufferSentences = 3, revision = 2),
    ;

    /** Asset path prefixes that must be installed before this model can run. */
    val assetPrefixes: List<String>
        get() = if (this == ZIPVOICE) listOf("models/$directory/", "voices/") else listOf("models/$directory/")

    fun modelDirectory(filesDir: File): File = File(filesDir, "models/$directory")

    /**
     * Files and directories the native engine opens. sherpa-onnx crashes the process (instead of
     * failing) when one is missing, so the engine checks these before loading the model.
     */
    fun requiredPaths(filesDir: File): List<File> {
        val names = when (this) {
            ZIPVOICE -> listOf("tokens.txt", "encoder.int8.onnx", "decoder.int8.onnx", "vocos_24khz.onnx", "lexicon.txt", "espeak-ng-data")
            MATCHA -> listOf("model-steps-3.onnx", "vocos-16khz-univ.onnx", "tokens.txt", "lexicon.txt", "espeak-ng-data")
            MELO -> listOf("model.onnx", "tokens.txt", "lexicon.txt", "dict")
            KOKORO -> listOf("model.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt", "lexicon-zh.txt", "espeak-ng-data", "dict")
        }
        return names.map { File(modelDirectory(filesDir), it) }
    }

    fun toConfig(filesDir: File, numThreads: Int = DEFAULT_THREADS): OfflineTtsConfig {
        val root = modelDirectory(filesDir)
        fun path(name: String) = File(root, name).path
        return when (this) {
            ZIPVOICE -> ZipVoicePaths(root).toConfig()
            MATCHA -> OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    matcha = OfflineTtsMatchaModelConfig(
                        acousticModel = path("model-steps-3.onnx"),
                        vocoder = path("vocos-16khz-univ.onnx"),
                        tokens = path("tokens.txt"),
                        lexicon = path("lexicon.txt"),
                        dataDir = path("espeak-ng-data"),
                    ),
                    numThreads = numThreads,
                    provider = "cpu",
                ),
                ruleFsts = listOf("date-zh.fst", "phone-zh.fst", "number-zh.fst").joinToString(",") { path(it) },
                maxNumSentences = 1,
                silenceScale = SILENCE_SCALE,
            )
            MELO -> OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = path("model.onnx"),
                        tokens = path("tokens.txt"),
                        lexicon = path("lexicon.txt"),
                        dictDir = path("dict"),
                    ),
                    numThreads = numThreads,
                    provider = "cpu",
                ),
                ruleFsts = listOf("date.fst", "phone.fst", "number.fst", "new_heteronym.fst")
                    .joinToString(",") { path(it) },
                maxNumSentences = 1,
                silenceScale = SILENCE_SCALE,
            )
            KOKORO -> OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = path("model.onnx"),
                        voices = path("voices.bin"),
                        tokens = path("tokens.txt"),
                        dataDir = path("espeak-ng-data"),
                        dictDir = path("dict"),
                        lexicon = "${path("lexicon-us-en.txt")},${path("lexicon-zh.txt")}",
                    ),
                    numThreads = numThreads,
                    provider = "cpu",
                ),
                ruleFsts = listOf("date-zh.fst", "phone-zh.fst", "number-zh.fst").joinToString(",") { path(it) },
                maxNumSentences = 1,
                silenceScale = SILENCE_SCALE,
            )
        }
    }

    companion object {
        const val DEFAULT_THREADS = 4
        const val SILENCE_SCALE = 0.2f
        val SUPPORTED_SAMPLE_RATES: Set<Int> = entries.map(VoiceModel::sampleRate).toSet()

        fun fromId(id: String): VoiceModel? = entries.firstOrNull { it.id == id }
    }
}
