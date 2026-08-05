package com.mkread.app.speech

import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SpeechGenerationCoordinator(
    private val engine: SpeechEngine,
    private val cache: AudioCacheRepository,
    private val normalizer: TextNormalizer,
    private val voiceProvider: NarrationVoiceProvider,
    private val cacheDirectory: File,
    private val availableBytes: () -> Long,
    private val nowMillis: () -> Long,
) {
    private val generationMutex = Mutex()
    private val activationLock = Any()
    private var activeGenerationId = -1L
    private var engineInitialized = false

    suspend fun prepare(
        queue: List<NarrationSentence>,
        currentIndex: Int,
        settings: NarrationSettings,
    ): List<GenerationState> {
        require(queue.isNotEmpty()) { "Narration queue must not be empty" }
        require(currentIndex in queue.indices) { "Current narration index is outside the queue" }
        val generationId = queue[currentIndex].queueGenerationId
        require(queue.all { it.queueGenerationId == generationId }) {
            "Narration queue must use one generation id"
        }
        if (!activate(generationId)) return emptyList()

        val results = mutableListOf<GenerationState>()
        val requested = queue.drop(currentIndex).take(PREFETCH_COUNT + 1)
        for (sentence in requested) {
            if (!isActive(generationId)) break
            val state = prepareSentence(sentence, settings)
            if (state == null) break
            results += state
            if (state is GenerationState.Blocked || state is GenerationState.StorageLow) break
        }
        return results
    }

    private suspend fun prepareSentence(
        sentence: NarrationSentence,
        settings: NarrationSettings,
    ): GenerationState? {
        val normalized = try {
            normalizer.normalize(sentence.rawText)
        } catch (failure: Exception) {
            return GenerationState.Blocked(sentence, failure.safeMessage(), retryable = false)
        }
        val primaryVoice = voiceProvider.resolve(settings.voiceId, settings.styleId).getOrNull()
        if (primaryVoice != null) {
            when (val result = attempt(sentence, normalized, primaryVoice, settings.quality)) {
                is Attempt.Ready -> return result.toState(sentence)
                is Attempt.Stale -> return null
                is Attempt.StorageLow -> return GenerationState.StorageLow(sentence, result.reason)
                is Attempt.Failed -> Unit
            }
        }
        if (!isActive(sentence.queueGenerationId)) return null

        val fallback = voiceProvider.builtInNeutral().getOrElse { failure ->
            return GenerationState.Blocked(sentence, failure.safeMessage(), retryable = true)
        }
        return when (val result = attempt(sentence, normalized, fallback, SpeechQuality.FLUENT)) {
            is Attempt.Ready -> result.toState(sentence)
            is Attempt.Stale -> null
            is Attempt.StorageLow -> GenerationState.StorageLow(sentence, result.reason)
            is Attempt.Failed -> GenerationState.Blocked(sentence, result.reason, retryable = true)
        }
    }

    private suspend fun attempt(
        sentence: NarrationSentence,
        normalized: NormalizedText,
        voice: NarrationVoice,
        quality: SpeechQuality,
    ): Attempt {
        val key = AudioCacheKey.calculate(
            AudioCacheKeyInput(
                bookContentSha256 = sentence.bookContentSha256,
                chapterId = sentence.chapterId,
                sentenceStart = sentence.start,
                sentenceEnd = sentence.end,
                normalizedTextSha256 = normalized.sha256,
                voicePackageSha256 = voice.packageSha256,
                styleId = voice.styleId,
                qualityId = quality.cacheId,
            ),
        )
        cache.find(key, nowMillis())?.let { cached ->
            protectAndTrim(setOf(key))
            return Attempt.Ready(cached.file, key, fromCache = true)
        }

        return generationMutex.withLock {
            if (!isActive(sentence.queueGenerationId)) return@withLock Attempt.Stale
            cache.find(key, nowMillis())?.let { cached ->
                protectAndTrim(setOf(key))
                return@withLock Attempt.Ready(cached.file, key, fromCache = true)
            }
            if (!engineInitialized) {
                val initialized = engine.initialize()
                if (initialized.isFailure) {
                    return@withLock Attempt.Failed(initialized.exceptionOrNull().safeMessage())
                }
                engineInitialized = true
            }

            val partial = partialFile(sentence.queueGenerationId, key)
            partial.parentFile?.mkdirs()
            partial.delete()
            val generated = withContext(NonCancellable) {
                engine.generate(
                    SpeechRequest(
                        text = normalized.value,
                        reference = voice.reference,
                        quality = quality,
                        outputFile = partial,
                    ),
                )
            }
            if (!isActive(sentence.queueGenerationId)) {
                partial.delete()
                return@withLock Attempt.Stale
            }
            if (generated.isFailure) {
                partial.delete()
                return@withLock Attempt.Failed(generated.exceptionOrNull().safeMessage())
            }

            try {
                val now = nowMillis()
                val cached = cache.commit(
                    AudioCacheCommit(
                        cacheKey = key,
                        bookId = sentence.bookId,
                        chapterId = sentence.chapterId,
                        sentenceStart = sentence.start,
                        sentenceEnd = sentence.end,
                        normalizedTextSha256 = normalized.sha256,
                        voicePackageSha256 = voice.packageSha256,
                        styleId = voice.styleId,
                        qualityId = quality.cacheId,
                        lastAccessedAt = now,
                        protectedUntil = now + PROTECTION_MILLIS,
                    ),
                    partial,
                )
                protectAndTrim(setOf(key))
                Attempt.Ready(cached.file, key, fromCache = false)
            } catch (failure: IOException) {
                partial.delete()
                Attempt.StorageLow(failure.safeMessage())
            } catch (failure: Exception) {
                partial.delete()
                Attempt.Failed(failure.safeMessage())
            }
        }
    }

    private suspend fun protectAndTrim(keys: Set<String>) {
        val now = nowMillis()
        cache.protect(keys, now + PROTECTION_MILLIS)
        cache.evictToBudget(cacheBudget(availableBytes().coerceAtLeast(0L)), now)
    }

    private fun activate(generationId: Long): Boolean = synchronized(activationLock) {
        if (generationId < activeGenerationId) return@synchronized false
        activeGenerationId = generationId
        true
    }

    private fun isActive(generationId: Long): Boolean = synchronized(activationLock) {
        activeGenerationId == generationId
    }

    private fun partialFile(generationId: Long, key: String): File =
        File(cacheDirectory, "tts-staging/$generationId-$key.wav.partial")

    private fun cacheBudget(available: Long): Long {
        val proportional = minOf(MAX_CACHE_BYTES, available / 10L)
        return if (available >= MIN_CACHE_BYTES) maxOf(MIN_CACHE_BYTES, proportional) else proportional
    }

    private sealed interface Attempt {
        data class Ready(val file: File, val cacheKey: String, val fromCache: Boolean) : Attempt
        data class Failed(val reason: String) : Attempt
        data class StorageLow(val reason: String) : Attempt
        data object Stale : Attempt
    }

    private fun Attempt.Ready.toState(sentence: NarrationSentence) = GenerationState.Ready(
        sentence = sentence,
        file = file,
        cacheKey = cacheKey,
        fromCache = fromCache,
    )

    private val SpeechQuality.cacheId: String
        get() = name.lowercase(Locale.ROOT)

    private fun Throwable?.safeMessage(): String =
        this?.message?.takeIf(String::isNotBlank) ?: "离线语音生成失败"

    private companion object {
        const val PREFETCH_COUNT = 3
        const val PROTECTION_MILLIS = 10 * 60 * 1_000L
        const val MIN_CACHE_BYTES = 100L * 1024 * 1024
        const val MAX_CACHE_BYTES = 1024L * 1024 * 1024
    }
}
