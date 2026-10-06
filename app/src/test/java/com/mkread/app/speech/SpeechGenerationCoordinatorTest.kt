package com.mkread.app.speech

import com.mkread.app.core.database.AudioCacheEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpeechGenerationCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun currentAndNextThreeAreGeneratedInOrderThenHitCache() = runTest {
        val engine = FakeEngine()
        val cache = FakeCache(temporaryFolder.root)
        val coordinator = coordinator(engine, cache)
        val sentences = sentences(generationId = 1L, count = 6)

        val first = coordinator.prepare(sentences, currentIndex = 1, settings = settings())
        val second = coordinator.prepare(sentences, currentIndex = 1, settings = settings())

        assertEquals(listOf(1, 2, 3, 4), first.filterIsInstance<GenerationState.Ready>().map { it.sentence.index })
        assertEquals(4, engine.requests.size)
        assertTrue(first.filterIsInstance<GenerationState.Ready>().none { it.fromCache })
        assertTrue(second.filterIsInstance<GenerationState.Ready>().all { it.fromCache })
        assertEquals(1, engine.maxConcurrentCalls)
    }

    @Test
    fun newerQueueMakesCompletedOldNativeResultStaleAndUncommitted() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val engine = FakeEngine(firstGate = gate, firstEntered = entered)
        val cache = FakeCache(temporaryFolder.root)
        val coordinator = coordinator(engine, cache)
        val old = sentences(generationId = 10L, count = 1)
        val fresh = sentences(generationId = 11L, count = 1, chapterId = "chapter-new")

        val oldResult = async { coordinator.prepare(old, 0, settings()) }
        entered.await()
        val freshResult = async { coordinator.prepare(fresh, 0, settings()) }
        gate.complete(Unit)

        assertTrue(oldResult.await().isEmpty())
        assertEquals(11L, freshResult.await().filterIsInstance<GenerationState.Ready>().single().sentence.queueGenerationId)
        assertEquals(1, cache.commits.size)
        assertEquals("chapter-new", cache.commits.single().chapterId)
    }

    @Test
    fun firstFailureRetriesWithBuiltInNeutralAndFluentQuality() = runTest {
        val engine = FakeEngine(failAttempts = 1)
        val cache = FakeCache(temporaryFolder.root)
        val voiceProvider = FakeVoiceProvider()
        val coordinator = coordinator(engine, cache, voiceProvider)

        val result = coordinator.prepare(
            sentences(generationId = 2L, count = 1),
            currentIndex = 0,
            settings = settings(quality = SpeechQuality.HIGH, styleId = "joy"),
        )

        assertTrue(result.single() is GenerationState.Ready)
        assertEquals(listOf("selected", "builtin"), voiceProvider.resolutions)
        assertEquals(listOf(SpeechQuality.HIGH, SpeechQuality.FLUENT), engine.requests.map { it.quality })
    }

    @Test
    fun twoInvalidWaveAttemptsBlockOnTheSameSentence() = runTest {
        val engine = FakeEngine(writeInvalidWave = true)
        val cache = FakeCache(temporaryFolder.root)
        val result = coordinator(engine, cache).prepare(
            sentences(generationId = 3L, count = 2),
            currentIndex = 0,
            settings = settings(),
        )

        val blocked = result.single() as GenerationState.Blocked
        assertEquals(0, blocked.sentence.index)
        assertTrue(blocked.retryable)
        assertEquals(2, engine.requests.size)
        assertTrue(cache.commits.isEmpty())
    }

    @Test
    fun storageFailureStopsAtCurrentSentenceWithoutPrefetching() = runTest {
        val engine = FakeEngine()
        val cache = FakeCache(temporaryFolder.root, failCommitWithStorage = true)
        val result = coordinator(engine, cache).prepare(
            sentences(generationId = 4L, count = 4),
            currentIndex = 0,
            settings = settings(),
        )

        val storageLow = result.single() as GenerationState.StorageLow
        assertEquals(0, storageLow.sentence.index)
        assertEquals(1, engine.requests.size)
    }

    private fun coordinator(
        engine: FakeEngine,
        cache: FakeCache,
        voiceProvider: FakeVoiceProvider = FakeVoiceProvider(),
    ) = SpeechGenerationCoordinator(
        engine = engine,
        cache = cache,
        normalizer = TextNormalizer(PronunciationOverrides.empty()),
        voiceProvider = voiceProvider,
        cacheDirectory = temporaryFolder.root,
        availableBytes = { 2L * 1024 * 1024 * 1024 },
        nowMillis = { 1_000L },
    )

    private fun settings(
        quality: SpeechQuality = SpeechQuality.FLUENT,
        styleId: String = "neutral",
    ) = NarrationSettings(
        voiceId = "selected",
        styleId = styleId,
        quality = quality,
    )

    private fun sentences(
        generationId: Long,
        count: Int,
        chapterId: String = "chapter-1",
    ) = List(count) { index ->
        NarrationSentence(
            bookId = "book-1",
            bookContentSha256 = "book-hash",
            chapterId = chapterId,
            index = index,
            start = index * 10,
            end = index * 10 + 9,
            rawText = "第 $index 句。",
            bookTitle = "测试书",
            chapterTitle = "第一章",
            queueGenerationId = generationId,
        )
    }

    private class FakeVoiceProvider : NarrationVoiceProvider {
        val resolutions = mutableListOf<String>()
        private val reference = VoiceReference(File("reference.wav"), "参考文本")

        override suspend fun resolve(voiceId: String?, styleId: String): Result<NarrationVoice> = runCatching {
            resolutions += "selected"
            NarrationVoice("selected", "a".repeat(64), styleId, reference)
        }

        override suspend fun builtInNeutral(): Result<NarrationVoice> = runCatching {
            resolutions += "builtin"
            NarrationVoice("builtin", "b".repeat(64), "neutral", reference)
        }
    }

    private class FakeEngine(
        private val failAttempts: Int = 0,
        private val writeInvalidWave: Boolean = false,
        private val firstGate: CompletableDeferred<Unit>? = null,
        private val firstEntered: CompletableDeferred<Unit>? = null,
    ) : SpeechEngine {
        val requests = mutableListOf<SpeechRequest>()
        var maxConcurrentCalls = 0
        private var concurrentCalls = 0
        private var attempts = 0

        override suspend fun initialize(): Result<Unit> = Result.success(Unit)

        override suspend fun generate(request: SpeechRequest): Result<SpeechResult> {
            requests += request
            concurrentCalls += 1
            maxConcurrentCalls = maxOf(maxConcurrentCalls, concurrentCalls)
            try {
                if (attempts++ == 0) {
                    firstEntered?.complete(Unit)
                    firstGate?.await()
                }
                if (attempts <= failAttempts) return Result.failure(IllegalStateException("generation failed"))
                request.outputFile.parentFile?.mkdirs()
                request.outputFile.writeBytes(if (writeInvalidWave) byteArrayOf(1, 2, 3) else playableWave())
                return Result.success(SpeechResult(request.outputFile, 24_000, 1, 1L))
            } finally {
                concurrentCalls -= 1
            }
        }

        override fun close() = Unit
    }

    private class FakeCache(
        private val root: File,
        private val failCommitWithStorage: Boolean = false,
    ) : AudioCacheRepository {
        private val entries = linkedMapOf<String, CachedAudio>()
        val commits = mutableListOf<AudioCacheCommit>()

        override suspend fun find(cacheKey: String, accessedAt: Long): CachedAudio? = entries[cacheKey]

        override suspend fun commit(commit: AudioCacheCommit, partialFile: File): CachedAudio {
            if (failCommitWithStorage) throw IOException("ENOSPC")
            val sampleCount = WaveValidator.requirePlayable(partialFile)
            val target = File(root, "stored/${commit.cacheKey}.wav")
            target.parentFile!!.mkdirs()
            partialFile.copyTo(target, overwrite = true)
            partialFile.delete()
            val entity = AudioCacheEntity(
                cacheKey = commit.cacheKey,
                bookId = commit.bookId,
                chapterId = commit.chapterId,
                sentenceStart = commit.sentenceStart,
                sentenceEnd = commit.sentenceEnd,
                normalizedTextSha256 = commit.normalizedTextSha256,
                voicePackageSha256 = commit.voicePackageSha256,
                styleId = commit.styleId,
                qualityId = commit.qualityId,
                generationVersion = commit.generationVersion,
                relativeWavePath = target.name,
                byteSize = target.length(),
                lastAccessedAt = commit.lastAccessedAt,
                protectedUntil = commit.protectedUntil,
            )
            return CachedAudio(entity, target, sampleCount).also {
                commits += commit
                entries[commit.cacheKey] = it
            }
        }

        override suspend fun touch(cacheKey: String, accessedAt: Long) = entries.containsKey(cacheKey)
        override suspend fun protect(cacheKeys: Set<String>, protectedUntil: Long) = cacheKeys.count(entries::containsKey)
        override suspend fun invalidateChapter(chapterId: String) = 0
        override suspend fun removeInvalid() = 0
        override suspend fun evictToBudget(maxBytes: Long, now: Long) = 0L
        override suspend fun clearGeneratedAudio() = 0
    }

    private companion object {
        fun playableWave(): ByteArray = ByteArrayOutputStream().apply {
            write("RIFF".toByteArray())
            writeLittleEndian32(38)
            write("WAVEfmt ".toByteArray())
            writeLittleEndian32(16)
            writeLittleEndian16(1)
            writeLittleEndian16(1)
            writeLittleEndian32(24_000)
            writeLittleEndian32(48_000)
            writeLittleEndian16(2)
            writeLittleEndian16(16)
            write("data".toByteArray())
            writeLittleEndian32(2)
            writeLittleEndian16(0)
        }.toByteArray()

        fun ByteArrayOutputStream.writeLittleEndian16(value: Int) {
            write(value and 0xff)
            write(value ushr 8 and 0xff)
        }

        fun ByteArrayOutputStream.writeLittleEndian32(value: Int) {
            writeLittleEndian16(value)
            writeLittleEndian16(value ushr 16)
        }
    }
}
