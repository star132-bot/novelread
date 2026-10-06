package com.mkread.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ShelfFolderDao {
    @Query("SELECT * FROM shelf_folders ORDER BY name COLLATE NOCASE ASC, id ASC")
    abstract fun observeAll(): Flow<List<ShelfFolderEntity>>

    @Query("SELECT * FROM shelf_folders WHERE name = :name COLLATE NOCASE LIMIT 1")
    abstract suspend fun getByName(name: String): ShelfFolderEntity?

    @Query("SELECT * FROM shelf_folders WHERE id = :folderId LIMIT 1")
    abstract suspend fun getById(folderId: String): ShelfFolderEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(folder: ShelfFolderEntity)

    @Query("UPDATE shelf_folders SET name = :name WHERE id = :folderId")
    abstract suspend fun rename(folderId: String, name: String): Int

    @Query(
        """
        UPDATE books
        SET folder_id = :folderId
        WHERE id = :bookId
          AND (:folderId IS NULL OR EXISTS(
              SELECT 1 FROM shelf_folders WHERE id = :folderId
          ))
        """,
    )
    abstract suspend fun moveBook(bookId: String, folderId: String?): Int

    @Query("UPDATE books SET folder_id = NULL WHERE folder_id = :folderId")
    abstract suspend fun clearBooks(folderId: String): Int

    @Query("DELETE FROM shelf_folders WHERE id = :folderId")
    abstract suspend fun deleteById(folderId: String): Int

    @Transaction
    open suspend fun deleteAndUnfileBooks(folderId: String): Boolean {
        clearBooks(folderId)
        return deleteById(folderId) > 0
    }
}
