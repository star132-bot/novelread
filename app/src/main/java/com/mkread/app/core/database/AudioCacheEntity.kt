package com.mkread.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(
    tableName = "audio_cache",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["chapterId"]),
    ],
)
data class AudioCacheEntity(
    @PrimaryKey
    val cacheKey: String,
    val bookId: String,
    val chapterId: String,
    val sentenceStart: Int,
    val sentenceEnd: Int,
    val normalizedTextSha256: String,
    val voicePackageSha256: String,
    val styleId: String,
    val qualityId: String,
    val generationVersion: Int,
    val relativeWavePath: String,
    val byteSize: Long,
    val lastAccessedAt: Long,
    val protectedUntil: Long,
)

@Dao
interface AudioCacheDao {
    @Query("SELECT * FROM audio_cache WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun getByCacheKey(cacheKey: String): AudioCacheEntity?

    @Query("SELECT * FROM audio_cache ORDER BY cacheKey ASC")
    suspend fun getAll(): List<AudioCacheEntity>

    @Query("SELECT * FROM audio_cache WHERE chapterId = :chapterId ORDER BY cacheKey ASC")
    suspend fun getByChapterId(chapterId: String): List<AudioCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AudioCacheEntity)

    @Query("UPDATE audio_cache SET lastAccessedAt = :accessedAt WHERE cacheKey = :cacheKey")
    suspend fun touch(cacheKey: String, accessedAt: Long): Int

    @Query(
        """
        UPDATE audio_cache
        SET protectedUntil = MAX(protectedUntil, :protectedUntil)
        WHERE cacheKey IN (:cacheKeys)
        """,
    )
    suspend fun protect(cacheKeys: List<String>, protectedUntil: Long): Int

    @Query("DELETE FROM audio_cache WHERE cacheKey = :cacheKey")
    suspend fun deleteByCacheKey(cacheKey: String): Int

    @Query("DELETE FROM audio_cache")
    suspend fun deleteAll(): Int
}
