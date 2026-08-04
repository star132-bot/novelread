package com.mkread.app.feature.library

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.mkread.app.core.database.BookEntity
import com.mkread.app.core.database.MkreadDatabase
import com.mkread.app.core.files.FileBookStorage
import com.mkread.app.core.files.ImportLimits
import com.mkread.app.core.files.TxtBookParser
import com.mkread.app.core.model.LibrarySort
import com.mkread.app.core.model.SourceType
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryImportJourneyTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var databaseName: String
    private lateinit var database: MkreadDatabase
    private lateinit var storage: FileBookStorage
    private lateinit var repository: RoomBookRepository
    private lateinit var importer: ImportBookUseCase
    private lateinit var documentAccess: AndroidDocumentAccess

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.cacheDir, "phase-2-${UUID.randomUUID()}").apply { mkdirs() }
        databaseName = "phase-2-${UUID.randomUUID()}.db"
        documentAccess = AndroidDocumentAccess(context)
        ImportTestContentProvider.clear()
        openPersistence()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized && database.isOpen) database.close()
        context.deleteDatabase(databaseName)
        root.deleteRecursively()
        ImportTestContentProvider.clear()
    }

    @Test
    fun txtAndEpubJourney_preservesSourcesAndSurvivesRepositoryRecreation() = runBlocking {
        assertTrue(repository.observeLibrary(LibraryQuery()).first().isEmpty())
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val txtBytes = testAssets.open("import/valid-utf8.txt").use { it.readBytes() }
        Log.i(METRIC_TAG, "txt_fixture_sha256=${sha256(txtBytes)}")
        val txtUri = ImportTestContentProvider.register("valid-utf8", txtBytes)
        val txtHashBefore = hashProviderBytes(txtUri)

        val txtWork = runImportWorker(txtUri, "雾城来信.txt", "text/plain")

        val txtSuccess = txtWork as ListenableWorker.Result.Success
        val txtBookId = requireNotNull(txtSuccess.outputData.getString(ImportBookWorker.KEY_BOOK_ID))
        assertEquals("雾城来信", database.bookDao().getById(txtBookId)?.title)
        assertEquals(
            listOf("第一章 雨夜", "第二章 清晨"),
            database.chapterDao().getByBookId(txtBookId).map { it.title },
        )

        assertTrue(repository.updateMetadata(txtBookId, "雾中来信", null))
        assertTrue(repository.updateMetadata(txtBookId, "雾中来信", "林舟"))
        assertEquals("林舟", database.bookDao().getById(txtBookId)?.author)
        assertTrue(repository.removeBook(txtBookId))
        assertNull(database.bookDao().getById(txtBookId))
        assertTrue(database.chapterDao().getByBookId(txtBookId).isEmpty())
        assertEquals(txtHashBefore, hashProviderBytes(txtUri))

        val fixtures = ImportJourneyFixtureFactory(File(root, "fixtures").apply { mkdirs() })
        val epubBytes = fixtures.validEpub().readBytes()
        Log.i(METRIC_TAG, "valid_epub_sha256=${sha256(epubBytes)}")
        val epubUri = ImportTestContentProvider.register("valid-epub", epubBytes)
        val epubHashBefore = hashProviderBytes(epubUri)
        val epubWork = runImportWorker(epubUri, "山海旧闻.epub", "application/epub+zip")
        val epubSuccess = epubWork as ListenableWorker.Result.Success
        val epubBookId = requireNotNull(epubSuccess.outputData.getString(ImportBookWorker.KEY_BOOK_ID))
        assertEquals("山海旧闻", database.bookDao().getById(epubBookId)?.title)
        assertEquals(
            listOf("第二章", "第一章"),
            database.chapterDao().getByBookId(epubBookId).map { it.title },
        )

        val malformedBytes = fixtures.malformedEpub().readBytes()
        Log.i(METRIC_TAG, "malformed_epub_sha256=${sha256(malformedBytes)}")
        val malformedUri = ImportTestContentProvider.register(
            "malformed-epub",
            malformedBytes,
        )
        val malformedWork = runImportWorker(
            malformedUri,
            "损坏.epub",
            "application/epub+zip",
        ) as ListenableWorker.Result.Failure
        assertEquals(
            ImportFailureCode.MALFORMED_EPUB.name,
            malformedWork.outputData.getString(ImportBookWorker.KEY_FAILURE_CODE),
        )
        assertEquals(listOf(epubBookId), database.bookDao().getAllIds())

        database.close()
        openPersistence()

        assertNotNull(database.bookDao().getById(epubBookId))
        assertEquals(
            listOf("第二章", "第一章"),
            database.chapterDao().getByBookId(epubBookId).map { it.title },
        )
        assertEquals(epubHashBefore, hashProviderBytes(epubUri))
    }

    @Test
    fun oneHundredBookSearchAndSort_completeWithinFiveHundredMilliseconds() = runBlocking {
        repeat(100) { index ->
            val suffix = index.toString().padStart(3, '0')
            database.bookDao().insertBook(
                BookEntity(
                    id = "seed-$suffix",
                    title = if (index == 73) "星河目标" else "小说 $suffix",
                    author = if (index == 73) "目标作者" else "作者 $suffix",
                    sourceType = SourceType.TXT,
                    sourceSha256 = "seed-hash-$suffix",
                    coverRelativePath = null,
                    importedAt = index.toLong(),
                    modifiedAt = index.toLong(),
                    lastOpenedAt = if (index % 2 == 0) index.toLong() else null,
                ),
            )
        }

        val started = SystemClock.elapsedRealtimeNanos()
        val result = repository.observeLibrary(
            LibraryQuery(search = "目标", sort = LibrarySort.TITLE),
        ).first()
        val elapsedMillis = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L

        assertEquals(listOf("seed-073"), result.map { it.id })
        assertTrue("100-book query took ${elapsedMillis}ms", elapsedMillis < 500L)
        Log.i(METRIC_TAG, "query_100_books_ms=$elapsedMillis")
        Unit
    }

    @Test(timeout = 60_000L)
    fun twentyMegabyteTxt_parsesCorrectlyAndRecordsTiming() {
        val source = File(root, "twenty-megabytes.txt")
        writeTwentyMegabyteTxt(source)

        val started = SystemClock.elapsedRealtimeNanos()
        val parsed = TxtBookParser().parse(source, source.name)
        val elapsedMillis = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L

        assertEquals(ImportLimits.SOURCE_BYTES, source.length())
        assertEquals("基准小说", parsed.title)
        assertEquals(5, parsed.chapters.size)
        assertTrue(parsed.chapters.all { it.text.length <= ImportLimits.CHAPTER_CHARACTERS })
        Log.i(METRIC_TAG, "parse_20mb_txt_ms=$elapsedMillis")
    }

    private fun openPersistence() {
        database = Room.databaseBuilder(context, MkreadDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        storage = FileBookStorage(
            filesDir = File(root, "files"),
            cacheDir = File(root, "cache"),
        )
        repository = RoomBookRepository(database, storage)
        importer = ImportBookUseCase(storage, repository)
    }

    private suspend fun runImportWorker(
        uri: Uri,
        displayName: String,
        mimeType: String,
    ): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder.from(context, ImportBookWorker::class.java)
            .setWorkerFactory(ImportBookWorkerFactory(importer, documentAccess))
            .setInputData(
                ImportBookWorker.inputData(
                    uri = uri,
                    displayName = displayName,
                    mimeType = mimeType,
                    permissionPersisted = false,
                ),
            )
            .build()
        return worker.doWork()
    }

    private fun hashProviderBytes(uri: Uri): String = documentAccess.openInputStream(uri).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun writeTwentyMegabyteTxt(target: File) {
        FileOutputStream(target).use { output ->
            var written = 0L
            fun write(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                output.write(bytes)
                written += bytes.size
            }
            write("基准小说\n\n")
            val fill = "The rain crossed the station while the reader kept moving forward.\n"
                .toByteArray(Charsets.US_ASCII)
            repeat(5) { chapterIndex ->
                if (chapterIndex > 0) write("\n")
                write("第${chapterIndex + 1}章 基准章节\n\n")
                val chapterTarget = ImportLimits.SOURCE_BYTES * (chapterIndex + 1) / 5L
                while (written < chapterTarget) {
                    val count = minOf(fill.size.toLong(), chapterTarget - written).toInt()
                    output.write(fill, 0, count)
                    written += count
                }
            }
            check(written == ImportLimits.SOURCE_BYTES)
            output.flush()
            output.fd.sync()
        }
    }

    private companion object {
        const val METRIC_TAG = "MKread.Phase2"
    }
}
