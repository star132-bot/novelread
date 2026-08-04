package com.mkread.app.feature.library

import com.mkread.app.core.database.BookEntity
import com.mkread.app.core.database.ChapterEntity
import com.mkread.app.core.files.BookParseException
import com.mkread.app.core.files.BookParseFailure
import com.mkread.app.core.files.BookParser
import com.mkread.app.core.files.BookStorage
import com.mkread.app.core.files.EpubBookParser
import com.mkread.app.core.files.ImportStaging
import com.mkread.app.core.files.SafeZipException
import com.mkread.app.core.files.SafeZipFailure
import com.mkread.app.core.files.SafeZipReader
import com.mkread.app.core.files.StorageException
import com.mkread.app.core.files.StorageFailure
import com.mkread.app.core.files.StoredBookMetadata
import com.mkread.app.core.files.StoredChapterMetadata
import com.mkread.app.core.files.TxtBookParser
import com.mkread.app.core.model.SourceType
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface SourceTypeDetector {
    fun detect(source: File): SourceType
}

class ContentSourceTypeDetector(
    private val zipReader: SafeZipReader = SafeZipReader(),
) : SourceTypeDetector {
    override fun detect(source: File): SourceType {
        val prefix = source.inputStream().use { input ->
            val buffer = ByteArray(ZIP_MAGIC_BYTES)
            var offset = 0
            while (offset < buffer.size) {
                val read = input.read(buffer, offset, buffer.size - offset)
                if (read < 0) break
                if (read > 0) offset += read
            }
            buffer.copyOf(offset)
        }
        if (!prefix.hasZipMagic()) return SourceType.TXT
        try {
            zipReader.open(source).use { archive ->
                val mimetype = try {
                    archive.read(EPUB_MIMETYPE_PATH, EPUB_MIMETYPE.length.toLong() + 1L)
                } catch (failure: SafeZipException) {
                    if (failure.failure == SafeZipFailure.ENTRY_NOT_FOUND) {
                        throw UnsupportedSourceTypeException()
                    }
                    throw failure
                }
                if (mimetype.toString(Charsets.US_ASCII) != EPUB_MIMETYPE) {
                    throw UnsupportedSourceTypeException()
                }
            }
            return SourceType.EPUB
        } catch (failure: UnsupportedSourceTypeException) {
            throw failure
        } catch (failure: SafeZipException) {
            throw BookParseException(
                BookParseFailure.MALFORMED_EPUB,
                "ZIP-like source is not a valid bounded EPUB",
                failure,
            )
        }
    }

    private fun ByteArray.hasZipMagic(): Boolean {
        if (size < ZIP_MAGIC_BYTES || this[0] != 'P'.code.toByte() || this[1] != 'K'.code.toByte()) {
            return false
        }
        return (this[2] == 3.toByte() && this[3] == 4.toByte()) ||
            (this[2] == 5.toByte() && this[3] == 6.toByte()) ||
            (this[2] == 7.toByte() && this[3] == 8.toByte())
    }

    private companion object {
        const val ZIP_MAGIC_BYTES = 4
        const val EPUB_MIMETYPE_PATH = "mimetype"
        const val EPUB_MIMETYPE = "application/epub+zip"
    }
}

class UnsupportedSourceTypeException : Exception("ZIP is not an EPUB")

