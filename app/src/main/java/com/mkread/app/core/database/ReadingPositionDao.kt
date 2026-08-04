package com.mkread.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingPositionDao {
    @Query("SELECT * FROM reading_positions WHERE bookId = :bookId LIMIT 1")
    suspend fun getByBookId(bookId: String): ReadingPositionEntity?

    @Query("SELECT * FROM reading_positions WHERE bookId = :bookId LIMIT 1")
    fun observeByBookId(bookId: String): Flow<ReadingPositionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(position: ReadingPositionEntity)

    @Query("DELETE FROM reading_positions WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: String)
}
