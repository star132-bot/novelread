package com.mkread.app.feature.library

import com.mkread.app.core.database.BookEntity
import com.mkread.app.core.database.ChapterEntity
import com.mkread.app.core.files.BookStorage
import com.mkread.app.core.files.ImportStaging
import com.mkread.app.core.files.StoredBookMetadata
import com.mkread.app.core.files.StoredChapter
import com.mkread.app.core.files.StoredChapterMetadata
import com.mkread.app.core.model.SourceType
import com.mkread.app.feature.reader.ChapterContentRepository
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class BookFragmentSource(
    val bookId: String,
    val title: String,
)

data class BookAssemblyRequest(
    val title: String,
    val folderId: String?,
    val fragments: List<BookFragmentSource>,
)

data class BookAssemblyResult(
    val bookId: String,
    val chapterCount: Int,
    val removedFragmentCount: Int,
)

fun interface BookFragmentAssembler {
    suspend fun assemble(request: BookAssemblyRequest): BookAssemblyResult
}

class AssembleBookFragmentsUseCase(
    private val storage: BookStorage,
    private val repository: BookAssemblyRepository,
    private val contentRepository: ChapterContentRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) : BookFragmentAssembler {
    override suspend fun assemble(request: BookAssemblyRequest): BookAssemblyResult =
        withContext(ioDispatcher) { assembleOnIo(request) }

    private suspend fun assembleOnIo(request: BookAssemblyRequest): BookAssemblyResult {
        val metadata = normalizeBookMetadata(request.title, author = null)
        require(request.fragments.size >= 2) { "At least two fragment books are required" }
        require(request.fragments.map(BookFragmentSource::bookId).distinct().size == request.fragments.size) {
            "Fragment books must be unique"
        }

        val chapters = request.fragments.flatMap { fragment ->
            val sourceChapters = contentRepository.listChapters(fragment.bookId)
                .sortedWith(compareBy<ChapterEntity> { it.ordinal }.thenBy { it.id })
            require(sourceChapters.isNotEmpty()) { "Fragment ${fragment.bookId} has no chapters" }
            sourceChapters.map { sourceChapter ->
                val content = contentRepository.load(sourceChapter.id)
                AssemblyChapter(
                    title = if (sourceChapters.size == 1) {
                        cleanFragmentTitle(fragment.title)
                    } else {
                        sourceChapter.title.trim().ifBlank { cleanFragmentTitle(fragment.title) }
                    },
                    text = content.text,
                    sourceContentSha256 = content.contentSha256,
                )
            }
        }
        val sourceSha256 = assemblyHash(request.fragments, chapters)
        repository.findBookIdBySourceHash(sourceSha256)?.let { existingBookId ->
            return reuseExistingAssembly(existingBookId, request, chapters)
        }

        val bookId = idGenerator().lowercase(Locale.ROOT)
        var staging: ImportStaging? = null
        var promoted = false
        lateinit var storedChapters: List<StoredChapter>
        try {
            staging = storage.begin(bookId)
            storedChapters = chapters.mapIndexed { index, chapter ->
                storage.writeChapter(staging, index + 1, chapter.text)
            }
            storage.writeMetadata(
                staging,
                StoredBookMetadata(
                    sourceName = "${metadata.title}.mkread-assembly",
                    sourceSha256 = sourceSha256,
                    parserVersion = ASSEMBLY_VERSION,
                    title = metadata.title,
                    author = null,
                    language = null,
                    chapters = storedChapters.mapIndexed { index, stored ->
                        StoredChapterMetadata(
                            ordinal = stored.ordinal,
                            title = chapters[index].title,
                            relativePath = stored.relativePath,
                            characterCount = stored.characterCount,
                            contentSha256 = stored.contentSha256,
                        )
                    },
                ),
            )
            storage.promote(staging, bookId)
            promoted = true
        } catch (failure: CancellationException) {
            compensate(staging, promoted, bookId)
            throw failure
        } catch (failure: Exception) {
            compensate(staging, promoted, bookId)
            throw failure
        }

        val timestamp = clock()
        val book = BookEntity(
            id = bookId,
            title = metadata.title,
            author = null,
            sourceType = SourceType.TXT,
            sourceSha256 = sourceSha256,
            coverRelativePath = null,
            importedAt = timestamp,
            modifiedAt = timestamp,
            lastOpenedAt = null,
            folderId = request.folderId,
        )
        val chapterEntities = storedChapters.mapIndexed { index, stored ->
            ChapterEntity(
                id = "$bookId:${stored.ordinal.toString().padStart(4, '0')}",
                bookId = bookId,
                ordinal = stored.ordinal,
                title = chapters[index].title,
                relativePath = stored.relativePath,
                characterCount = stored.characterCount,
                contentSha256 = stored.contentSha256,
            )
        }
        var commitCompleted = false
        try {
            withContext(NonCancellable) {
                repository.commitImportedBook(book, chapterEntities)
                commitCompleted = true
            }
        } catch (failure: CancellationException) {
            val retained = commitCompleted || isCommittedBook(bookId, sourceSha256)
            if (!retained) compensate(staging, promoted, bookId)
            throw failure
        } catch (failure: DuplicateSourceException) {
            compensate(staging, promoted, bookId)
            val existingBookId = failure.existingBookId ?: throw failure
            return reuseExistingAssembly(existingBookId, request, chapters)
        } catch (failure: Exception) {
            val retained = commitCompleted || isCommittedBook(bookId, sourceSha256)
            if (!retained) compensate(staging, promoted, bookId)
            throw failure
        }

        return BookAssemblyResult(
            bookId = bookId,
            chapterCount = chapters.size,
            removedFragmentCount = removeFragments(request.fragments, bookId),
        )
    }

    private suspend fun reuseExistingAssembly(
        existingBookId: String,
        request: BookAssemblyRequest,
        expectedChapters: List<AssemblyChapter>,
    ): BookAssemblyResult {
        verifyRetainedAssembly(existingBookId, expectedChapters)
        request.folderId?.let { repository.moveBookToFolder(existingBookId, it) }
        return BookAssemblyResult(
            bookId = existingBookId,
            chapterCount = expectedChapters.size,
            removedFragmentCount = removeFragments(request.fragments, existingBookId),
        )
    }

    private suspend fun verifyRetainedAssembly(
        bookId: String,
        expectedChapters: List<AssemblyChapter>,
    ) {
        val retainedChapters = contentRepository.listChapters(bookId)
            .sortedWith(compareBy<ChapterEntity> { it.ordinal }.thenBy { it.id })
        check(retainedChapters.size == expectedChapters.size) {
            "Existing assembled book is incomplete"
        }
        retainedChapters.zip(expectedChapters).forEachIndexed { index, (chapter, expected) ->
            check(chapter.ordinal == index + 1 && chapter.title == expected.title) {
                "Existing assembled book chapter order is invalid"
            }
            val content = contentRepository.load(chapter.id)
            check(content.contentSha256.equals(expected.sourceContentSha256, ignoreCase = true)) {
                "Existing assembled book chapter content is invalid"
            }
        }
    }

    private suspend fun isCommittedBook(bookId: String, sourceSha256: String): Boolean =
        withContext(NonCancellable) {
            runCatching { repository.findBookIdBySourceHash(sourceSha256) == bookId }
                .getOrDefault(false)
        }

    private suspend fun removeFragments(
        fragments: List<BookFragmentSource>,
        retainedBookId: String,
    ): Int {
        var removed = 0
        fragments.forEach { fragment ->
            if (fragment.bookId == retainedBookId) return@forEach
            try {
                if (repository.removeBook(fragment.bookId)) removed++
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                Unit
            }
        }
        return removed
    }

    private fun compensate(staging: ImportStaging?, promoted: Boolean, bookId: String) {
        runCatching {
            if (promoted) storage.deleteBook(bookId) else if (staging != null) storage.discard(staging)
        }
    }

    private companion object {
        const val ASSEMBLY_VERSION = 1
    }
}

