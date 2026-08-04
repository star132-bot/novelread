package com.mkread.app.core.database

import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BookEntity::class, ChapterEntity::class, ReadingPositionEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class MkreadDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    abstract fun chapterDao(): ChapterDao

    abstract fun readingPositionDao(): ReadingPositionDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reading_positions` (
                        `bookId` TEXT NOT NULL,
                        `chapterId` TEXT NOT NULL,
                        `characterOffset` INTEGER NOT NULL,
                        `pageIndex` INTEGER NOT NULL,
                        `sentenceIndex` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`bookId`),
                        FOREIGN KEY(`bookId`) REFERENCES `books`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reading_positions_chapterId` " +
                        "ON `reading_positions` (`chapterId`)",
                )
            }
        }
    }
}
