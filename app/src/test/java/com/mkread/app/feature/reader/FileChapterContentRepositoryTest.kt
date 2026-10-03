package com.mkread.app.feature.reader

import com.mkread.app.core.database.ChapterEntity
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileChapterContentRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var source: FakeChapterMetadataSource

    @Before
    fun setUp() {
        filesDir = temporaryFolder.newFolder("files")
        source = FakeChapterMetadataSource()
    }

    @Test
    fun loadReturnsVerifiedUtf8ChapterAndMetadata() = runBlocking {
        val text = "第一句。\n\nSecond sentence."
        val chapter = writeChapter("book-1", "chapter-0001.txt", text)
        source.chapters += chapter
        val repository = repository()

        val content = repository.load(chapter.id)

        assertEquals(chapter, content.chapter)
        assertEquals(text, content.text)
        assertEquals(text.utf8Sha256(), content.contentSha256)
        assertEquals(listOf(chapter), repository.listChapters("book-1"))
    }

    @Test
    fun traversalPathIsRejectedBeforeReadingOutsideFile() = runBlocking {
        val outside = File(filesDir, "outside.txt").apply { writeText("private", Charsets.UTF_8) }
        source.chapters += chapter(
            bookId = "book-1",
            relativePath = "../../outside.txt",
            text = outside.readText(),
        )

        assertFailure(ChapterContentFailure.UNSAFE_PATH) {
            repository().load("chapter-1")
        }
        assertTrue(outside.exists())
    }

    @Test
    fun hashMismatchReturnsTypedCorruptionFailure() = runBlocking {
        val chapter = writeChapter("book-1", "chapter-0001.txt", "Changed text")
            .copy(contentSha256 = "00".repeat(32))
        source.chapters += chapter

        assertFailure(ChapterContentFailure.HASH_MISMATCH) {
            repository().load(chapter.id)
        }
    }

    @Test
    fun malformedUtf8AndCharacterLimitAreRejected() = runBlocking {
        val bookDir = File(filesDir, "books/book-1").apply { mkdirs() }
        val invalid = File(bookDir, "invalid.txt").apply {
            writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        }
        source.chapters += chapter("book-1", invalid.name, "unused", id = "invalid")
        assertFailure(ChapterContentFailure.INVALID_UTF8) {
            repository().load("invalid")
        }

        val long = File(bookDir, "long.txt").apply { writeText("12345", Charsets.UTF_8) }
        source.chapters += chapter("book-1", long.name, "12345", id = "long")
        assertFailure(ChapterContentFailure.TOO_LARGE) {
            repository(maxCharacters = 4).load("long")
        }
    }

    @Test
    fun missingMetadataOrFileReturnsNotFound() = runBlocking {
        assertFailure(ChapterContentFailure.NOT_FOUND) {
            repository().load("missing-row")
        }

        source.chapters += chapter("book-1", "missing.txt", "missing", id = "missing-file")
        assertFailure(ChapterContentFailure.NOT_FOUND) {
            repository().load("missing-file")
        }
    }

    private fun repository(maxCharacters: Int = 5_000_000) = FileChapterContentRepository(
        filesDir = filesDir,
        metadataSource = source,
        ioDispatcher = Dispatchers.Unconfined,
        maxCharacters = maxCharacters,
    )

    private fun writeChapter(bookId: String, relativePath: String, text: String): ChapterEntity {
        File(filesDir, "books/$bookId").mkdirs()
        File(filesDir, "books/$bookId/$relativePath").writeText(text, Charsets.UTF_8)
        return chapter(bookId, relativePath, text)
    }

    private fun chapter(
        bookId: String,
        relativePath: String,
        text: String,
        id: String = "chapter-1",
    ) = ChapterEntity(
        id = id,
        bookId = bookId,
        ordinal = 1,
        title = "Chapter",
        relativePath = relativePath,
        characterCount = text.length,
        contentSha256 = text.utf8Sha256(),
    )

    private suspend fun assertFailure(
        expected: ChapterContentFailure,
        block: suspend () -> Unit,
    ) {
        try {
            block()
            fail("Expected chapter content failure $expected")
        } catch (failure: ChapterContentException) {
            assertEquals(expected, failure.failure)
        }
    }

    private fun String.utf8Sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private class FakeChapterMetadataSource : ChapterMetadataSource {
        val chapters = mutableListOf<ChapterEntity>()

        override suspend fun getChapter(chapterId: String): ChapterEntity? =
            chapters.singleOrNull { it.id == chapterId }

        override suspend fun getChapters(bookId: String): List<ChapterEntity> =
            chapters.filter { it.bookId == bookId }.sortedBy { it.ordinal }
    }
}
