package com.mkread.app.feature.reader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mkread.app.core.database.BookEntity
import com.mkread.app.core.database.ChapterEntity
import com.mkread.app.core.database.MkreadDatabase
import com.mkread.app.core.model.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingPositionRepositoryTest {
    private lateinit var database: MkreadDatabase
    private lateinit var repository: RoomReadingPositionRepository
    private var now = 1_000L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MkreadDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.bookDao().insertBookWithChapters(
            BookEntity(
                id = "book-1",
                title = "测试书",
                author = null,
                sourceType = SourceType.TXT,
                sourceSha256 = "source-hash",
                coverRelativePath = null,
                importedAt = now,
                modifiedAt = now,
                lastOpenedAt = null,
            ),
            listOf(
                ChapterEntity(
                    id = "chapter-1",
                    bookId = "book-1",
                    ordinal = 0,
                    title = "第一章",
                    relativePath = "chapter-0000.txt",
                    characterCount = 10,
                    contentSha256 = "chapter-hash",
                ),
            ),
        )
        repository = RoomReadingPositionRepository(database, clock = { now })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun saveClampsOffsetsAndCoalescesStationaryWrites() = runBlocking {
        assertTrue(
            repository.save(
                ReadingPosition(
                    bookId = "book-1",
                    chapterId = "chapter-1",
                    characterOffset = 99,
                    pageIndex = -2,
                    sentenceIndex = -1,
                    updatedAt = 0,
                ),
                chapterLength = 10,
            ),
        )
        assertEquals(
            ReadingPosition("book-1", "chapter-1", 10, 0, 0, now),
            repository.get("book-1"),
        )

        now = 1_500L
        assertFalse(repository.save(repository.get("book-1")!!, chapterLength = 10))
        now = 2_000L
        assertTrue(repository.save(repository.get("book-1")!!, chapterLength = 10))
    }

    @Test
    fun sentenceChangeAndForcedCheckpointWriteImmediately() = runBlocking {
        val initial = ReadingPosition("book-1", "chapter-1", 2, 0, 0, 0)
        assertTrue(repository.save(initial, chapterLength = 10))

        now = 1_100L
        assertTrue(
            repository.save(
                initial.copy(characterOffset = 3, sentenceIndex = 1),
                chapterLength = 10,
            ),
        )

        now = 1_200L
        assertTrue(
            repository.checkpoint(
                initial.copy(characterOffset = 4, sentenceIndex = 1),
                chapterLength = 10,
            ),
        )
        assertEquals(4, repository.get("book-1")?.characterOffset)
        assertEquals(1, repository.get("book-1")?.sentenceIndex)
        assertEquals(1_200L, repository.get("book-1")?.updatedAt)
    }
}
