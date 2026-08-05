package com.mkread.app.core.files

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var storage: FileBookStorage

    @Before
    fun createStorage() {
        filesDir = temporaryFolder.newFolder("files")
        cacheDir = temporaryFolder.newFolder("cache")
        storage = FileBookStorage(filesDir = filesDir, cacheDir = cacheDir)
    }

    @Test
    fun begin_createsTransactionUnderCacheImportRoot() {
        val staging = storage.begin("transaction-1")

        assertEquals(
            File(cacheDir, "import/transaction-1").canonicalFile,
            staging.directory.canonicalFile,
        )
        assertTrue(staging.directory.isDirectory)
        assertTrue(staging.bookDirectory.isDirectory)
    }

    @Test
    fun copySource_streamsBytesAndReturnsSha256() {
        val bytes = ByteArray(ImportLimits.COPY_BUFFER_BYTES + 17) { index -> index.toByte() }
        val staging = storage.begin("copy")

        val copied = storage.copySource(ByteArrayInputStream(bytes), staging, "txt")

        assertEquals(bytes.size.toLong(), copied.byteCount)
        assertEquals(bytes.sha256(), copied.sha256)
        assertEquals("source.txt", copied.file.name)
        assertArrayEquals(bytes, copied.file.readBytes())
        assertFalse(File(staging.bookDirectory, "source.txt.tmp").exists())
    }

    @Test
    fun copySource_rejectsSourceLimitPlusOne_andRemovesPartialFile() {
        val staging = storage.begin("too-large")

        assertStorageFailure(StorageFailure.SOURCE_TOO_LARGE) {
            storage.copySource(
                LimitPlusOneInputStream(ImportLimits.SOURCE_BYTES + 1L),
                staging,
                "txt",
            )
        }

        assertFalse(File(staging.bookDirectory, "source.txt").exists())
        assertFalse(File(staging.bookDirectory, "source.txt.tmp").exists())
    }

    @Test
    fun writeChapter_usesNormalizedNameAndUtf8() {
        val staging = storage.begin("chapter")
        val text = "First paragraph.\n\nSecond paragraph."

        val chapter = storage.writeChapter(staging, ordinal = 1, text = text)

        assertEquals("chapter-0001.txt", chapter.relativePath)
        assertEquals(text, File(staging.bookDirectory, chapter.relativePath).readText(Charsets.UTF_8))
        assertEquals(text.length, chapter.characterCount)
        assertEquals(text.toByteArray(Charsets.UTF_8).sha256(), chapter.contentSha256)
    }

    @Test
    fun writeCover_usesImageSignatureForStableRelativeName() {
        val staging = storage.begin("cover")
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x00,
        )

        val relativePath = storage.writeCover(staging, png)

        assertEquals("cover.png", relativePath)
        assertArrayEquals(png, File(staging.bookDirectory, relativePath).readBytes())
    }

    @Test
    fun writeCover_rejectsUnsupportedImageSignature() {
        val staging = storage.begin("tx-cover-invalid")

        assertStorageFailure(StorageFailure.UNSUPPORTED_COVER) {
            storage.writeCover(staging, "not-an-image".toByteArray(Charsets.UTF_8))
        }

        assertFalse(File(staging.bookDirectory, "cover.png").exists())
    }

    @Test
    fun discard_removesOnlyTheSelectedTransaction() {
        val discarded = storage.begin("discarded")
        val retained = storage.begin("retained")

        storage.discard(discarded)

        assertFalse(discarded.directory.exists())
        assertTrue(retained.directory.exists())
    }

    @Test
    fun promote_atomicallyMovesNormalizedBookAndCleansTransaction() {
        val staging = storage.begin("promote")
        val sourceBytes = "Original source bytes".toByteArray(Charsets.UTF_8)
        val source = storage.copySource(ByteArrayInputStream(sourceBytes), staging, "txt")
        val chapter = storage.writeChapter(staging, ordinal = 1, text = "Body")
        storage.writeMetadata(staging, metadata(chapter))

        val promoted = storage.promote(staging, "book-1")

        assertEquals(File(filesDir, "books/book-1").canonicalFile, promoted.canonicalFile)
        assertArrayEquals(sourceBytes, File(promoted, source.file.name).readBytes())
        assertEquals("Body", File(promoted, chapter.relativePath).readText(Charsets.UTF_8))
        assertTrue(File(promoted, "metadata.json").readText().contains("source.txt"))
        assertFalse(staging.directory.exists())
    }

    @Test
    fun bookExists_reportsOnlyPromotedPrivateBookDirectories() {
        val staging = storage.begin("exists")
        storage.writeChapter(staging, ordinal = 1, text = "Body")

        assertFalse(storage.bookExists("book-exists"))
        storage.promote(staging, "book-exists")

        assertTrue(storage.bookExists("book-exists"))
        assertFalse(storage.bookExists("other-book"))
    }

    @Test
    fun promote_moveFailure_removesStagingAndPartialTarget() {
        val failingStorage = FileBookStorage(
            filesDir = filesDir,
            cacheDir = cacheDir,
            directoryMover = DirectoryMover { _, target ->
                target.mkdirs()
                File(target, "partial").writeText("partial")
                throw IOException("injected move failure")
            },
        )
        val staging = failingStorage.begin("move-failure")
        failingStorage.writeChapter(staging, ordinal = 1, text = "Body")

        assertStorageFailure(StorageFailure.PROMOTION_FAILED) {
            failingStorage.promote(staging, "book-1")
        }

        assertFalse(staging.directory.exists())
        assertFalse(File(filesDir, "books/book-1").exists())
    }

    @Test
    fun importingAndDeletingBook_neverChangesExternalSource() {
        val externalDir = temporaryFolder.newFolder("external")
        val externalSource = File(externalDir, "novel.txt").apply {
            writeText("External original", Charsets.UTF_8)
        }
        val original = externalSource.readBytes()
        val sentinel = File(externalDir, "sentinel.keep").apply { writeText("keep") }
        val staging = storage.begin("external-source")

        FileInputStream(externalSource).use { input ->
            storage.copySource(input, staging, "txt")
        }
        storage.writeChapter(staging, ordinal = 1, text = "Normalized")
        storage.promote(staging, "book-1")
        storage.deleteBook("book-1")

        assertArrayEquals(original, externalSource.readBytes())
        assertTrue(sentinel.exists())
        assertFalse(File(filesDir, "books/book-1").exists())
    }

    @Test
    fun callerSuppliedPaths_cannotEscapePrivateRoots() {
        val external = File(temporaryFolder.root, "outside.keep").apply { writeText("keep") }

        assertStorageFailure(StorageFailure.INVALID_PATH) {
            storage.begin("../outside")
        }
        assertStorageFailure(StorageFailure.INVALID_PATH) {
            storage.deleteBook("../outside.keep")
        }

        assertTrue(external.exists())
    }

    @Test
    fun cleanup_isBoundedToStaleTransactionsAndOrphanBooks() {
        val now = 5L * ImportLimits.STALE_TRANSACTION_MILLIS
        val stale = storage.begin("stale")
        val current = storage.begin("current")
        assertTrue(stale.directory.setLastModified(now - ImportLimits.STALE_TRANSACTION_MILLIS - 1L))
        assertTrue(current.directory.setLastModified(now))

        val booksRoot = File(filesDir, "books")
        val retained = File(booksRoot, "retained").apply { mkdirs() }
        val orphan = File(booksRoot, "orphan").apply { mkdirs() }
        val external = File(temporaryFolder.root, "outside-dir").apply { mkdirs() }

        storage.cleanStaleTransactions(now)
        storage.cleanOrphanBooks(setOf("retained"))

        assertFalse(stale.directory.exists())
        assertTrue(current.directory.exists())
        assertTrue(retained.exists())
        assertFalse(orphan.exists())
        assertTrue(external.exists())
    }

    private fun metadata(chapter: StoredChapter) = StoredBookMetadata(
        sourceName = "source.txt",
        sourceSha256 = "source-hash",
        parserVersion = 1,
        title = "Book",
        author = null,
        language = null,
        chapters = listOf(
            StoredChapterMetadata(
                ordinal = chapter.ordinal,
                title = "Chapter 1",
                relativePath = chapter.relativePath,
                characterCount = chapter.characterCount,
                contentSha256 = chapter.contentSha256,
            ),
        ),
    )

    private fun assertStorageFailure(
        expected: StorageFailure,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("Expected storage failure $expected")
        } catch (failure: StorageException) {
            assertEquals(expected, failure.failure)
        }
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private class LimitPlusOneInputStream(
        private val size: Long,
    ) : InputStream() {
        private var position = 0L

        override fun read(): Int = if (position++ < size) 0 else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            val count = minOf(length.toLong(), size - position).toInt()
            buffer.fill(0, offset, offset + count)
            position += count
            return count
        }
    }
}
