package com.mkread.app.feature.library

import com.mkread.app.core.database.BookEntity
import com.mkread.app.core.database.ChapterEntity
import com.mkread.app.core.files.BookParseException
import com.mkread.app.core.files.BookParseFailure
import com.mkread.app.core.files.BookParser
import com.mkread.app.core.files.BookStorage
import com.mkread.app.core.files.CopiedSource
import com.mkread.app.core.files.ImportStaging
import com.mkread.app.core.files.ParsedBook
import com.mkread.app.core.files.ParsedChapter
import com.mkread.app.core.files.StoredBookMetadata
import com.mkread.app.core.files.StoredChapter
import com.mkread.app.core.files.StorageException
import com.mkread.app.core.files.StorageFailure
import com.mkread.app.core.model.SourceType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportBookUseCaseTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun success_followsAtomicOrder_andCommitsStableRecords() = runBlocking {
        val harness = Harness(temporaryFolder.root)

        val result = harness.useCase(harness.request())

        assertEquals(ImportResult.Success(BOOK_ID), result)
        assertEquals(
            listOf(
                "begin", "copy", "lookup", "detect", "parse-TXT",
                "write-chapter-1", "write-chapter-2", "write-cover",
                "write-metadata", "promote", "commit",
            ),
            harness.events,
        )
        val committedBook = requireNotNull(harness.repository.committedBook)
        assertEquals(BOOK_ID, committedBook.id)
        assertEquals(SourceType.TXT, committedBook.sourceType)
        assertEquals("cover.png", committedBook.coverRelativePath)
        assertEquals(
            listOf("$BOOK_ID:0001", "$BOOK_ID:0002"),
            harness.repository.committedChapters.map { it.id },
        )
        assertEquals("novel.txt", harness.storage.metadata?.sourceName)
    }

    @Test
    fun duplicateHash_discardsStagingAndSkipsTypeDetectionAndParsing() = runBlocking {
        val harness = Harness(temporaryFolder.root)
        harness.repository.existingBookId = "existing-book"

        val result = harness.useCase(harness.request())

        assertEquals(ImportResult.Duplicate("existing-book"), result)
        assertEquals(listOf("begin", "copy", "lookup", "discard"), harness.events)
        assertEquals(0, harness.txtParser.callCount)
        assertEquals(0, harness.epubParser.callCount)
    }

    @Test
    fun parserFailureBeforePromote_discardsStaging() = runBlocking {
        val harness = Harness(temporaryFolder.root)
        harness.txtParser.failure = BookParseException(
            BookParseFailure.INVALID_ENCODING,
            "bad encoding",
        )

        val result = harness.useCase(harness.request())

        assertFailure(ImportFailureCode.ENCODING, result)
        assertTrue(harness.events.endsWith(listOf("detect", "parse-TXT", "discard")))
        assertFalse("promote" in harness.events)
    }

    @Test
    fun repositoryFailureAfterPromote_deletesPromotedDirectory() = runBlocking {
        val harness = Harness(temporaryFolder.root)
        harness.repository.commitFailure = IllegalStateException("database unavailable")

        val result = harness.useCase(harness.request())

        assertFailure(ImportFailureCode.INTERNAL_COMMIT, result)
        assertTrue(harness.events.endsWith(listOf("promote", "commit", "delete-book")))
    }

    @Test
    fun concurrentDuplicateAfterPromote_deletesNewDirectoryAndReturnsExistingBook() = runBlocking {
        val harness = Harness(temporaryFolder.root)
        harness.repository.commitFailure = DuplicateSourceException("existing-book")

        val result = harness.useCase(harness.request())

        assertEquals(ImportResult.Duplicate("existing-book"), result)
        assertTrue(harness.events.endsWith(listOf("promote", "commit", "delete-book")))
    }

    @Test
    fun cancellationDuringCommit_compensatesAndPropagatesCancellation() = runBlocking {
        val harness = Harness(temporaryFolder.root)
        harness.repository.commitFailure = CancellationException("cancelled")

        try {
            harness.useCase(harness.request())
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected structured cancellation.
        }

        assertTrue(harness.events.endsWith(listOf("promote", "commit", "delete-book")))
    }

    @Test
    fun sourceOpenFailure_isTypedAndLeavesNoStaging() = runBlocking {
        val harness = Harness(temporaryFolder.root)
        val request = ImportRequest(
            displayName = "missing.txt",
            mimeType = "text/plain",
            openStream = { throw IOException("provider unavailable") },
        )

        val result = harness.useCase(request)

        assertFailure(ImportFailureCode.SOURCE_UNAVAILABLE, result)
        assertEquals(listOf("begin", "discard"), harness.events)
    }

    @Test
    fun sourceSizeFailure_isTypedAndCleansStaging() = runBlocking {
        val harness = Harness(temporaryFolder.root)
        harness.storage.copyFailure = StorageException(
            StorageFailure.SOURCE_TOO_LARGE,
            "too large",
        )

        val result = harness.useCase(harness.request())

        assertFailure(ImportFailureCode.SIZE_LIMIT, result)
        assertEquals(listOf("begin", "copy", "discard"), harness.events)
    }

    @Test
    fun contentDetectionOverridesFilenameExtension() {
        val detector = ContentSourceTypeDetector()
        val text = temporaryFolder.newFile("looks-like.epub").apply {
            writeText("plain text", Charsets.UTF_8)
        }
        val epub = temporaryFolder.newFile("looks-like.txt").apply {
            writeBytes(epubBytes())
        }

        assertEquals(SourceType.TXT, detector.detect(text))
        assertEquals(SourceType.EPUB, detector.detect(epub))
    }

    @Test
    fun invalidZipMagicPair_isTreatedAsTxtContent() {
        val detector = ContentSourceTypeDetector()
        val source = temporaryFolder.newFile("invalid-zip-magic.txt").apply {
            writeBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 6))
        }

        assertEquals(SourceType.TXT, detector.detect(source))
    }

    private fun assertFailure(expected: ImportFailureCode, result: ImportResult) {
        assertTrue("Expected failure but was $result", result is ImportResult.Failure)
        assertEquals(expected, (result as ImportResult.Failure).code)
        assertTrue(result.userMessage.isNotBlank())
    }

    private fun List<String>.endsWith(suffix: List<String>): Boolean =
        size >= suffix.size && takeLast(suffix.size) == suffix

    private fun epubBytes(): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray(Charsets.US_ASCII))
            zip.closeEntry()
        }
        bytes.toByteArray()
    }

    private class Harness(root: File) {
        val events = mutableListOf<String>()
        val storage = FakeStorage(File(root, "storage-${System.nanoTime()}"), events)
        val repository = FakeRepository(events)
        val detector = FakeDetector(events)
        val txtParser = FakeParser("TXT", events)
        val epubParser = FakeParser("EPUB", events)
        val useCase = ImportBookUseCase(
            storage = storage,
            repository = repository,
            txtParser = txtParser,
            epubParser = epubParser,
            sourceTypeDetector = detector,
            ioDispatcher = Dispatchers.Unconfined,
            idGenerator = { BOOK_ID.uppercase() },
            clock = { 123_456L },
        )

        fun request(bytes: ByteArray = "source".toByteArray(Charsets.UTF_8)) = ImportRequest(
            displayName = "novel.txt",
            mimeType = "text/plain",
            openStream = { ByteArrayInputStream(bytes) },
        )
    }

    private class FakeDetector(
        private val events: MutableList<String>,
    ) : SourceTypeDetector {
        var sourceType = SourceType.TXT

        override fun detect(source: File): SourceType {
            events += "detect"
            return sourceType
        }
    }

    private class FakeParser(
        private val label: String,
        private val events: MutableList<String>,
    ) : BookParser {
        var failure: Exception? = null
        var callCount = 0

        override fun parse(source: File, sourceName: String): ParsedBook {
            events += "parse-$label"
            callCount += 1
            failure?.let { throw it }
            return ParsedBook(
                title = "Imported title",
                author = "Author",
                language = "zh",
                coverBytes = PNG_BYTES,
                chapters = listOf(
                    ParsedChapter("First", "Body one"),
                    ParsedChapter("Second", "Body two"),
                ),
            )
        }
    }

    private class FakeRepository(
        private val events: MutableList<String>,
    ) : BookImportRepository {
        var existingBookId: String? = null
        var commitFailure: Throwable? = null
        var committedBook: BookEntity? = null
        var committedChapters: List<ChapterEntity> = emptyList()

        override suspend fun findBookIdBySourceHash(sourceSha256: String): String? {
            events += "lookup"
            return existingBookId
        }

        override suspend fun commitImportedBook(
            book: BookEntity,
            chapters: List<ChapterEntity>,
        ) {
            events += "commit"
            commitFailure?.let { throw it }
            committedBook = book
            committedChapters = chapters
        }
    }

    private class FakeStorage(
        private val root: File,
        private val events: MutableList<String>,
    ) : BookStorage {
        var copyFailure: StorageException? = null
        var metadata: StoredBookMetadata? = null

        override fun begin(transactionId: String): ImportStaging {
            events += "begin"
            val directory = File(root, "cache/import/$transactionId")
            val book = File(directory, "book")
            book.mkdirs()
            return ImportStaging(transactionId, directory, book)
        }

        override fun copySource(
            input: InputStream,
            staging: ImportStaging,
            extension: String,
        ): CopiedSource {
            events += "copy"
            copyFailure?.let { throw it }
            val bytes = input.readBytes()
            val file = File(staging.directory, "source.$extension").apply { writeBytes(bytes) }
            return CopiedSource(file, bytes.size.toLong(), bytes.sha256())
        }

        override fun writeChapter(
            staging: ImportStaging,
            ordinal: Int,
            text: String,
        ): StoredChapter {
            events += "write-chapter-$ordinal"
            val path = "chapter-${ordinal.toString().padStart(4, '0')}.txt"
            File(staging.bookDirectory, path).writeText(text, Charsets.UTF_8)
            return StoredChapter(
                ordinal,
                path,
                text.length,
                text.toByteArray(Charsets.UTF_8).sha256(),
            )
        }

        override fun writeCover(staging: ImportStaging, bytes: ByteArray): String {
            events += "write-cover"
            File(staging.bookDirectory, "cover.png").writeBytes(bytes)
            return "cover.png"
        }

        override fun writeMetadata(staging: ImportStaging, metadata: StoredBookMetadata) {
            events += "write-metadata"
            this.metadata = metadata
        }

        override fun promote(staging: ImportStaging, bookId: String): File {
            events += "promote"
            return File(root, "files/books/$bookId").apply { mkdirs() }
        }

        override fun discard(staging: ImportStaging) {
            events += "discard"
            staging.directory.deleteRecursively()
        }

        override fun deleteBook(bookId: String) {
            events += "delete-book"
            File(root, "files/books/$bookId").deleteRecursively()
        }

        override fun bookExists(bookId: String): Boolean =
            File(root, "files/books/$bookId").isDirectory

        override fun cleanStaleTransactions(nowMillis: Long) = Unit

        override fun cleanOrphanBooks(retainedBookIds: Set<String>) = Unit

        private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val BOOK_ID = "11111111-1111-1111-1111-111111111111"
        val PNG_BYTES = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
    }
}
