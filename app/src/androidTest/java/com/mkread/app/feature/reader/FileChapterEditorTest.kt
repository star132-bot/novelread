package com.mkread.app.feature.reader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mkread.app.core.database.BookEntity
import com.mkread.app.core.database.ChapterEntity
import com.mkread.app.core.database.MkreadDatabase
import com.mkread.app.core.files.ImportLimits
import com.mkread.app.core.model.SourceType
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileChapterEditorTest {
    private lateinit var database: MkreadDatabase
    private lateinit var root: File
    private lateinit var sourceFile: File
    private lateinit var chapterFile: File
    private lateinit var chapter: ChapterEntity
    private lateinit var invalidator: RecordingInvalidator
    private lateinit var editor: FileChapterEditor

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MkreadDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        root = File(context.cacheDir, "editor-${UUID.randomUUID()}").apply { mkdirs() }
        val bookRoot = File(root, "books/book-1").apply { mkdirs() }
        sourceFile = File(bookRoot, "source.txt").apply { writeText("ORIGINAL", Charsets.UTF_8) }
        chapterFile = File(bookRoot, "chapter-0001.txt")
        val initialText = "第一句。\n第二句。"
        chapterFile.writeText(initialText, Charsets.UTF_8)
        chapter = ChapterEntity(
            id = "chapter-1",
            bookId = "book-1",
            ordinal = 1,
            title = "第一章",
            relativePath = "chapter-0001.txt",
            characterCount = initialText.length,
            contentSha256 = initialText.sha256(),
        )
        database.bookDao().insertBookWithChapters(
            BookEntity(
                id = "book-1",
                title = "测试书",
                author = null,
                sourceType = SourceType.TXT,
                sourceSha256 = "source-hash",
                coverRelativePath = null,
                importedAt = 1L,
                modifiedAt = 1L,
                lastOpenedAt = null,
            ),
            listOf(chapter),
        )
        invalidator = RecordingInvalidator()
        editor = createEditor()
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun saveNormalizesLineEndingsUpdatesRoomAndPreservesOriginalSource() = runBlocking {
        val originalSource = sourceFile.readBytes()
        val result = editor.save(
            chapterId = chapter.id,
            newText = "前缀\r\n第一句。\r第二句。\n后缀",
            currentOffset = 4,
        )

        assertEquals("前缀\n第一句。\n第二句。\n后缀", chapterFile.readText(Charsets.UTF_8))
        assertEquals(chapterFile.readText(Charsets.UTF_8).length, result.characterCount)
        val savedChapter = database.chapterDao().getById(chapter.id)
        assertEquals(result.characterCount, savedChapter?.characterCount)
        assertEquals(result.newContentSha256, savedChapter?.contentSha256)
        assertEquals(99L, database.bookDao().getById(chapter.bookId)?.modifiedAt)
        assertEquals(result.newContentSha256, chapterFile.readText(Charsets.UTF_8).sha256())
        assertEquals(originalSource.toList(), sourceFile.readBytes().toList())
        assertEquals(1, invalidator.calls.size)
        assertTrue(result.undoAvailable)
    }

    @Test
    fun undoRestoresPreviousChapterAndConsumesOneSnapshot() = runBlocking {
        val original = chapterFile.readText(Charsets.UTF_8)
        editor.save(chapter.id, "Changed。", currentOffset = 2)

        val result = editor.undo(chapter.id, currentOffset = 3)

        assertEquals(original, chapterFile.readText(Charsets.UTF_8))
        assertTrue(result != null)
        assertFalse(result!!.undoAvailable)
        assertFalse(File(chapterFile.parentFile, ".undo").listFiles().orEmpty().any())
        assertEquals(chapter.contentSha256, database.chapterDao().getById(chapter.id)?.contentSha256)
    }

    @Test
    fun secondSaveReplacesUndoWithImmediatelyPreviousChapter() = runBlocking {
        editor.save(chapter.id, "First saved version", currentOffset = 2)
        editor.save(chapter.id, "Second saved version", currentOffset = 2)

        val result = editor.undo(chapter.id, currentOffset = 2)

        assertEquals("First saved version", chapterFile.readText(Charsets.UTF_8))
        assertFalse(result!!.undoAvailable)
        assertFalse(editor.hasUndo(chapter.id))
    }

    @Test
    fun invalidatorFailureCommitsTextAndRecordsCleanupMarker() = runBlocking {
        invalidator.shouldFail = true

        val result = editor.save(chapter.id, "New text。", currentOffset = 1)

        assertTrue(result.cleanupRecorded)
        assertEquals("New text。", chapterFile.readText(Charsets.UTF_8))
        assertTrue(File(root, "reader-cleanup").walkTopDown().any { it.isFile })
    }

    @Test
    fun fileOperationFailuresRestoreCurrentChapterAndRoomHash() = runBlocking {
        listOf(
            InjectedFailure.WRITE_NEW to ChapterEditFailure.FILE_IO,
            InjectedFailure.MOVE_TO_UNDO to ChapterEditFailure.FILE_IO,
            InjectedFailure.PROMOTE_NEW to ChapterEditFailure.FILE_IO,
        ).forEach { (injectedFailure, expectedFailure) ->
            val failingEditor = createEditor(FailingFileOperations(injectedFailure))
            assertSaveFailure(failingEditor, expectedFailure)
        }
    }

    @Test
    fun databaseFailureRestoresCurrentChapterAndRoomHash() = runBlocking {
        val failingMetadata = FailingMetadata(RoomChapterEditMetadata(database))
        val failingEditor = createEditor(metadata = failingMetadata)

        assertSaveFailure(failingEditor, ChapterEditFailure.DATABASE)
    }

    @Test
    fun blankAndOversizedTextAreRejectedWithoutChangingChapter() = runBlocking {
        assertSaveFailure(editor, ChapterEditFailure.BLANK_CHAPTER, " \r\n ")

        val limitedEditor = createEditor(maxCharacters = 4)
        assertSaveFailure(limitedEditor, ChapterEditFailure.TOO_LARGE, "12345")
    }

    private suspend fun assertSaveFailure(
        failingEditor: FileChapterEditor,
        expectedFailure: ChapterEditFailure,
        text: String = "Changed content",
    ) {
        val oldText = chapterFile.readText(Charsets.UTF_8)
        val oldHash = database.chapterDao().getById(chapter.id)!!.contentSha256
        val sourceBytes = sourceFile.readBytes()
        try {
            failingEditor.save(chapter.id, text, currentOffset = 2)
            throw AssertionError("Expected $expectedFailure")
        } catch (failure: ChapterEditException) {
            assertEquals(expectedFailure, failure.failure)
        }
        assertEquals(oldText, chapterFile.readText(Charsets.UTF_8))
        assertEquals(oldHash, database.chapterDao().getById(chapter.id)!!.contentSha256)
        assertEquals(sourceBytes.toList(), sourceFile.readBytes().toList())
        assertFalse(File(chapterFile.parentFile, "${chapterFile.name}.new").exists())
    }

    private fun createEditor(
        fileOperations: ChapterFileOperations = DefaultChapterFileOperations,
        metadata: ChapterEditMetadata = RoomChapterEditMetadata(database),
        maxCharacters: Int = ImportLimits.CHAPTER_CHARACTERS,
    ) = FileChapterEditor(
        filesDir = root,
        cacheDir = root,
        metadata = metadata,
        invalidator = invalidator,
        fileOperations = fileOperations,
        clock = { 99L },
        maxCharacters = maxCharacters,
    )

    private enum class InjectedFailure {
        WRITE_NEW,
        MOVE_TO_UNDO,
        PROMOTE_NEW,
    }

    private class FailingFileOperations(
        private val injectedFailure: InjectedFailure,
    ) : ChapterFileOperations {
        private val delegate = DefaultChapterFileOperations
        private var injected = false

        override fun createDirectories(directory: File) = delegate.createDirectories(directory)

        override fun writeUtf8AndSync(target: File, text: String) {
            if (!injected && injectedFailure == InjectedFailure.WRITE_NEW && target.name.endsWith(".new")) {
                injected = true
                error("injected write failure")
            }
            delegate.writeUtf8AndSync(target, text)
        }

        override fun move(source: File, target: File) {
            val movingCurrentToUndo = target.parentFile?.name == ".undo" && target.name.endsWith(".txt")
            val promotingNew = source.name.endsWith(".new") && target.name == "chapter-0001.txt"
            if (!injected &&
                ((injectedFailure == InjectedFailure.MOVE_TO_UNDO && movingCurrentToUndo) ||
                    (injectedFailure == InjectedFailure.PROMOTE_NEW && promotingNew))
            ) {
                injected = true
                error("injected move failure")
            }
            delegate.move(source, target)
        }

        override fun delete(target: File): Boolean = delegate.delete(target)
    }

    private class FailingMetadata(
        private val delegate: ChapterEditMetadata,
    ) : ChapterEditMetadata {
        override suspend fun getChapter(chapterId: String): ChapterEntity? = delegate.getChapter(chapterId)

        override suspend fun updateContent(
            chapter: ChapterEntity,
            expectedHash: String,
            newHash: String,
            characterCount: Int,
            modifiedAt: Long,
        ) {
            error("injected database failure")
        }
    }

    private class RecordingInvalidator : DerivedDataInvalidator {
        val calls = mutableListOf<Triple<String, String, String>>()
        var shouldFail = false

        override suspend fun invalidateChapter(chapterId: String, oldHash: String, newHash: String) {
            calls += Triple(chapterId, oldHash, newHash)
            if (shouldFail) error("injected invalidator failure")
        }
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
