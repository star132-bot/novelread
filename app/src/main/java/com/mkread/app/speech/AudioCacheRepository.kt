package com.mkread.app.speech

import com.mkread.app.core.database.AudioCacheEntity
import java.io.File

data class AudioCacheCommit(
    val cacheKey: String,
    val bookId: String,
    val chapterId: String,
    val sentenceStart: Int,
    val sentenceEnd: Int,
    val normalizedTextSha256: String,
    val voicePackageSha256: String,
    val styleId: String,
    val qualityId: String,
    val generationVersion: Int = 1,
    val lastAccessedAt: Long,
    val protectedUntil: Long,
)

data class CachedAudio(
    val entity: AudioCacheEntity,
    val file: File,
    val sampleCount: Int,
)

interface AudioCacheRepository {
    suspend fun find(cacheKey: String, accessedAt: Long): CachedAudio?

    suspend fun commit(commit: AudioCacheCommit, partialFile: File): CachedAudio

    suspend fun touch(cacheKey: String, accessedAt: Long): Boolean

    suspend fun protect(cacheKeys: Set<String>, protectedUntil: Long): Int

    suspend fun invalidateChapter(chapterId: String): Int

    suspend fun removeInvalid(): Int

    suspend fun evictToBudget(maxBytes: Long, now: Long): Long

    suspend fun clearGeneratedAudio(): Int
}