private data class AssemblyChapter(
    val title: String,
    val text: String,
    val sourceContentSha256: String,
)

internal fun naturallyOrderBookFragments(
    fragments: List<BookFragmentSource>,
): List<BookFragmentSource> = fragments.sortedWith(
    compareBy<BookFragmentSource> { fragmentOrderGroup(it.title) }
        .thenBy { leadingNumber(it.title) ?: Long.MAX_VALUE }
        .thenBy { it.title.lowercase(Locale.ROOT) }
        .thenBy { it.bookId },
)

internal fun moveBookFragment(
    fragments: List<BookFragmentSource>,
    fromIndex: Int,
    toIndex: Int,
): List<BookFragmentSource> {
    if (fromIndex !in fragments.indices || toIndex !in fragments.indices || fromIndex == toIndex) {
        return fragments
    }
    return fragments.toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}

private fun fragmentOrderGroup(title: String): Int = when {
    title.trim().startsWith("序章") || title.trim().startsWith("楔子") -> 0
    leadingNumber(title) != null -> 1
    BACK_MATTER.containsMatchIn(title.trim()) -> 3
    else -> 2
}

private fun leadingNumber(title: String): Long? = LEADING_NUMBER.find(title)
    ?.groupValues
    ?.get(1)
    ?.toLongOrNull()

private fun cleanFragmentTitle(title: String): String {
    val normalized = title.trim()
    val withoutPrefix = LEADING_NUMBER_WITH_SEPARATOR.replaceFirst(normalized, "").trim()
    return withoutPrefix.ifBlank { normalized }
}

private fun assemblyHash(
    fragments: List<BookFragmentSource>,
    chapters: List<AssemblyChapter>,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun update(value: String) {
        digest.update(value.toByteArray(Charsets.UTF_8))
        digest.update(0)
    }
    update("mkread-book-assembly-v1")
    fragments.forEach { fragment ->
        update(fragment.bookId)
        update(fragment.title)
    }
    chapters.forEach { chapter ->
        update(chapter.title)
        update(chapter.sourceContentSha256)
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private val LEADING_NUMBER = Regex("^\\s*(\\d{1,9})(?=\\D|$)")
private val LEADING_NUMBER_WITH_SEPARATOR = Regex("^\\s*\\d{1,9}\\s*(?:[._\\-、:：]\\s*)?")
private val BACK_MATTER = Regex("^(?:后记|尾声|番外|插图|特典|附录)")
