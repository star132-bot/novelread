package com.mkread.app.speech

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class ZipVoiceConfigTest {
    @Test
    fun privateModelPaths_mapToCpuZipVoiceConfig() {
        val filesDir = File("build/test-private-files").absoluteFile

        val config = ZipVoicePaths.fromFilesDir(filesDir).toConfig()
        val modelRoot = File(filesDir, "models/zipvoice")
        val zipVoice = config.model.zipvoice

        assertEquals(File(modelRoot, "tokens.txt").path, zipVoice.tokens)
        assertEquals(File(modelRoot, "encoder.int8.onnx").path, zipVoice.encoder)
        assertEquals(File(modelRoot, "decoder.int8.onnx").path, zipVoice.decoder)
        assertEquals(File(modelRoot, "vocos_24khz.onnx").path, zipVoice.vocoder)
        assertEquals(File(modelRoot, "espeak-ng-data").path, zipVoice.dataDir)
        assertEquals(File(modelRoot, "lexicon.txt").path, zipVoice.lexicon)
        assertEquals("cpu", config.model.provider)
        assertEquals(2, config.model.numThreads)
        assertEquals(1, config.maxNumSentences)
        assertEquals(0.2f, config.silenceScale)
    }
}
