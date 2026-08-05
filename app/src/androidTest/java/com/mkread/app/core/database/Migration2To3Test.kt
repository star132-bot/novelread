package com.mkread.app.core.database

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mkread.app.AppContainer
import com.mkread.app.core.model.SourceType
import com.mkread.app.speech.AudioCacheCommit
import com.mkread.app.speech.RoomAudioCacheRepository
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration2To3Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MkreadDatabase::class.java,
    )

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cacheRoot by lazy { File(context.cacheDir, "audio-cache-repository-test") }
    private var database: MkreadDatabase? = null

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_DATABASE)
        context.deleteDatabase(APP_DATABASE)
        cacheRoot.deleteRecursively()
    }

    @After
    fun tearDown() {
        database?.close()
        database = null
        context.deleteDatabase(TEST_DATABASE)
        context.deleteDatabase(APP_DATABASE)
        cacheRoot.deleteRecursively()
    }

    @Test
    fun appContainerRegistersMigrationAndAudioCacheRepository() = runBlocking {
        helper.createDatabase(APP_DATABASE, 2).apply {
            execSQL(
                """
                INSERT INTO books(
                    id, title, author, source_type, source_sha256,
                    cover_relative_path, imported_at, modified_at, last_opened_at
                ) VALUES ('book-app', 'App Book', NULL, 'TXT', 'app-hash', NULL, 1, 1, NULL)
                """.trimIndent(),
            )
            close()
        }

        val application = ApplicationProvider.getApplicationContext<Application>()
        val container = AppContainer(application)
        database = container.database

        assertEquals("book-app", database!!.bookDao().getById("book-app")!!.id)
        assertNotNull(container.audioCacheRepository)
    }

    @Test
    fun migrationPreservesBooksChaptersAndReadingPositions() = runBlocking {
        helper.createDatabase(TEST_DATABASE, 2).apply {
            execSQL(
                """
                INSERT INTO books(
                    id, title, author, source_type, source_sha256,
                    cover_relative_path, imported_at, modified_at, last_opened_at
                ) VALUES ('book-1', 'Old Book', 'Author', 'TXT', 'source-hash', NULL, 10, 20, 30)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO chapters(
                    id, book_id, ordinal, title, relative_path, character_count, content_sha256
                ) VALUES ('chapter-1', 'book-1', 0, 'Chapter One', 'chapter-0000.txt', 120, 'chapter-hash')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO reading_positions(
                    bookId, chapterId, characterOffset, pageIndex, sentenceIndex, updatedAt
                ) VALUES ('book-1', 'chapter-1', 42, 2, 3, 100)
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            3,
            true,
            MkreadDatabase.MIGRATION_2_3,
        )

        migrated.query("SELECT title, source_sha256 FROM books WHERE id = 'book-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Old Book", cursor.getString(0))
            assertEquals("source-hash", cursor.getString(1))
        }
        migrated.query("SELECT title, content_sha256 FROM chapters WHERE id = 'chapter-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Chapter One", cursor.getString(0))
            assertEquals("chapter-hash", cursor.getString(1))
        }
        migrated.query(
            "SELECT characterOffset, pageIndex, sentenceIndex FROM reading_positions WHERE bookId = 'book-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(42, cursor.getInt(0))
            assertEquals(2, cursor.getInt(1))
            assertEquals(3, cursor.getInt(2))
        }
        migrated.query("SELECT COUNT(*) FROM audio_cache").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()

        database = Room.databaseBuilder(context, MkreadDatabase::class.java, TEST_DATABASE)
            .addMigrations(MkreadDatabase.MIGRATION_1_2, MkreadDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        database!!.audioCacheDao().insert(
            cacheEntity(cacheKey = key('a'), relativeWavePath = "tts/aa/${key('a')}.wav"),
        )

        assertNotNull(database!!.audioCacheDao().getByCacheKey(key('a')))
        database!!.bookDao().deleteById("book-1")
        assertNull(database!!.audioCacheDao().getByCacheKey(key('a')))
    }

    @Test
    fun commitPromotesValidatedPartialAndFindRemovesCorruption() = runBlocking {
        val repository = createRepository()
        insertBook()
        val cacheKey = key('a')
        val partial = File(cacheRoot, "sentence.wav.partial").apply {
            parentFile!!.mkdirs()
            writeBytes(playableWaveBytes())
        }

        val cached = repository.commit(commit(cacheKey), partial)

        val expectedFile = File(cacheRoot, "tts/aa/$cacheKey.wav")
        assertFalse(partial.exists())
        assertEquals(expectedFile.canonicalFile, cached.file.canonicalFile)
        assertTrue(expectedFile.isFile)
        assertEquals("tts/aa/$cacheKey.wav", cached.entity.relativeWavePath)
        assertEquals(expectedFile.length(), cached.entity.byteSize)
        assertEquals(1, cached.sampleCount)

        val found = repository.find(cacheKey, accessedAt = 200L)
        assertNotNull(found)
        assertEquals(200L, database!!.audioCacheDao().getByCacheKey(cacheKey)!!.lastAccessedAt)

        expectedFile.writeBytes(byteArrayOf(1, 2, 3))

        assertNull(repository.find(cacheKey, accessedAt = 300L))
        assertNull(database!!.audioCacheDao().getByCacheKey(cacheKey))
        assertFalse(expectedFile.exists())
    }

    @Test
    fun commitDeletesPromotedFileWhenRoomInsertFails() = runBlocking {
        val repository = createRepository()
        val cacheKey = key('b')
        val partial = File(cacheRoot, "orphan.wav.partial").apply {
            parentFile!!.mkdirs()
            writeBytes(playableWaveBytes())
        }

        try {
            repository.commit(commit(cacheKey, bookId = "missing-book"), partial)
            fail("Expected the foreign-key insert to fail")
        } catch (_: Exception) {
            // Expected Room constraint failure.
        }

        assertFalse(File(cacheRoot, "tts/bb/$cacheKey.wav").exists())
        assertNull(database!!.audioCacheDao().getByCacheKey(cacheKey))
    }

    @Test
    fun maintenanceOperationsRespectProtectionAndRemoveFiles() = runBlocking {
        val repository = createRepository()
        insertBook()
        val protectedKey = key('a')
        val oldestKey = key('b')
        val newestKey = key('c')
        val otherChapterKey = key('d')
        val protected = repository.commit(commit(protectedKey, lastAccessedAt = 10L), partial('a'))
        val oldest = repository.commit(commit(oldestKey, lastAccessedAt = 20L), partial('b'))
        val newest = repository.commit(commit(newestKey, lastAccessedAt = 30L), partial('c'))
        val otherChapter = repository.commit(
            commit(otherChapterKey, chapterId = "chapter-2", lastAccessedAt = 60L),
            partial('d'),
        )

        repository.protect(setOf(protectedKey), protectedUntil = 1_000L)
        repository.touch(newestKey, accessedAt = 50L)
        val bytesRemoved = repository.evictToBudget(
            maxBytes = protected.entity.byteSize + otherChapter.entity.byteSize,
            now = 500L,
        )

        assertEquals(oldest.entity.byteSize + newest.entity.byteSize, bytesRemoved)
        assertNotNull(database!!.audioCacheDao().getByCacheKey(protectedKey))
        assertNull(database!!.audioCacheDao().getByCacheKey(oldestKey))
        assertNull(database!!.audioCacheDao().getByCacheKey(newestKey))
        assertNotNull(database!!.audioCacheDao().getByCacheKey(otherChapterKey))
        assertFalse(oldest.file.exists())
        assertFalse(newest.file.exists())

        assertEquals(1, repository.invalidateChapter("chapter-2"))
        assertFalse(otherChapter.file.exists())
        assertNull(database!!.audioCacheDao().getByCacheKey(otherChapterKey))

        val corruptKey = key('e')
        val corrupt = repository.commit(commit(corruptKey), partial('e'))
        corrupt.file.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(1, repository.removeInvalid())
        assertNull(database!!.audioCacheDao().getByCacheKey(corruptKey))
        assertFalse(corrupt.file.exists())

        File(cacheRoot, "tts/orphan.partial").apply {
            parentFile!!.mkdirs()
            writeText("partial")
        }
        assertEquals(1, repository.clearGeneratedAudio())
        assertTrue(database!!.audioCacheDao().getAll().isEmpty())
        assertFalse(File(cacheRoot, "tts").exists())
    }

    private suspend fun createRepository(): RoomAudioCacheRepository {
        database = Room.inMemoryDatabaseBuilder(context, MkreadDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        return RoomAudioCacheRepository(database!!.audioCacheDao(), cacheRoot)
    }

    private suspend fun insertBook() {
        database!!.bookDao().insertBook(
            BookEntity(
                id = "book-1",
                title = "Book",
                author = null,
                sourceType = SourceType.TXT,
                sourceSha256 = "book-content-hash",
                coverRelativePath = null,
                importedAt = 1L,
                modifiedAt = 1L,
                lastOpenedAt = null,
            ),
        )
    }

    private fun commit(
        cacheKey: String,
        bookId: String = "book-1",
        chapterId: String = "chapter-1",
        lastAccessedAt: Long = 100L,
    ) = AudioCacheCommit(
        cacheKey = cacheKey,
        bookId = bookId,
        chapterId = chapterId,
        sentenceStart = 10,
        sentenceEnd = 20,
        normalizedTextSha256 = "text-hash",
        voicePackageSha256 = "voice-hash",
        styleId = "neutral",
        qualityId = "fluent",
        generationVersion = 1,
        lastAccessedAt = lastAccessedAt,
        protectedUntil = 0L,
    )

    private fun cacheEntity(
        cacheKey: String,
        relativeWavePath: String,
    ) = AudioCacheEntity(
        cacheKey = cacheKey,
        bookId = "book-1",
        chapterId = "chapter-1",
        sentenceStart = 10,
        sentenceEnd = 20,
        normalizedTextSha256 = "text-hash",
        voicePackageSha256 = "voice-hash",
        styleId = "neutral",
        qualityId = "fluent",
        generationVersion = 1,
        relativeWavePath = relativeWavePath,
        byteSize = 46L,
        lastAccessedAt = 100L,
        protectedUntil = 0L,
    )

    private fun partial(label: Char) = File(cacheRoot, "$label.wav.partial").apply {
        parentFile!!.mkdirs()
        writeBytes(playableWaveBytes())
    }

    private fun key(character: Char): String = character.toString().repeat(64)

    private fun playableWaveBytes(): ByteArray = ByteArrayOutputStream().apply {
        writeAscii("RIFF")
        writeLittleEndian32(38)
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeLittleEndian32(16)
        writeLittleEndian16(1)
        writeLittleEndian16(1)
        writeLittleEndian32(24_000)
        writeLittleEndian32(48_000)
        writeLittleEndian16(2)
        writeLittleEndian16(16)
        writeAscii("data")
        writeLittleEndian32(2)
        writeLittleEndian16(0)
    }.toByteArray()

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteArrayOutputStream.writeLittleEndian16(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
    }

    private fun ByteArrayOutputStream.writeLittleEndian32(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
        write(value ushr 16 and 0xff)
        write(value ushr 24 and 0xff)
    }

    private companion object {
        const val APP_DATABASE = "mkread.db"
        const val TEST_DATABASE = "migration-2-3-test.db"
    }
}
