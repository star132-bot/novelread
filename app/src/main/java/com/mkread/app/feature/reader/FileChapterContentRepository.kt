package com.mkread.app.feature.reader

import com.mkread.app.core.database.ChapterEntity
import com.mkread.app.core.files.ImportLimits
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FileChapterContentRepository(
    filesDir: java.io.File,
    private val metadataSource: ChapterMetadataSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxCharacters: Int = ImportLimits.CHAPTER_CHARACTERS,
) : ChapterContentRepository {
    private val booksRoot = filesDir.toPath().toAbsolutePath().normalize().resolve("books")

    init {
        require(maxCharacters > 0) { "Chapter character limit must be positive" }
    }

    override suspend fun load(chapterId: String): ChapterContent = withContext(ioDispatcher) {
        val chapter = metadataSource.getChapter(chapterId)
            ?: throw ChapterContentException(
                ChapterContentFailure.NOT_FOUND,
                "Chapter metadata was not found: $chapterId",
            )
        val path = resolveChapterPath(chapter)
        val (text, actualHash) = readUtf8(path)
        if (!actualHash.equals(chapter.contentSha256, ignoreCase = true)) {
            throw ChapterContentException(
                ChapterContentFailure.HASH_MISMATCH,
                "Chapter content hash does not match Room metadata: $chapterId",
            )
        }
        ChapterContent(chapter, text, actualHash)
    }

    override suspend fun listChapters(bookId: String): List<ChapterEntity> =
        metadataSource.getChapters(bookId)

    private fun resolveChapterPath(chapter: ChapterEntity): Path {
        if (!SAFE_SEGMENT.matches(chapter.bookId)) {
            throw unsafePath(chapter.id)
        }
        val relative = try {
            Paths.get(chapter.relativePath)
        } catch (failure: InvalidPathException) {
            throw unsafePath(chapter.id, failure)
        }
        if (
            relative.isAbsolute ||
            relative.nameCount == 0 ||
            relative.any { part -> part.toString() == "." || part.toString() == ".." }
        ) {
            throw unsafePath(chapter.id)
        }

        val bookRoot = booksRoot.resolve(chapter.bookId).normalize()
        val target = bookRoot.resolve(relative).normalize()
        if (bookRoot.parent != booksRoot || !target.startsWith(bookRoot)) {
            throw unsafePath(chapter.id)
        }
        if (!Files.isDirectory(bookRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(bookRoot)) {
            throw ChapterContentException(
                ChapterContentFailure.NOT_FOUND,
                "Book directory was not found for chapter ${chapter.id}",
            )
        }

        var current = bookRoot
        relative.forEach { part ->
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) throw unsafePath(chapter.id)
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw ChapterContentException(
                ChapterContentFailure.NOT_FOUND,
                "Chapter file was not found: ${chapter.id}",
            )
        }
        return target
    }

    private fun readUtf8(path: Path): Pair<String, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        try {
            val text = DigestInputStream(Files.newInputStream(path), digest).use { input ->
                InputStreamReader(input, decoder).use { reader ->
                    val output = StringBuilder(minOf(maxCharacters, DEFAULT_CAPACITY))
                    val buffer = CharArray(READ_BUFFER_CHARACTERS)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        if (output.length + count > maxCharacters) {
                            throw ChapterContentException(
                                ChapterContentFailure.TOO_LARGE,
                                "Chapter exceeds the $maxCharacters character limit",
                            )
                        }
                        output.append(buffer, 0, count)
                    }
                    output.toString()
                }
            }
            return text to digest.digest().toHex()
        } catch (failure: ChapterContentException) {
            throw failure
        } catch (failure: CharacterCodingException) {
            throw ChapterContentException(
                ChapterContentFailure.INVALID_UTF8,
                "Chapter is not valid UTF-8",
                failure,
            )
        } catch (failure: IOException) {
            throw ChapterContentException(
                ChapterContentFailure.IO_ERROR,
                "Unable to read chapter content",
                failure,
            )
        }
    }

    private fun unsafePath(chapterId: String, cause: Throwable? = null) = ChapterContentException(
        ChapterContentFailure.UNSAFE_PATH,
        "Chapter path leaves its private book directory: $chapterId",
        cause,
    )

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        val SAFE_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        const val READ_BUFFER_CHARACTERS = 8 * 1024
        const val DEFAULT_CAPACITY = 64 * 1024
    }
}
