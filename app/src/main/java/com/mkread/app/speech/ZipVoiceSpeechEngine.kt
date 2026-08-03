package com.mkread.app.speech

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ZipVoiceSpeechEngine(
    private val paths: ZipVoicePaths,
) : SpeechEngine {
    private val stateLock = Any()
    private val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mkread-zipvoice").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private var nativeTts: OfflineTts? = null
    private var closed = false

    override suspend fun initialize(): Result<Unit> = onEngineThread { _ ->
        synchronized(stateLock) {
            check(!closed) { "Speech engine is closed" }
            if (nativeTts == null) {
                val created = OfflineTts(config = paths.toConfig())
                try {
                    require(created.sampleRate() == EXPECTED_SAMPLE_RATE) {
                        "ZipVoice sample rate must be 24000 Hz"
                    }
                    nativeTts = created
                } catch (error: Throwable) {
                    created.release()
                    throw error
                }
            }
        }
    }

    override suspend fun generate(request: SpeechRequest): Result<SpeechResult> = onEngineThread { context ->
        synchronized(stateLock) {
            check(!closed) { "Speech engine is closed" }
            val tts = checkNotNull(nativeTts) { "Speech engine is not initialized" }
            generateLocked(tts, request, context)
        }
    }

    override fun close() {
        var releaseFailure: Throwable? = null
        val closeDispatcher = synchronized(stateLock) {
            if (closed) {
                false
            } else {
                closed = true
                try {
                    nativeTts?.release()
                } catch (error: Throwable) {
                    releaseFailure = error
                } finally {
                    nativeTts = null
                }
                true
            }
        }
        if (closeDispatcher) {
            dispatcher.close()
        }
        releaseFailure?.let { throw it }
    }

    private fun generateLocked(
        tts: OfflineTts,
        request: SpeechRequest,
        coroutineContext: CoroutineContext,
    ): SpeechResult {
        require(request.text.isNotBlank()) { "Speech text must not be blank" }
        require(request.reference.transcript.isNotBlank()) { "Reference transcript must not be blank" }

        val reference = SherpaWaveReader.read(request.reference.audioFile)
        require(reference.sampleRate == request.reference.sampleRate) {
            "Reference sample rate does not match its WAV file"
        }
        val generationConfig = GenerationConfig(
            referenceAudio = reference.samples,
            referenceSampleRate = request.reference.sampleRate,
            referenceText = request.reference.transcript,
            numSteps = request.quality.numSteps,
            extra = mapOf("min_char_in_sentence" to "10"),
        )

        val outputFile = request.outputFile.absoluteFile
        val outputDirectory = checkNotNull(outputFile.parentFile) { "Output file has no parent directory" }
        check(outputDirectory.isDirectory || outputDirectory.mkdirs()) {
            "Unable to create speech output directory"
        }
        val partialFile = File(outputDirectory, "${outputFile.name}.partial")
        check(!partialFile.exists() || partialFile.delete()) { "Unable to clear stale partial speech output" }

        return try {
            coroutineContext.ensureActive()
            val startedNanos = System.nanoTime()
            val generated = tts.generateWithConfig(request.text, generationConfig)
            require(generated.sampleRate == EXPECTED_SAMPLE_RATE) {
                "Generated speech sample rate must be 24000 Hz"
            }
            require(generated.samples.isNotEmpty()) { "Generated speech is empty" }
            require(generated.samples.all { it.isFinite() }) { "Generated speech contains non-finite samples" }
            check(generated.save(partialFile.path)) { "Unable to save generated speech" }

            val sampleCount = WaveValidator.requirePlayable(partialFile)
            require(sampleCount == generated.samples.size) { "Saved speech sample count changed" }
            coroutineContext.ensureActive()
            Files.move(
                partialFile.toPath(),
                outputFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            SpeechResult(
                file = outputFile,
                sampleRate = generated.sampleRate,
                sampleCount = sampleCount,
                generationMillis = (System.nanoTime() - startedNanos) / 1_000_000L,
            )
        } catch (error: Throwable) {
            partialFile.delete()
            throw error
        }
    }

    private suspend fun <T> onEngineThread(block: (CoroutineContext) -> T): Result<T> = try {
        withContext(dispatcher) {
            val context = currentCoroutineContext()
            context.ensureActive()
            Result.success(block(context))
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private companion object {
        const val EXPECTED_SAMPLE_RATE = 24_000
    }
}
