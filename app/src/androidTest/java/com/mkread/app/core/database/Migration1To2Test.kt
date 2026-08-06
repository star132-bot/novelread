package com.mkread.app.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration1To2Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MkreadDatabase::class.java,
    )

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var database: MkreadDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migrationPreservesPhase2RowsAndCascadesPositions() = runBlocking {
        helper.createDatabase(TEST_DATABASE, 1).apply {
            execSQL(
                """
                INSERT INTO books(
                    id, title, author, source_type, source_sha256,
                    cover_relative_path, imported_at, modified_at, last_opened_at
                ) VALUES ('book-1', '旧书', NULL, 'TXT', 'source-hash', NULL, 10, 20, NULL)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO chapters(
                    id, book_id, ordinal, title, relative_path, character_count, content_sha256
                ) VALUES ('chapter-1', 'book-1', 0, '第一章', 'chapter-0000.txt', 120, 'chapter-hash')
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            2,
            true,
            MkreadDatabase.MIGRATION_1_2,
        )
        assertEquals(1, migrated.query("SELECT COUNT(*) FROM books").use { it.moveToFirst(); it.getInt(0) })
        assertEquals(1, migrated.query("SELECT COUNT(*) FROM chapters").use { it.moveToFirst(); it.getInt(0) })
        migrated.close()

        database = Room.databaseBuilder(context, MkreadDatabase::class.java, TEST_DATABASE)
            .addMigrations(MkreadDatabase.MIGRATION_1_2, MkreadDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        database!!.readingPositionDao().upsert(
            ReadingPositionEntity(
                bookId = "book-1",
                chapterId = "chapter-1",
                characterOffset = 42,
                pageIndex = 2,
                sentenceIndex = 3,
                updatedAt = 100,
            ),
        )

        assertNotNull(database!!.readingPositionDao().getByBookId("book-1"))
        database!!.bookDao().deleteById("book-1")
        assertNull(database!!.readingPositionDao().getByBookId("book-1"))
    }

    private companion object {
        const val TEST_DATABASE = "migration-1-2-test.db"
    }
}