class ImportBookUseCase(
    private val storage: BookStorage,
    private val repository: BookImportRepository,
    private val txtParser: BookParser = TxtBookParser(),
    private val epubParser: BookParser = EpubBookParser(),
    private val sourceTypeDetector: SourceTypeDetector = ContentSourceTypeDetector(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend operator fun invoke(request: ImportRequest): ImportResult = withContext(ioDispatcher) {
        importOnIo(request)
    }

    private suspend fun importOnIo(request: ImportRequest): ImportResult {
        val bookId = idGenerator().lowercase(Locale.ROOT)
        var staging: ImportStaging? = null
        var promoted = false
        try {
            staging = storage.begin(bookId)
            val input = try {
                request.openStream()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                throw SourceUnavailableException(failure)
            }
            val copied = input.use { source ->
                storage.copySource(source, staging, sourceExtension(request.displayName))
            }
            repository.findBookIdBySourceHash(copied.sha256)?.let { existingBookId ->
                storage.discard(staging)
                return ImportResult.Duplicate(existingBookId)
            }

            val sourceType = sourceTypeDetector.detect(copied.file)
            val parser = when (sourceType) {
                SourceType.TXT -> txtParser
                SourceType.EPUB -> epubParser
            }
            val parsed = parser.parse(copied.file, request.displayName)
            if (parsed.chapters.isEmpty()) {
                throw BookParseException(
                    BookParseFailure.NO_READABLE_CONTENT,
                    "Parsed book contains no chapters",
                )
            }
            val storedChapters = parsed.chapters.mapIndexed { index, chapter ->
                storage.writeChapter(staging, index + 1, chapter.text)
            }
            val coverPath = parsed.coverBytes?.let { bytes -> storage.writeCover(staging, bytes) }
            storage.writeMetadata(
                staging,
                StoredBookMetadata(
                    sourceName = request.displayName,
                    sourceSha256 = copied.sha256,
                    parserVersion = PARSER_VERSION,
                    title = parsed.title,
                    author = parsed.author,
                    language = parsed.language,
                    chapters = storedChapters.mapIndexed { index, stored ->
                        StoredChapterMetadata(
                            ordinal = stored.ordinal,
                            title = parsed.chapters[index].title,
                            relativePath = stored.relativePath,
                            characterCount = stored.characterCount,
                            contentSha256 = stored.contentSha256,
                        )
                    },
                ),
            )
            storage.promote(staging, bookId)
            promoted = true

            val timestamp = clock()
            repository.commitImportedBook(
                book = BookEntity(
                    id = bookId,
                    title = parsed.title,
                    author = parsed.author,
                    sourceType = sourceType,
                    sourceSha256 = copied.sha256,
                    coverRelativePath = coverPath,
                    importedAt = timestamp,
                    modifiedAt = timestamp,
                    lastOpenedAt = null,
                ),
                chapters = storedChapters.mapIndexed { index, stored ->
                    ChapterEntity(
                        id = "$bookId:${stored.ordinal.toString().padStart(4, '0')}",
                        bookId = bookId,
                        ordinal = stored.ordinal,
                        title = parsed.chapters[index].title,
                        relativePath = stored.relativePath,
                        characterCount = stored.characterCount,
                        contentSha256 = stored.contentSha256,
                    )
                },
            )
            return ImportResult.Success(bookId)
        } catch (failure: CancellationException) {
            compensate(staging, promoted, bookId)
            throw failure
        } catch (failure: DuplicateSourceException) {
            compensate(staging, promoted, bookId)
            return failure.existingBookId?.let(ImportResult::Duplicate)
                ?: ImportResult.Failure(
                    ImportFailureCode.INTERNAL_COMMIT,
                    MESSAGE_INTERNAL_COMMIT,
                )
        } catch (failure: Exception) {
            compensate(staging, promoted, bookId)
            return failure.toImportFailure()
        }
    }

    private fun compensate(
        staging: ImportStaging?,
        promoted: Boolean,
        bookId: String,
    ) {
        runCatching {
            if (promoted) {
                storage.deleteBook(bookId)
            } else if (staging != null) {
                storage.discard(staging)
            }
        }
    }

    private fun sourceExtension(displayName: String): String {
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension.takeIf { it == "txt" || it == "epub" } ?: "bin"
    }

    private fun Exception.toImportFailure(): ImportResult.Failure = when (this) {
        is SourceUnavailableException -> ImportResult.Failure(
            ImportFailureCode.SOURCE_UNAVAILABLE,
            "无法读取所选文件，请重新选择",
        )
        is UnsupportedSourceTypeException -> ImportResult.Failure(
            ImportFailureCode.UNSUPPORTED_TYPE,
            "文件不是受支持的 TXT 或 EPUB",
        )
        is StorageException -> when (failure) {
            StorageFailure.SOURCE_TOO_LARGE,
            StorageFailure.CHAPTER_TOO_LARGE,
            StorageFailure.COVER_TOO_LARGE,
            -> ImportResult.Failure(ImportFailureCode.SIZE_LIMIT, "文件超过导入大小限制")
            StorageFailure.IO_ERROR -> ImportResult.Failure(
                ImportFailureCode.STORAGE_FULL,
                "无法写入本地存储，请检查可用空间",
            )
            else -> ImportResult.Failure(ImportFailureCode.INTERNAL_COMMIT, MESSAGE_INTERNAL_COMMIT)
        }
        is BookParseException -> when (failure) {
            BookParseFailure.INVALID_ENCODING,
            BookParseFailure.INVALID_CONTENT,
            -> ImportResult.Failure(ImportFailureCode.ENCODING, "无法识别 TXT 文件编码或内容")
            BookParseFailure.MALFORMED_EPUB -> ImportResult.Failure(
                ImportFailureCode.MALFORMED_EPUB,
                "EPUB 文件损坏或结构不受支持",
            )
            BookParseFailure.NO_READABLE_CONTENT -> ImportResult.Failure(
                ImportFailureCode.NO_READABLE_CONTENT,
                "文件中没有可阅读的正文",
            )
            BookParseFailure.SOURCE_TOO_LARGE,
            BookParseFailure.CHAPTER_TOO_LARGE,
            -> ImportResult.Failure(ImportFailureCode.SIZE_LIMIT, "文件超过导入大小限制")
        }
        else -> ImportResult.Failure(ImportFailureCode.INTERNAL_COMMIT, MESSAGE_INTERNAL_COMMIT)
    }

    private class SourceUnavailableException(cause: Throwable) : IOException(cause)

    private companion object {
        const val PARSER_VERSION = 1
        const val MESSAGE_INTERNAL_COMMIT = "导入未完成，请重试"
    }
}
