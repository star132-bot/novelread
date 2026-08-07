package com.mkread.app.feature.library

import com.mkread.app.core.database.BookEntity
import com.mkread.app.core.database.ChapterEntity
import com.mkread.app.core.files.FileBookStorage
import com.mkread.app.core.model.SourceType
import com.mkread.app.feature.reader.ChapterContent
import com.mkread.app.feature.reader.ChapterContentRepository
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookFragmentAssemblerTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("mkread-assembly").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun naturalOrderUsesNumericPrefixesAndKeepsBackMatterLast() {
        val fragments = listOf(
            BookFragmentSource("ten", "10 第十章 等待"),
            BookFragmentSource("afterword", "后记"),
            BookFragmentSource("two", "02 第二章 调查"),
            BookFragmentSource("one", "01 第一章 初见"),
        )

        assertEquals(
            listOf("one", "two", "ten", "afterword"),
            naturallyOrderBookFragments(fragments).map(BookFragmentSource::bookId),
        )
        assertEquals(
            listOf("two", "one", "ten", "afterword"),
            moveBookFragment(naturallyOrderBookFragments(fragments), fromIndex = 1, toIndex = 0)
                .map(BookFragmentSource::bookId),
        )
    }

    @Test
    fun assembleCopiesFragmentsIntoOneOrderedBookBeforeRemovingShelfCopies() = runTest {
        val sources = listOf(
            fragment("one", "01 第一章 初见", "first body"),
            fragment("two", "02 第二章 调查", "second body"),
            fragment("ten", "10 第十章 等待", "tenth body"),
        )
        val content = FakeChapterContentRepository(sources)
        val repository = RecordingAssemblyRepository()
        val storage = FileBookStorage(File(root, "files"), File(root, "cache"))
        val assembler = AssembleBookFragmentsUseCase(
            storage = storage,
            repository = repository,
            contentRepository = content,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            idGenerator = { "assembled-book" },
            clock = { 1234L },
        )

        val result = assembler.assemble(
            BookAssemblyRequest(
                title = "完整小说",
                folderId = "folder-first",
                fragments = naturallyOrderBookFragments(sources.map { it.source }),
            ),
        )

        assertEquals("assembled-book", result.bookId)
        assertEquals(3, result.chapterCount)
        assertEquals(3, result.removedFragmentCount)
        assertEquals("完整小说", repository.committedBook?.title)
        assertEquals("folder-first", repository.committedBook?.folderId)
        assertEquals(listOf(1, 2, 3), repository.committedChapters.map(ChapterEntity::ordinal))
        assertEquals(
            listOf("第一章 初见", "第二章 调查", "第十章 等待"),
            repository.committedChapters.map(ChapterEntity::title),
        )
        assertEquals(listOf("one", "two", "ten"), repository.removedBookIds)
        assertEquals(
            listOf("first body", "second body", "tenth body"),
            repository.committedChapters.map { chapter ->
                File(root, "files/books/assembled-book/${chapter.relativePath}").readText()
            },
        )
    }

    @Test
    fun cancellationDuringCleanupKeepsTheAlreadyCommittedBook() = runTest {
        val sources = listOf(
            fragment("one", "01 第一章", "first body"),
            fragment("two", "02 第二章", "second body"),
        )
        val repository = RecordingAssemblyRepository().apply {
            removeFailure = CancellationException("cancel cleanup")
        }
        val assembler = AssembleBookFragmentsUseCase(
            storage = FileBookStorage(File(root, "files"), File(root, "cache")),
            repository = repository,
            contentRepository = FakeChapterContentRepository(sources),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            idGenerator = { "committed-book" },
        )
        var cancelled = false

        try {
            assembler.assemble(
                BookAssemblyRequest(
                    title = "完整小说",
                    folderId = null,
                    fragments = sources.map(SourceFixture::source),
                ),
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals("committed-book", repository.committedBook?.id)
        assertTrue(File(root, "files/books/committed-book/chapter-0001.txt").isFile)
    }

    @Test
    fun unreadableDuplicateTargetPreservesTheSourceFragments() = runTest {
        val sources = listOf(
            fragment("one", "01 第一章", "first body"),
            fragment("two", "02 第二章", "second body"),
        )
        val repository = RecordingAssemblyRepository().apply {
            existingBookId = "missing-assembled-book"
        }
        val assembler = AssembleBookFragmentsUseCase(
            storage = FileBookStorage(File(root, "files"), File(root, "cache")),
            repository = repository,
            contentRepository = FakeChapterContentRepository(sources),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        var failed = false

        try {
            assembler.assemble(
                BookAssemblyRequest(
                    title = "完整小说",
                    folderId = null,
                    fragments = sources.map(SourceFixture::source),
                ),
            )
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue(failed)
        assertTrue(repository.removedBookIds.isEmpty())
    }

    @Test
    fun commitFailureDeletesPromotedFilesAndPreservesTheSourceFragments() = runTest {
        val sources = listOf(
            fragment("one", "01 第一章", "first body"),
            fragment("two", "02 第二章", "second body"),
        )
        val repository = RecordingAssemblyRepository().apply {
            commitFailure = IllegalStateException("database unavailable")
        }
        val assembler = AssembleBookFragmentsUseCase(
            storage = FileBookStorage(File(root, "files"), File(root, "cache")),
            repository = repository,
            contentRepository = FakeChapterContentRepository(sources),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            idGenerator = { "failed-book" },
        )
        var failed = false

        try {
            assembler.assemble(
                BookAssemblyRequest(
                    title = "完整小说",
                    folderId = null,
                    fragments = sources.map(SourceFixture::source),
                ),
            )
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue(failed)
        assertTrue(repository.removedBookIds.isEmpty())
        assertTrue(!File(root, "files/books/failed-book").exists())
    }

    private fun fragment(bookId: String, title: String, text: String): SourceFixture {
        val chapter = ChapterEntity(
            id = "$bookId:0001",
            bookId = bookId,
            ordinal = 1,
            title = "第一卷",
            relativePath = "chapter-0001.txt",
            characterCount = text.length,
            contentSha256 = text.sha256(),
        )
        return SourceFixture(
            source = BookFragmentSource(bookId, title),
            chapters = listOf(ChapterContent(chapter, text, chapter.contentSha256)),
        )
    }

    private data class SourceFixture(
        val source: BookFragmentSource,
        val chapters: List<ChapterContent>,
    )

    private class FakeChapterContentRepository(
        fixtures: List<SourceFixture>,
    ) : ChapterContentRepository {
        private val byBook = fixtures.associate { fixture ->
            fixture.source.bookId to fixture.chapters
        }
        private val byChapter = fixtures
            .flatMap(SourceFixture::chapters)
            .associateBy { content -> content.chapter.id }

        override suspend fun load(chapterId: String): ChapterContent =
            checkNotNull(byChapter[chapterId])

        override suspend fun listChapters(bookId: String): List<ChapterEntity> =
            byBook[bookId].orEmpty().map(ChapterContent::chapter)
    }

    private class RecordingAssemblyRepository : BookAssemblyRepository {
        var committedBook: BookEntity? = null
        var committedChapters = emptyList<ChapterEntity>()
        val removedBookIds = mutableListOf<String>()
        var removeFailure: Exception? = null
        var existingBookId: String? = null
        var commitFailure: Exception? = null

        override suspend fun findBookIdBySourceHash(sourceSha256: String): String? = existingBookId

        override suspend fun commitImportedBook(
            book: BookEntity,
            chapters: List<ChapterEntity>,
        ) {
            commitFailure?.let { throw it }
            committedBook = book
            committedChapters = chapters
        }

        override suspend fun removeBook(bookId: String): Boolean {
            removeFailure?.let { throw it }
            removedBookIds += bookId
            return true
        }
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
