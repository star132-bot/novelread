package com.mkread.app.core.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration3To4Test {
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
    fun migrationPreservesBooksAndAddsShelfFolders() {
        helper.createDatabase(DATABASE, 3).apply {
            execSQL(
                """
                INSERT INTO books(
                    id, title, author, source_type, source_sha256,
                    cover_relative_path, imported_at, modified_at, last_opened_at
                ) VALUES ('book-1', 'Old Book', NULL, 'TXT', 'old-hash', NULL, 1, 1, NULL)
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            DATABASE,
            4,
            true,
            MkreadDatabase.MIGRATION_3_4,
        )
        migrated.query("SELECT title, folder_id FROM books WHERE id = 'book-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Old Book", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
        migrated.execSQL(
            "INSERT INTO shelf_folders(id, name, created_at) VALUES ('folder-1', '收藏', 2)",
        )
        migrated.execSQL("UPDATE books SET folder_id = 'folder-1' WHERE id = 'book-1'")
        migrated.query("SELECT folder_id FROM books WHERE id = 'book-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("folder-1", cursor.getString(0))
        }
        migrated.close()
    }

    private companion object {
        const val DATABASE = "migration-3-4-test.db"
    }
}
