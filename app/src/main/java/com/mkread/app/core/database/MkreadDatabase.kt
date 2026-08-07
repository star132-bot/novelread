package com.mkread.app.core.database

import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        ReadingPositionEntity::class,
        AudioCacheEntity::class,
        ShelfFolderEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class MkreadDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    abstract fun chapterDao(): ChapterDao

    abstract fun readingPositionDao(): ReadingPositionDao

    abstract fun audioCacheDao(): AudioCacheDao

    abstract fun shelfFolderDao(): ShelfFolderDao

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

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `audio_cache` (
                        `cacheKey` TEXT NOT NULL,
                        `bookId` TEXT NOT NULL,
                        `chapterId` TEXT NOT NULL,
                        `sentenceStart` INTEGER NOT NULL,
                        `sentenceEnd` INTEGER NOT NULL,
                        `normalizedTextSha256` TEXT NOT NULL,
                        `voicePackageSha256` TEXT NOT NULL,
                        `styleId` TEXT NOT NULL,
                        `qualityId` TEXT NOT NULL,
                        `generationVersion` INTEGER NOT NULL,
                        `relativeWavePath` TEXT NOT NULL,
                        `byteSize` INTEGER NOT NULL,
                        `lastAccessedAt` INTEGER NOT NULL,
                        `protectedUntil` INTEGER NOT NULL,
                        PRIMARY KEY(`cacheKey`),
                        FOREIGN KEY(`bookId`) REFERENCES `books`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_audio_cache_bookId` " +
                        "ON `audio_cache` (`bookId`)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_audio_cache_chapterId` " +
                        "ON `audio_cache` (`chapterId`)",
                )
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `shelf_folders` (
                        `id` TEXT NOT NULL,
                        `name` TEXT COLLATE NOCASE NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_shelf_folders_name` " +
                        "ON `shelf_folders` (`name`)",
                )
                database.execSQL("ALTER TABLE `books` ADD COLUMN `folder_id` TEXT")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_books_folder_id` ON `books` (`folder_id`)",
                )
            }
        }
    }
}
