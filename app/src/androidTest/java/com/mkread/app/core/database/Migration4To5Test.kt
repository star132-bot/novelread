package com.mkread.app.core.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration4To5Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MkreadDatabase::class.java,
    )

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE)
    }

    @Test
    fun migrationKeepsBooksAndAddsCatalogIdentity() {
        helper.createDatabase(DATABASE, 4).apply {
            execSQL(
                """
                INSERT INTO books(
                    id, title, author, source_type, source_sha256,
                    cover_relative_path, imported_at, modified_at, last_opened_at, folder_id
                ) VALUES ('book-1', 'Old Book', NULL, 'TXT', 'old-hash', NULL, 1, 1, NULL, NULL)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO chapters(id, book_id, ordinal, title, relative_path, character_count, content_sha256)
                VALUES ('chapter-1', 'book-1', 1, 'Chapter One', 'chapters/0001.txt', 10, 'chapter-hash')
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(DATABASE, 5, true, MkreadDatabase.MIGRATION_4_5)

        migrated.query("SELECT title, catalog_id, catalog_revision FROM books WHERE id = 'book-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Old Book", cursor.getString(0))
            assertTrue("Existing books have no catalog id", cursor.isNull(1) && cursor.isNull(2))
        }
        migrated.query("SELECT external_id FROM chapters WHERE id = 'chapter-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }

        migrated.execSQL("UPDATE books SET catalog_id = 'yexing-zhe', catalog_revision = 3 WHERE id = 'book-1'")
        assertThrows("catalog_id must be unique", SQLiteConstraintException::class.java) {
            migrated.execSQL(
                """
                INSERT INTO books(
                    id, title, author, source_type, source_sha256, cover_relative_path,
                    imported_at, modified_at, last_opened_at, folder_id, catalog_id, catalog_revision
                ) VALUES ('book-2', 'Copy', NULL, 'MKBOOK', 'other-hash', NULL, 2, 2, NULL, NULL, 'yexing-zhe', 4)
                """.trimIndent(),
            )
        }
        migrated.close()
    }

    private companion object {
        const val DATABASE = "migration-4-5-test.db"
    }
}
