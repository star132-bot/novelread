package com.mkread.app.core.files

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface BookStorage {
    fun begin(transactionId: String): ImportStaging

    fun copySource(
        input: InputStream,
        staging: ImportStaging,
        extension: String,
    ): CopiedSource

    fun writeChapter(
        staging: ImportStaging,
        ordinal: Int,
        text: String,
    ): StoredChapter

    fun writeMetadata(
        staging: ImportStaging,
        metadata: StoredBookMetadata,
    )

    fun writeCover(staging: ImportStaging, bytes: ByteArray): String

    fun promote(staging: ImportStaging, bookId: String): File

    fun discard(staging: ImportStaging)

    fun deleteBook(bookId: String)

    fun cleanStaleTransactions(nowMillis: Long)

    fun cleanOrphanBooks(retainedBookIds: Set<String>)
}

class ImportStaging internal constructor(
    val transactionId: String,
    val directory: File,
    val bookDirectory: File,
)

data class CopiedSource(
    val file: File,
    val byteCount: Long,
    val sha256: String,
)

data class StoredChapter(
    val ordinal: Int,
    val relativePath: String,
    val characterCount: Int,
    val contentSha256: String,
)

@Serializable
data class StoredBookMetadata(
    val schemaVersion: Int = 1,
    val sourceName: String,
    val sourceSha256: String,
    val parserVersion: Int,
    val title: String,
    val author: String?,
    val language: String?,
    val chapters: List<StoredChapterMetadata>,
)

@Serializable
data class StoredChapterMetadata(
    val ordinal: Int,
    val title: String,
    val relativePath: String,
    val characterCount: Int,
    val contentSha256: String,
)

fun interface DirectoryMover {
    @Throws(IOException::class)
    fun move(source: File, target: File)
}

