package com.mkread.app.core.database

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE book_id = :bookId ORDER BY ordinal ASC")
    suspend fun getByBookId(bookId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE book_id = :bookId ORDER BY ordinal ASC")
    fun observeByBookId(bookId: String): Flow<List<ChapterEntity>>
}
