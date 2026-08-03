package com.mkread.app.speech

import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import java.io.File

data class ZipVoicePaths(
    val modelDirectory: File,
) {
    fun toConfig(): OfflineTtsConfig {
        val zipVoice = OfflineTtsZipVoiceModelConfig(
            tokens = File(modelDirectory, "tokens.txt").path,
            encoder = File(modelDirectory, "encoder.int8.onnx").path,
            decoder = File(modelDirectory, "decoder.int8.onnx").path,
            vocoder = File(modelDirectory, "vocos_24khz.onnx").path,
            dataDir = File(modelDirectory, "espeak-ng-data").path,
            lexicon = File(modelDirectory, "lexicon.txt").path,
        )
        val model = OfflineTtsModelConfig(
            zipvoice = zipVoice,
            numThreads = 2,
            debug = false,
            provider = "cpu",
        )
        return OfflineTtsConfig(
            model = model,
            maxNumSentences = 1,
            silenceScale = 0.2f,
        )
    }

    companion object {
        fun fromFilesDir(filesDir: File): ZipVoicePaths =
            ZipVoicePaths(File(filesDir, "models/zipvoice"))
    }
}
