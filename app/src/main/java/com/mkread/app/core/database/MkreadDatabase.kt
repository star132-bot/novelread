package com.mkread.app.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [BookEntity::class, ChapterEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MkreadDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    abstract fun chapterDao(): ChapterDao
}