class FileBookStorage(
    filesDir: File,
    cacheDir: File,
    private val directoryMover: DirectoryMover = DirectoryMover { source, target ->
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
        )
    },
) : BookStorage {
    private val filesRoot = filesDir.toPath().toAbsolutePath().normalize()
    private val cacheRoot = cacheDir.toPath().toAbsolutePath().normalize()
    private val booksRoot = containedPath(filesRoot, "books")
    private val importsRoot = containedPath(cacheRoot, "import")
    private val metadataJson = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
    }

    init {
        createDirectories(booksRoot)
        createDirectories(importsRoot)
    }

    override fun begin(transactionId: String): ImportStaging {
        requireSafeSegment(transactionId)
        val transaction = containedPath(importsRoot, transactionId)
        val book = containedPath(transaction, BOOK_DIRECTORY)
        if (Files.exists(transaction)) {
            throw StorageException(
                StorageFailure.TRANSACTION_EXISTS,
                "Import transaction already exists: $transactionId",
            )
        }
        try {
            Files.createDirectory(transaction)
            Files.createDirectory(book)
        } catch (failure: IOException) {
            transaction.toFile().deleteRecursively()
            throw StorageException(
                StorageFailure.IO_ERROR,
                "Unable to create import transaction: $transactionId",
                failure,
            )
        }
        return ImportStaging(
            transactionId = transactionId,
            directory = transaction.toFile(),
            bookDirectory = book.toFile(),
        )
    }

    override fun copySource(
        input: InputStream,
        staging: ImportStaging,
        extension: String,
    ): CopiedSource {
        val transaction = requireStaging(staging)
        val safeExtension = normalizedExtension(extension)
        val target = containedPath(transaction, "source.$safeExtension")
        val temporary = containedPath(transaction, "source.$safeExtension.tmp")
        try {
            val result = FileOutputStream(temporary.toFile()).use { output ->
                FileHash.copyBounded(input, output, ImportLimits.SOURCE_BYTES).also {
                    output.flush()
                    output.fd.sync()
                }
            }
            atomicReplace(temporary, target)
            return CopiedSource(
                file = target.toFile(),
                byteCount = result.byteCount,
                sha256 = result.sha256,
            )
        } catch (failure: ByteLimitExceededException) {
            temporary.toFile().delete()
            target.toFile().delete()
            throw StorageException(
                StorageFailure.SOURCE_TOO_LARGE,
                "Source exceeds the ${ImportLimits.SOURCE_BYTES} byte limit",
                failure,
            )
        } catch (failure: IOException) {
            temporary.toFile().delete()
            target.toFile().delete()
            throw StorageException(
                StorageFailure.IO_ERROR,
                "Unable to copy source into the import transaction",
                failure,
            )
        }
    }

    override fun writeChapter(
        staging: ImportStaging,
        ordinal: Int,
        text: String,
    ): StoredChapter {
        if (ordinal !in 1..ImportLimits.ZIP_ENTRIES) {
            throw StorageException(StorageFailure.INVALID_PATH, "Invalid chapter ordinal: $ordinal")
        }
        if (text.length > ImportLimits.CHAPTER_CHARACTERS) {
            throw StorageException(
                StorageFailure.CHAPTER_TOO_LARGE,
                "Chapter exceeds the ${ImportLimits.CHAPTER_CHARACTERS} character limit",
            )
        }
        val transaction = requireStaging(staging)
        val bookDirectory = containedPath(transaction, BOOK_DIRECTORY)
        val relativePath = "chapter-${ordinal.toString().padStart(4, '0')}.txt"
        val target = containedPath(bookDirectory, relativePath)
        val temporary = containedPath(bookDirectory, "$relativePath.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                val writer = OutputStreamWriter(
                    DigestOutputStream(output, digest),
                    StandardCharsets.UTF_8,
                )
                writer.write(text)
                writer.flush()
                output.fd.sync()
            }
            atomicReplace(temporary, target)
        } catch (failure: IOException) {
            temporary.toFile().delete()
            throw StorageException(
                StorageFailure.IO_ERROR,
                "Unable to write chapter $ordinal",
                failure,
            )
        }
        return StoredChapter(
            ordinal = ordinal,
            relativePath = relativePath,
            characterCount = text.length,
            contentSha256 = digest.digest().toHex(),
        )
    }

    override fun writeMetadata(
        staging: ImportStaging,
        metadata: StoredBookMetadata,
    ) {
        require(metadata.schemaVersion == 1) { "Unsupported metadata schema" }
        val transaction = requireStaging(staging)
        val bookDirectory = containedPath(transaction, BOOK_DIRECTORY)
        val target = containedPath(bookDirectory, METADATA_FILE)
        val temporary = containedPath(bookDirectory, "$METADATA_FILE.tmp")
        try {
            writeUtf8AndSync(temporary, metadataJson.encodeToString(metadata))
            atomicReplace(temporary, target)
        } catch (failure: IOException) {
            temporary.toFile().delete()
            throw StorageException(
                StorageFailure.IO_ERROR,
                "Unable to write book metadata",
                failure,
            )
        }
    }

    override fun writeCover(staging: ImportStaging, bytes: ByteArray): String {
        if (bytes.size.toLong() > ImportLimits.COVER_BYTES) {
            throw StorageException(
                StorageFailure.COVER_TOO_LARGE,
                "Cover exceeds the ${ImportLimits.COVER_BYTES} byte limit",
            )
        }
        val extension = coverExtension(bytes)
            ?: throw StorageException(
                StorageFailure.UNSUPPORTED_COVER,
                "Cover is not a supported JPEG, PNG, or WebP image",
            )
        val transaction = requireStaging(staging)
        val bookDirectory = containedPath(transaction, BOOK_DIRECTORY)
        val target = containedPath(bookDirectory, "cover.$extension")
        val temporary = containedPath(bookDirectory, "cover.$extension.tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            atomicReplace(temporary, target)
        } catch (failure: IOException) {
            temporary.toFile().delete()
            throw StorageException(StorageFailure.IO_ERROR, "Unable to write cover", failure)
        }
        return target.fileName.toString()
    }

    override fun promote(staging: ImportStaging, bookId: String): File {
        requireSafeSegment(bookId)
        val transaction = requireStaging(staging)
        val source = containedPath(transaction, BOOK_DIRECTORY).toFile()
        val target = containedPath(booksRoot, bookId).toFile()
        if (target.exists()) {
            throw StorageException(StorageFailure.BOOK_EXISTS, "Book already exists: $bookId")
        }
        try {
            directoryMover.move(source, target)
        } catch (failure: Exception) {
            target.deleteRecursively()
            transaction.toFile().deleteRecursively()
            throw StorageException(
                StorageFailure.PROMOTION_FAILED,
                "Unable to promote import transaction for book $bookId",
                failure,
            )
        }
        transaction.toFile().deleteRecursively()
        return target
    }

    override fun discard(staging: ImportStaging) {
        val transaction = stagingPath(staging).toFile()
        if (transaction.exists() && !transaction.deleteRecursively()) {
            throw StorageException(
                StorageFailure.IO_ERROR,
                "Unable to discard import transaction: ${staging.transactionId}",
            )
        }
    }

    override fun deleteBook(bookId: String) {
        requireSafeSegment(bookId)
        val target = containedPath(booksRoot, bookId).toFile()
        if (target.exists() && !target.deleteRecursively()) {
            throw StorageException(StorageFailure.IO_ERROR, "Unable to delete book: $bookId")
        }
    }

    override fun cleanStaleTransactions(nowMillis: Long) {
        val cutoff = nowMillis - ImportLimits.STALE_TRANSACTION_MILLIS
        importsRoot.toFile().listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.lastModified() < cutoff }
            .forEach { transaction ->
                val candidate = transaction.toPath().toAbsolutePath().normalize()
                requireDirectChild(importsRoot, candidate)
                if (!transaction.deleteRecursively()) {
                    throw StorageException(
                        StorageFailure.IO_ERROR,
                        "Unable to delete stale transaction: ${transaction.name}",
                    )
                }
            }
    }

    override fun cleanOrphanBooks(retainedBookIds: Set<String>) {
        retainedBookIds.forEach(::requireSafeSegment)
        booksRoot.toFile().listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name !in retainedBookIds }
            .forEach { book ->
                val candidate = book.toPath().toAbsolutePath().normalize()
                requireDirectChild(booksRoot, candidate)
                if (!book.deleteRecursively()) {
                    throw StorageException(
                        StorageFailure.IO_ERROR,
                        "Unable to delete orphan book: ${book.name}",
                    )
                }
            }
    }

    private fun requireStaging(staging: ImportStaging): Path {
        val actual = stagingPath(staging)
        val actualBook = staging.bookDirectory.toPath().toAbsolutePath().normalize()
        if (!Files.isDirectory(actual) || !Files.isDirectory(actualBook)) {
            throw StorageException(StorageFailure.IO_ERROR, "Import staging no longer exists")
        }
        return actual
    }

    private fun stagingPath(staging: ImportStaging): Path {
        requireSafeSegment(staging.transactionId)
        val expected = containedPath(importsRoot, staging.transactionId)
        val actual = staging.directory.toPath().toAbsolutePath().normalize()
        val actualBook = staging.bookDirectory.toPath().toAbsolutePath().normalize()
        if (actual != expected || actualBook != containedPath(expected, BOOK_DIRECTORY)) {
            throw StorageException(StorageFailure.INVALID_PATH, "Invalid import staging path")
        }
        return actual
    }

    private fun coverExtension(bytes: ByteArray): String? = when {
        bytes.startsWith(PNG_SIGNATURE) -> "png"
        bytes.startsWith(JPEG_SIGNATURE) -> "jpg"
        bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).contentEquals(RIFF_SIGNATURE) &&
            bytes.copyOfRange(8, 12).contentEquals(WEBP_SIGNATURE) -> "webp"
        else -> null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

    private fun normalizedExtension(extension: String): String {
        val normalized = extension.removePrefix(".").lowercase(Locale.ROOT)
        if (!EXTENSION.matches(normalized)) {
            throw StorageException(StorageFailure.INVALID_PATH, "Invalid source extension")
        }
        return normalized
    }

    private fun requireSafeSegment(value: String) {
        if (!SAFE_SEGMENT.matches(value) || value == "." || value == "..") {
            throw StorageException(StorageFailure.INVALID_PATH, "Invalid path segment")
        }
    }

    private fun containedPath(root: Path, relativePath: String): Path {
        val supplied = Paths.get(relativePath)
        if (supplied.isAbsolute) {
            throw StorageException(StorageFailure.INVALID_PATH, "Absolute paths are not allowed")
        }
        val candidate = root.resolve(supplied).normalize()
        if (!candidate.startsWith(root)) {
            throw StorageException(StorageFailure.INVALID_PATH, "Path leaves the private root")
        }
        return candidate
    }

    private fun requireDirectChild(root: Path, candidate: Path) {
        if (!candidate.startsWith(root) || candidate.parent != root) {
            throw StorageException(StorageFailure.INVALID_PATH, "Path is not a direct private child")
        }
    }

    private fun createDirectories(path: Path) {
        try {
            Files.createDirectories(path)
        } catch (failure: IOException) {
            throw StorageException(StorageFailure.IO_ERROR, "Unable to create private storage", failure)
        }
    }

    private fun writeUtf8AndSync(path: Path, value: String) {
        FileOutputStream(path.toFile()).use { output ->
            val writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
            writer.write(value)
            writer.flush()
            output.fd.sync()
        }
    }

    private fun atomicReplace(source: Path, target: Path) {
        Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private companion object {
        val SAFE_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val EXTENSION = Regex("[a-z0-9]{1,10}")
        const val BOOK_DIRECTORY = "book"
        const val METADATA_FILE = "metadata.json"
        val JPEG_SIGNATURE = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        val RIFF_SIGNATURE = "RIFF".toByteArray(Charsets.US_ASCII)
        val WEBP_SIGNATURE = "WEBP".toByteArray(Charsets.US_ASCII)
    }
}
