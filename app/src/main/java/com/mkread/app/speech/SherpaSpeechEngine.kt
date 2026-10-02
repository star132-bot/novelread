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

/**
 * Runs every offline narration model through sherpa-onnx on one dedicated thread.
 *
 * Only one model is resident at a time: requesting a different [VoiceModel] releases the
 * current native instance before loading the next, which keeps memory bounded on phones.
 */
class SherpaSpeechEngine(
    private val filesDir: File,
    private val numThreads: Int = VoiceModel.DEFAULT_THREADS,
) : SpeechEngine {
    private val stateLock = Any()
    private val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mkread-tts").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private var nativeTts: OfflineTts? = null
    private var loadedModel: VoiceModel? = null
    private var cachedReference: CachedReference? = null
    private var closed = false

    /** Kept for callers that warm up the engine; models load lazily on first use. */
    override suspend fun initialize(): Result<Unit> = onEngineThread { _ ->
        synchronized(stateLock) { check(!closed) { "Speech engine is closed" } }
    }

    /** Loads [model] ahead of the first sentence so playback starts sooner. */
    suspend fun preload(model: VoiceModel): Result<Unit> = onEngineThread { _ ->
        synchronized(stateLock) {
            check(!closed) { "Speech engine is closed" }
            ensureLoaded(model)
        }
    }

    override suspend fun generate(request: SpeechRequest): Result<SpeechResult> = onEngineThread { context ->
        synchronized(stateLock) {
            check(!closed) { "Speech engine is closed" }
            generateLocked(ensureLoaded(request.model), request, context)
        }
    }

    override fun close() {
        var releaseFailure: Throwable? = null
        val closeDispatcher = synchronized(stateLock) {
            if (closed) {
                false
            } else {
                closed = true
                releaseFailure = runCatching { releaseLocked() }.exceptionOrNull()
                true
            }
        }
        if (closeDispatcher) {
            dispatcher.close()
        }
        releaseFailure?.let { throw it }
    }

    private fun ensureLoaded(model: VoiceModel): OfflineTts {
        nativeTts?.takeIf { loadedModel == model }?.let { return it }
        releaseLocked()
        val created = OfflineTts(config = model.toConfig(filesDir, numThreads))
        try {
            require(created.sampleRate() == model.sampleRate) {
                "${model.id} sample rate must be ${model.sampleRate} Hz"
            }
        } catch (error: Throwable) {
            created.release()
            throw error
        }
        nativeTts = created
        loadedModel = model
        return created
    }

    private fun releaseLocked() {
        try {
            nativeTts?.release()
        } finally {
            nativeTts = null
            loadedModel = null
        }
    }

    private fun generateLocked(
        tts: OfflineTts,
        request: SpeechRequest,
        coroutineContext: CoroutineContext,
    ): SpeechResult {
        require(request.text.isNotBlank()) { "Speech text must not be blank" }
        val generationConfig = if (request.model.clonesVoices) {
            val reference = requireNotNull(request.reference) { "Voice cloning needs a reference recording" }
            require(reference.transcript.isNotBlank()) { "Reference transcript must not be blank" }
            GenerationConfig(
                referenceAudio = referenceSamples(reference),
                referenceSampleRate = reference.sampleRate,
                referenceText = reference.transcript,
                numSteps = request.quality.numSteps,
                extra = mapOf("min_char_in_sentence" to "10"),
            )
        } else {
            GenerationConfig(
                silenceScale = VoiceModel.SILENCE_SCALE,
                speed = 1f,
                sid = request.speakerId,
            )
        }

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
            require(generated.sampleRate == request.model.sampleRate) {
                "Generated speech sample rate must be ${request.model.sampleRate} Hz"
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

    /** Decoding the reference WAV for every sentence is wasted work, so keep the last one. */
    private fun referenceSamples(reference: VoiceReference): FloatArray {
        val file = reference.audioFile
        cachedReference?.takeIf { it.path == file.path && it.modified == file.lastModified() }
            ?.let { return it.samples }
        val wave = SherpaWaveReader.read(file)
        require(wave.sampleRate == reference.sampleRate) {
            "Reference sample rate does not match its WAV file"
        }
        cachedReference = CachedReference(file.path, file.lastModified(), wave.samples)
        return wave.samples
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

    private class CachedReference(val path: String, val modified: Long, val samples: FloatArray)
}
