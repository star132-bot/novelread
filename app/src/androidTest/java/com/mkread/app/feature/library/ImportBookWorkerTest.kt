package com.mkread.app.feature.library

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.testing.TestListenableWorkerBuilder
import com.mkread.app.core.database.BookEntity
import com.mkread.app.core.database.ChapterEntity
import com.mkread.app.core.files.FileBookStorage
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportBookWorkerTest {
    private lateinit var context: Context
    private val roots = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ImportTestContentProvider.clear()
    }

    @After
    fun tearDown() {
        ImportTestContentProvider.clear()
        roots.forEach(File::deleteRecursively)
    }

    @Test
    fun validContentUri_importsTxtAndReturnsSuccessOutput() = runBlocking {
        val harness = harness()
        val uri = ImportTestContentProvider.register(
            "valid",
            "示例小说\n\n第一章 开始\n这是正文。".toByteArray(Charsets.UTF_8),
        )

        val result = harness.worker(uri).doWork()

        val success = result as ListenableWorker.Result.Success
        assertEquals(ImportBookWorker.STATUS_SUCCESS, success.outputData.getString(ImportBookWorker.KEY_STATUS))
        assertEquals(1, harness.repository.commitCount)
        assertTrue(harness.booksRoot.listFiles().orEmpty().single().isDirectory)
        assertNoStaging(harness)
    }

    @Test
    fun permanentParserFailure_returnsTypedFailureWithoutRetry() = runBlocking {
        val harness = harness()
        val uri = ImportTestContentProvider.register(
            "empty",
            "  \n\n  ".toByteArray(Charsets.UTF_8),
        )

        val result = harness.worker(uri).doWork()

        val failure = result as ListenableWorker.Result.Failure
        assertEquals(
            ImportFailureCode.NO_READABLE_CONTENT.name,
            failure.outputData.getString(ImportBookWorker.KEY_FAILURE_CODE),
        )
        assertEquals(0, harness.repository.commitCount)
        assertNoStaging(harness)
    }

    @Test
    fun transientSourceFailure_retriesOnceThenImportsExactlyOnce() = runBlocking {
        val harness = harness()
        val uri = ImportTestContentProvider.register(
            "retry",
            "正文内容".toByteArray(Charsets.UTF_8),
            1,
        )

        val first = harness.worker(uri, runAttemptCount = 0).doWork()
        assertTrue(first is ListenableWorker.Result.Retry)
        assertNoStaging(harness)

        val second = harness.worker(uri, runAttemptCount = 1).doWork()
        assertTrue(second is ListenableWorker.Result.Success)
        assertEquals(1, harness.repository.commitCount)
        assertNoStaging(harness)
    }

    @Test
    fun thirdTransientSourceFailure_isTerminal() = runBlocking {
        val harness = harness()
        val uri = ImportTestContentProvider.register(
            "unavailable",
            "正文".toByteArray(Charsets.UTF_8),
            3,
        )

        val result = harness.worker(uri, runAttemptCount = 2).doWork()

        val failure = result as ListenableWorker.Result.Failure
        assertEquals(
            ImportFailureCode.SOURCE_UNAVAILABLE.name,
            failure.outputData.getString(ImportBookWorker.KEY_FAILURE_CODE),
        )
        assertNoStaging(harness)
    }

    @Test
    fun cancellationDuringCopy_propagatesAndRemovesStaging() = runBlocking {
        val access = CancellingDocumentAccess()
        val harness = harness(access)
        val uri = Uri.parse("content://com.mkread.app.import-test/cancel")

        try {
            harness.worker(uri, permissionPersisted = true).doWork()
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected structured cancellation from the source stream.
        }

        assertEquals(1, access.releaseCount)
        assertNoStaging(harness)
    }

    @Test
    fun retryKeepsPersistedPermission_untilTerminalSuccess() = runBlocking {
        val bytes = "正文内容".toByteArray(Charsets.UTF_8)
        var opens = 0
        val access = RecordingDocumentAccess(openStream = {
            opens += 1
            if (opens == 1) throw IOException("temporarily unavailable")
            bytes.inputStream()
        })
        val harness = harness(access)
        val uri = Uri.parse("content://com.mkread.app.import-test/permission-retry")

        val first = harness.worker(
            uri,
            runAttemptCount = 0,
            permissionPersisted = true,
        ).doWork()
        assertTrue(first is ListenableWorker.Result.Retry)
        assertEquals(0, access.releaseCount)

        val second = harness.worker(
            uri,
            runAttemptCount = 1,
            permissionPersisted = true,
        ).doWork()
        assertTrue(second is ListenableWorker.Result.Success)
        assertEquals(1, access.releaseCount)
        assertNoStaging(harness)
    }

    @Test
    fun scheduler_takesPermissionBeforeEnqueue_andBuildsStableUniqueWork() {
        val events = mutableListOf<String>()
        val access = RecordingDocumentAccess(
            openStream = { "正文".byteInputStream() },
            events = events,
        )
        var capturedName: String? = null
        var capturedPolicy: ExistingWorkPolicy? = null
        var capturedRequest: OneTimeWorkRequest? = null
        val scheduler = BookImportScheduler(
            workEnqueuer = BookImportScheduler.WorkEnqueuer { name, policy, request ->
                events += "enqueue"
                capturedName = name
                capturedPolicy = policy
                capturedRequest = request
            },
            documentAccess = access,
        )
        val uri = Uri.parse("content://documents/book/42")

        val requestId = scheduler.enqueue(uri, " Novel.txt ", "text/plain")

        assertEquals(listOf("take", "enqueue"), events)
        assertEquals(ExistingWorkPolicy.KEEP, capturedPolicy)
        assertEquals(requestId, requireNotNull(capturedRequest).id)
        assertEquals(
            BookImportScheduler.uniqueWorkName(uri, "Novel.txt"),
            capturedName,
        )
        assertEquals(
            "Novel.txt",
            capturedRequest!!.workSpec.input.getString(ImportBookWorker.KEY_DISPLAY_NAME),
        )
        assertEquals(BackoffPolicy.LINEAR, capturedRequest!!.workSpec.backoffPolicy)
        assertEquals(10_000L, capturedRequest!!.workSpec.backoffDelayDuration)
        assertTrue(BookImportScheduler.IMPORT_TAG in capturedRequest!!.tags)
        assertEquals(
            listOf("text/plain", "application/epub+zip", "application/octet-stream"),
            BookImportScheduler.SUPPORTED_MIME_TYPES.toList(),
        )
    }

    @Test
    fun scheduler_enqueueFailure_releasesNewPersistedPermission() {
        val access = RecordingDocumentAccess(
            openStream = { "正文".byteInputStream() },
        )
        val scheduler = BookImportScheduler(
            workEnqueuer = BookImportScheduler.WorkEnqueuer { _, _, _ ->
                throw IllegalStateException("WorkManager unavailable")
            },
            documentAccess = access,
        )

        try {
            scheduler.enqueue(Uri.parse("content://documents/book/failed"), "book.txt", null)
            fail("Expected scheduling failure")
        } catch (_: IllegalStateException) {
            // Expected injected scheduling failure.
        }

        assertEquals(1, access.takeCount)
        assertEquals(1, access.releaseCount)
    }

    private fun harness(documentAccess: DocumentAccess = AndroidDocumentAccess(context)): Harness {
        val root = File(context.cacheDir, "worker-test-${UUID.randomUUID()}").apply { mkdirs() }
        roots += root
        return Harness(context, root, documentAccess)
    }

    private fun assertNoStaging(harness: Harness) {
        assertTrue(harness.importsRoot.listFiles().orEmpty().isEmpty())
    }

    private class Harness(
        private val context: Context,
        root: File,
        documentAccess: DocumentAccess,
    ) {
        val booksRoot = File(root, "files/books")
        val importsRoot = File(root, "cache/import")
        val repository = FakeRepository()
        private val storage = FileBookStorage(
            filesDir = File(root, "files"),
            cacheDir = File(root, "cache"),
        )
        private val workerFactory = ImportBookWorkerFactory(
            importer = ImportBookUseCase(storage, repository),
            documentAccess = documentAccess,
        )

        fun worker(
            uri: Uri,
            runAttemptCount: Int = 0,
            permissionPersisted: Boolean = false,
        ): ImportBookWorker = TestListenableWorkerBuilder.from(
            context,
            ImportBookWorker::class.java,
        )
            .setWorkerFactory(workerFactory)
            .setRunAttemptCount(runAttemptCount)
            .setInputData(
                ImportBookWorker.inputData(
                    uri = uri,
                    displayName = "novel.txt",
                    mimeType = "text/plain",
                    permissionPersisted = permissionPersisted,
                ),
            )
            .build()
    }

    private class FakeRepository : BookRepository {
        var commitCount = 0

        override suspend fun findBookIdBySourceHash(sourceSha256: String): String? = null

        override suspend fun commitImportedBook(
            book: BookEntity,
            chapters: List<ChapterEntity>,
        ) {
            commitCount += 1
        }
    }

    private class CancellingDocumentAccess : DocumentAccess {
        var releaseCount = 0

        override fun openInputStream(uri: Uri): InputStream = object : InputStream() {
            override fun read(): Int = throw CancellationException("cancelled")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                throw CancellationException("cancelled")
        }

        override fun takePersistableReadPermission(uri: Uri): Boolean = true

        override fun releasePersistableReadPermission(uri: Uri) {
            releaseCount += 1
        }
    }

    private class RecordingDocumentAccess(
        private val openStream: () -> InputStream,
        private val events: MutableList<String>? = null,
    ) : DocumentAccess {
        var takeCount = 0
        var releaseCount = 0

        override fun openInputStream(uri: Uri): InputStream = openStream()

        override fun takePersistableReadPermission(uri: Uri): Boolean {
            events?.add("take")
            takeCount += 1
            return true
        }

        override fun releasePersistableReadPermission(uri: Uri) {
            releaseCount += 1
        }
    }
}
