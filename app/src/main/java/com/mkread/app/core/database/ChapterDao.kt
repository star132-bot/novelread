package com.mkread.app.core.database

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE id = :chapterId LIMIT 1")
    suspend fun getById(chapterId: String): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE book_id = :bookId ORDER BY ordinal ASC")
    suspend fun getByBookId(bookId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE book_id = :bookId ORDER BY ordinal ASC")
    fun observeByBookId(bookId: String): Flow<List<ChapterEntity>>

    @Query(
        """
        UPDATE chapters
        SET character_count = :characterCount, content_sha256 = :newContentSha256
        WHERE id = :chapterId AND content_sha256 = :expectedContentSha256
        """,
    )
    suspend fun updateContent(
        chapterId: String,
        expectedContentSha256: String,
        characterCount: Int,
        newContentSha256: String,
    ): Int
}
