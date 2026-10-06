package com.mkread.app.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Generates real speech with each installed engine; Matcha is always bundled, the rest are optional packs. */
@RunWith(AndroidJUnit4::class)
class SpeechEngineSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun matcha_isBundledAndSpeaks() = speaks(VoiceModel.MATCHA)

    @Test
    fun melo_speaksWhenInstalled() = speaks(VoiceModel.MELO)

    @Test
    fun kokoro_speaksWhenInstalled() = speaks(VoiceModel.KOKORO, speakerId = 3)

    @Test
    fun zipVoice_clonesWhenInstalled() {
        val promptDirectory = File(context.filesDir, "voices/builtin-dev/prompts")
        speaks(VoiceModel.ZIPVOICE) {
            VoiceReference(
                audioFile = File(promptDirectory, "neutral.wav"),
                transcript = File(promptDirectory, "neutral.txt").readText(Charsets.UTF_8).trim(),
            )
        }
    }

    private fun speaks(
        model: VoiceModel,
        speakerId: Int = 0,
        reference: () -> VoiceReference? = { null },
    ) = runBlocking {
        runCatching { SpeechAssetInstaller(context).install(prefixes = model.assetPrefixes) }
        assumeTrue("${model.id} pack is not installed", model.modelDirectory(context.filesDir).isDirectory)

        val output = File(context.cacheDir, "smoke/${model.id}.wav")
        output.parentFile?.mkdirs()
        output.delete()
        val result = SherpaSpeechEngine(context.filesDir).use { engine ->
            engine.generate(
                SpeechRequest(
                    text = "窗外下着小雨，她轻声说：我们回家吧。",
                    reference = reference(),
                    quality = SpeechQuality.FLUENT,
                    outputFile = output,
                    model = model,
                    speakerId = speakerId,
                ),
            ).getOrThrow()
        }

        assertEquals(output, result.file)
        assertEquals(model.sampleRate, result.sampleRate)
        val durationSeconds = result.sampleCount / result.sampleRate.toFloat()
        assertTrue("Generated speech is too short: $durationSeconds", durationSeconds >= 0.5f)
        assertTrue("Generated speech is too long: $durationSeconds", durationSeconds <= 30f)
        assertEquals(result.sampleCount, WaveValidator.requirePlayable(result.file))
        assertFalse(result.file.readBytes().isEmpty())
    }
}
