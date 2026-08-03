package com.mkread.app.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ZipVoiceSmokeTest {
    @Test
    fun generatesPlayableChineseSpeechOnX8664() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SpeechAssetInstaller(context).install()

        val promptDirectory = File(context.filesDir, "voices/builtin-dev/prompts")
        val reference = VoiceReference(
            audioFile = File(promptDirectory, "neutral.wav"),
            transcript = File(promptDirectory, "neutral.txt").readText(Charsets.UTF_8).trim(),
        )
        val output = File(context.cacheDir, "spike/chinese.wav")
        output.parentFile?.mkdirs()
        output.delete()
        File(output.parentFile, "${output.name}.partial").delete()

        val result = ZipVoiceSpeechEngine(ZipVoicePaths.fromFilesDir(context.filesDir)).use { engine ->
            engine.initialize().getOrThrow()
            engine.initialize().getOrThrow()
            engine.generate(
                SpeechRequest(
                    text = "窗外下着小雨，她轻声说：“我们回家吧。”",
                    reference = reference,
                    quality = SpeechQuality.FLUENT,
                    outputFile = output,
                ),
            ).getOrThrow()
        }

        assertEquals(output, result.file)
        assertEquals(24_000, result.sampleRate)
        assertTrue(result.file.length() > 44L)
        val durationSeconds = result.sampleCount / result.sampleRate.toFloat()
        assertTrue("Generated speech is too short: $durationSeconds", durationSeconds >= 0.5f)
        assertTrue("Generated speech is too long: $durationSeconds", durationSeconds <= 30f)

        val wave = SherpaWaveReader.read(result.file)
        assertEquals(result.sampleCount, wave.samples.size)
        assertFalse(wave.samples.any { it.isNaN() })
    }
}
