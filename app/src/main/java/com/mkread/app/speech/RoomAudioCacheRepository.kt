package com.mkread.app.speech

import com.mkread.app.core.database.AudioCacheDao
import com.mkread.app.core.database.AudioCacheEntity
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RoomAudioCacheRepository(
    private val dao: AudioCacheDao,
    cacheDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AudioCacheRepository {
    private val cacheRoot = cacheDir.toPath().toAbsolutePath().normalize()
    private val ttsRoot = cacheRoot.resolve(TTS_DIRECTORY)
    private val lock = Mutex()

    override suspend fun find(cacheKey: String, accessedAt: Long): CachedAudio? = onIo {
        lock.withLock {
            requireCacheKey(cacheKey)
            val entity = dao.getByCacheKey(cacheKey) ?: return@withLock null
            val cached = validate(entity)
            if (cached == null) {
                remove(entity)
                return@withLock null
            }
            dao.touch(cacheKey, accessedAt)
            cached.copy(entity = entity.copy(lastAccessedAt = accessedAt))
        }
    }

    override suspend fun commit(
        commit: AudioCacheCommit,
        partialFile: File,
    ): CachedAudio = onIo {
        lock.withLock {
            requireCacheKey(commit.cacheKey)
            require(commit.sentenceStart >= 0 && commit.sentenceEnd > commit.sentenceStart) {
                "Sentence offsets must describe a non-empty range"
            }
            val partial = partialFile.toPath().toAbsolutePath().normalize()
            require(partial.fileName.toString().endsWith(PARTIAL_SUFFIX)) {
                "Audio cache input must be a .partial file"
            }
            require(partial.startsWith(cacheRoot) && Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS)) {
                "Audio cache partial file must be inside the private cache directory"
            }
            val sampleCount = WaveValidator.requirePlayable(partial.toFile())
            val target = wavePath(commit.cacheKey)
            Files.createDirectories(requireNotNull(target.parent))
            Files.move(
                partial,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )

            val entity = commit.toEntity(
                relativeWavePath = relativeWavePath(commit.cacheKey),
                byteSize = Files.size(target),
            )
            try {
                dao.insert(entity)
            } catch (failure: Throwable) {
                Files.deleteIfExists(target)
                throw failure
            }
            CachedAudio(entity = entity, file = target.toFile(), sampleCount = sampleCount)
        }
    }

    override suspend fun touch(cacheKey: String, accessedAt: Long): Boolean = onIo {
        lock.withLock {
            requireCacheKey(cacheKey)
            dao.touch(cacheKey, accessedAt) > 0
        }
    }

    override suspend fun protect(cacheKeys: Set<String>, protectedUntil: Long): Int = onIo {
        lock.withLock {
            if (cacheKeys.isEmpty()) return@withLock 0
            cacheKeys.forEach(::requireCacheKey)
            dao.protect(cacheKeys.sorted(), protectedUntil)
        }
    }

    override suspend fun invalidateChapter(chapterId: String): Int = onIo {
        lock.withLock {
            var deletedRows = 0
            for (entity in dao.getByChapterId(chapterId)) {
                deletedRows += remove(entity)
            }
            deletedRows
        }
    }

    override suspend fun removeInvalid(): Int = onIo {
        lock.withLock {
            var deletedRows = 0
            for (entity in dao.getAll()) {
                if (validate(entity) == null) {
                    deletedRows += remove(entity)
                }
            }
            deletedRows
        }
    }

    override suspend fun evictToBudget(maxBytes: Long, now: Long): Long = onIo {
        lock.withLock {
            require(maxBytes >= 0L) { "Audio cache budget cannot be negative" }
            val entities = dao.getAll()
            var retainedBytes = entities.sumOf { entity -> entity.byteSize }
            var removedBytes = 0L
            val evictionCandidates = entities.asSequence()
                .filter { entity -> entity.protectedUntil <= now }
                .sortedWith(compareBy(AudioCacheEntity::lastAccessedAt, AudioCacheEntity::cacheKey))
            for (entity in evictionCandidates) {
                if (retainedBytes <= maxBytes) break
                if (remove(entity) > 0) {
                    retainedBytes -= entity.byteSize
                    removedBytes += entity.byteSize
                }
            }
            removedBytes
        }
    }

    override suspend fun clearGeneratedAudio(): Int = onIo {
        lock.withLock {
            val deletedRows = dao.deleteAll()
            deleteTree(ttsRoot)
            deletedRows
        }
    }

    private suspend fun remove(entity: AudioCacheEntity): Int {
        expectedPath(entity)?.let { path -> runCatching { Files.deleteIfExists(path) } }
        return dao.deleteByCacheKey(entity.cacheKey)
    }

    private fun validate(entity: AudioCacheEntity): CachedAudio? {
        val path = expectedPath(entity) ?: return null
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        val samples = try {
            WaveValidator.requirePlayable(path.toFile())
        } catch (_: Exception) {
            return null
        }
        return CachedAudio(entity = entity, file = path.toFile(), sampleCount = samples)
    }

    private fun expectedPath(entity: AudioCacheEntity): Path? {
        if (!CACHE_KEY.matches(entity.cacheKey)) return null
        if (entity.relativeWavePath != relativeWavePath(entity.cacheKey)) return null
        return wavePath(entity.cacheKey)
    }

    private fun wavePath(cacheKey: String): Path =
        ttsRoot.resolve(cacheKey.take(PREFIX_LENGTH)).resolve("$cacheKey.wav")

    private fun relativeWavePath(cacheKey: String): String =
        "$TTS_DIRECTORY/${cacheKey.take(PREFIX_LENGTH)}/$cacheKey.wav"

    private fun requireCacheKey(cacheKey: String) {
        require(CACHE_KEY.matches(cacheKey)) { "Audio cache key must be lowercase SHA-256" }
    }

    private fun AudioCacheCommit.toEntity(
        relativeWavePath: String,
        byteSize: Long,
    ) = AudioCacheEntity(
        cacheKey = cacheKey,
        bookId = bookId,
        chapterId = chapterId,
        sentenceStart = sentenceStart,
        sentenceEnd = sentenceEnd,
        normalizedTextSha256 = normalizedTextSha256,
        voicePackageSha256 = voicePackageSha256,
        styleId = styleId,
        qualityId = qualityId,
        generationVersion = generationVersion,
        relativeWavePath = relativeWavePath,
        byteSize = byteSize,
        lastAccessedAt = lastAccessedAt,
        protectedUntil = protectedUntil,
    )

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, failure: java.io.IOException?): FileVisitResult {
                    failure?.let { throw it }
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private suspend fun <T> onIo(block: suspend () -> T): T = withContext(ioDispatcher) { block() }

    private companion object {
        const val TTS_DIRECTORY = "tts"
        const val PARTIAL_SUFFIX = ".partial"
        const val PREFIX_LENGTH = 2
        val CACHE_KEY = Regex("[0-9a-f]{64}")
    }
}
