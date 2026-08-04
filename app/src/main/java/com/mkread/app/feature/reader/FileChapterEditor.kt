package com.mkread.app.feature.reader

import androidx.room.withTransaction
import com.mkread.app.core.database.ChapterEntity
import com.mkread.app.core.database.MkreadDatabase
import com.mkread.app.core.files.ImportLimits
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface ChapterEditMetadata {
    suspend fun getChapter(chapterId: String): ChapterEntity?

    suspend fun updateContent(
        chapter: ChapterEntity,
        expectedHash: String,
        newHash: String,
        characterCount: Int,
        modifiedAt: Long,
    )
}

class RoomChapterEditMetadata(
    private val database: MkreadDatabase,
) : ChapterEditMetadata {
    override suspend fun getChapter(chapterId: String): ChapterEntity? =
        database.chapterDao().getById(chapterId)

    override suspend fun updateContent(
        chapter: ChapterEntity,
        expectedHash: String,
        newHash: String,
        characterCount: Int,
        modifiedAt: Long,
    ) {
        database.withTransaction {
            val chapterUpdated = database.chapterDao().updateContent(
                chapterId = chapter.id,
                expectedContentSha256 = expectedHash,
                characterCount = characterCount,
                newContentSha256 = newHash,
            )
            if (chapterUpdated != 1) error("Chapter content changed concurrently")
            if (database.bookDao().updateModifiedAt(chapter.bookId, modifiedAt) != 1) {
                error("Chapter book is missing")
            }
        }
    }
}

interface ChapterFileOperations {
    fun createDirectories(directory: File)

    fun writeUtf8AndSync(target: File, text: String)

    fun move(source: File, target: File)

    fun delete(target: File): Boolean
}

object DefaultChapterFileOperations : ChapterFileOperations {
    override fun createDirectories(directory: File) {
        Files.createDirectories(directory.toPath())
    }

    override fun writeUtf8AndSync(target: File, text: String) {
        FileOutputStream(target).use { output ->
            val writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
            writer.write(text)
            writer.flush()
            output.fd.sync()
        }
    }

    override fun move(source: File, target: File) {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    override fun delete(target: File): Boolean = !target.exists() || target.delete()
}

class FileChapterEditor(
    filesDir: File,
    cacheDir: File,
    private val metadata: ChapterEditMetadata,
    private val invalidator: DerivedDataInvalidator,
    private val fileOperations: ChapterFileOperations = DefaultChapterFileOperations,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxCharacters: Int = ImportLimits.CHAPTER_CHARACTERS,
) : ChapterEditor {
    private val booksRoot = filesDir.toPath().toAbsolutePath().normalize().resolve("books")
    private val cleanupRoot = cacheDir.toPath().toAbsolutePath().normalize().resolve("reader-cleanup")
    private val transactionMutex = Mutex()

    init {
        require(maxCharacters > 0) { "Chapter character limit must be positive" }
    }

    override suspend fun save(
        chapterId: String,
        newText: String,
        currentOffset: Int,
    ): ChapterEditResult = withContext(ioDispatcher) {
        transactionMutex.withLock {
            val normalized = normalizeAndValidate(newText)
            val chapter = requireChapter(chapterId)
            val paths = resolvePaths(chapter)
            val oldText = readAndVerify(paths.current, chapter.contentSha256)
            val newHash = normalized.sha256()
            if (oldText == normalized) {
                return@withLock ChapterEditResult(
                    chapterId = chapter.id,
                    oldContentSha256 = chapter.contentSha256,
                    newContentSha256 = chapter.contentSha256,
                    characterCount = oldText.length,
                    mappedOffset = currentOffset.coerceIn(0, oldText.length),
                    undoAvailable = paths.undo.isFile,
                    cleanupRecorded = false,
                )
            }
            saveTransaction(chapter, paths, oldText, normalized, newHash, currentOffset)
        }
    }

    override suspend fun undo(chapterId: String, currentOffset: Int): ChapterEditResult? =
        withContext(ioDispatcher) {
            transactionMutex.withLock {
                val chapter = requireChapter(chapterId)
                val paths = resolvePaths(chapter)
                if (!paths.undo.isFile) return@withLock null
                val oldText = readAndVerify(paths.current, chapter.contentSha256)
                val restoredText = readUtf8(paths.undo)
                val restoredHash = restoredText.sha256()
                undoTransaction(
                    chapter = chapter,
                    paths = paths,
                    oldText = oldText,
                    restoredText = restoredText,
                    restoredHash = restoredHash,
                    currentOffset = currentOffset,
                )
            }
        }

    override suspend fun hasUndo(chapterId: String): Boolean = withContext(ioDispatcher) {
        transactionMutex.withLock {
            val chapter = requireChapter(chapterId)
            resolvePaths(chapter).undo.isFile
        }
    }

    private suspend fun saveTransaction(
        chapter: ChapterEntity,
        paths: EditPaths,
        oldText: String,
        newText: String,
        newHash: String,
        currentOffset: Int,
    ): ChapterEditResult {
        var previousUndoMoved = false
        var currentMoved = false
        var newPromoted = false
        try {
            fileOperations.createDirectories(requireNotNull(paths.undo.parentFile))
            cleanTemporary(paths.newFile, paths.previousUndo)
            fileOperations.writeUtf8AndSync(paths.newFile, newText)
            if (paths.undo.exists()) {
                fileOperations.move(paths.undo, paths.previousUndo)
                previousUndoMoved = true
            }
            fileOperations.move(paths.current, paths.undo)
            currentMoved = true
            fileOperations.move(paths.newFile, paths.current)
            newPromoted = true
            updateMetadata(chapter, chapter.contentSha256, newHash, newText.length)
            fileOperations.delete(paths.previousUndo)
        } catch (failure: CancellationException) {
            rollbackSave(paths, previousUndoMoved, currentMoved, newPromoted)
            throw failure
        } catch (failure: Exception) {
            rollbackSave(paths, previousUndoMoved, currentMoved, newPromoted)
            throw editFailure(failure)
        }

        val cleanupRecorded = invalidateOrRecord(chapter.id, chapter.contentSha256, newHash)
        return ChapterEditResult(
            chapterId = chapter.id,
            oldContentSha256 = chapter.contentSha256,
            newContentSha256 = newHash,
            characterCount = newText.length,
            mappedOffset = PositionRemapper.remap(oldText, newText, currentOffset),
            undoAvailable = true,
            cleanupRecorded = cleanupRecorded,
        )
    }

    private suspend fun undoTransaction(
        chapter: ChapterEntity,
        paths: EditPaths,
        oldText: String,
        restoredText: String,
        restoredHash: String,
        currentOffset: Int,
    ): ChapterEditResult {
        var currentMoved = false
        var newPromoted = false
        try {
            cleanTemporary(paths.newFile, paths.rollbackCurrent)
            fileOperations.writeUtf8AndSync(paths.newFile, restoredText)
            fileOperations.move(paths.current, paths.rollbackCurrent)
            currentMoved = true
            fileOperations.move(paths.newFile, paths.current)
            newPromoted = true
            updateMetadata(chapter, chapter.contentSha256, restoredHash, restoredText.length)
            fileOperations.delete(paths.rollbackCurrent)
            fileOperations.delete(paths.undo)
        } catch (failure: CancellationException) {
            rollbackUndo(paths, currentMoved, newPromoted)
            throw failure
        } catch (failure: Exception) {
            rollbackUndo(paths, currentMoved, newPromoted)
            throw editFailure(failure)
        }

        val cleanupRecorded = invalidateOrRecord(chapter.id, chapter.contentSha256, restoredHash)
        return ChapterEditResult(
            chapterId = chapter.id,
            oldContentSha256 = chapter.contentSha256,
            newContentSha256 = restoredHash,
            characterCount = restoredText.length,
            mappedOffset = PositionRemapper.remap(oldText, restoredText, currentOffset),
            undoAvailable = false,
            cleanupRecorded = cleanupRecorded,
        )
    }

    private suspend fun updateMetadata(
        chapter: ChapterEntity,
        expectedHash: String,
        newHash: String,
        characterCount: Int,
    ) {
        try {
            metadata.updateContent(chapter, expectedHash, newHash, characterCount, clock())
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            throw ChapterEditException(
                ChapterEditFailure.DATABASE,
                "Unable to update chapter metadata",
                failure,
            )
        }
    }

    private fun rollbackSave(
        paths: EditPaths,
        previousUndoMoved: Boolean,
        currentMoved: Boolean,
        newPromoted: Boolean,
    ) {
        runCatching {
            if (newPromoted) fileOperations.delete(paths.current)
            if (currentMoved && paths.undo.exists()) fileOperations.move(paths.undo, paths.current)
            if (previousUndoMoved && paths.previousUndo.exists()) {
                fileOperations.move(paths.previousUndo, paths.undo)
            }
            fileOperations.delete(paths.newFile)
        }
    }

    private fun rollbackUndo(paths: EditPaths, currentMoved: Boolean, newPromoted: Boolean) {
        runCatching {
            if (newPromoted) fileOperations.delete(paths.current)
            if (currentMoved && paths.rollbackCurrent.exists()) {
                fileOperations.move(paths.rollbackCurrent, paths.current)
            }
            fileOperations.delete(paths.newFile)
        }
    }

    private suspend fun invalidateOrRecord(chapterId: String, oldHash: String, newHash: String): Boolean {
        try {
            invalidator.invalidateChapter(chapterId, oldHash, newHash)
            return false
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            val marker = cleanupRoot.resolve("${chapterId.sha256()}-$newHash.marker").toFile()
            try {
                fileOperations.createDirectories(requireNotNull(marker.parentFile))
                fileOperations.writeUtf8AndSync(marker, "$chapterId\n$oldHash\n$newHash\n")
                return true
            } catch (failure: Exception) {
                throw ChapterEditException(
                    ChapterEditFailure.CLEANUP_MARKER,
                    "Chapter was saved but its cleanup marker could not be recorded",
                    failure,
                )
            }
        }
    }

    private suspend fun requireChapter(chapterId: String): ChapterEntity =
        metadata.getChapter(chapterId)
            ?: throw ChapterEditException(
                ChapterEditFailure.NOT_FOUND,
                "Chapter was not found: $chapterId",
            )

    private fun resolvePaths(chapter: ChapterEntity): EditPaths {
        if (!SAFE_SEGMENT.matches(chapter.bookId)) throw unsafePath(chapter.id)
        val relative = try {
            Paths.get(chapter.relativePath)
        } catch (failure: Exception) {
            throw unsafePath(chapter.id, failure)
        }
        if (
            relative.isAbsolute ||
            relative.nameCount == 0 ||
            relative.any { it.toString() == "." || it.toString() == ".." }
        ) {
            throw unsafePath(chapter.id)
        }
        val bookRoot = booksRoot.resolve(chapter.bookId).normalize()
        val current = bookRoot.resolve(relative).normalize()
        if (bookRoot.parent != booksRoot || !current.startsWith(bookRoot)) throw unsafePath(chapter.id)
        if (!Files.isDirectory(bookRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(bookRoot)) {
            throw ChapterEditException(ChapterEditFailure.NOT_FOUND, "Book directory was not found")
        }
        var cursor = bookRoot
        relative.forEach { part ->
            cursor = cursor.resolve(part)
            if (Files.isSymbolicLink(cursor)) throw unsafePath(chapter.id)
        }
        if (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
            throw ChapterEditException(ChapterEditFailure.NOT_FOUND, "Chapter file was not found")
        }

        val undoRoot = bookRoot.resolve(UNDO_DIRECTORY).normalize()
        val snapshotId = chapter.id.sha256()
        return EditPaths(
            current = current.toFile(),
            newFile = current.resolveSibling("${current.fileName}.new").toFile(),
            undo = undoRoot.resolve("$snapshotId.txt").toFile(),
            previousUndo = undoRoot.resolve("$snapshotId.previous").toFile(),
            rollbackCurrent = undoRoot.resolve("$snapshotId.current").toFile(),
        )
    }

    private fun normalizeAndValidate(text: String): String {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.isBlank()) {
            throw ChapterEditException(ChapterEditFailure.BLANK_CHAPTER, "Chapter cannot be blank")
        }
        if (normalized.length > maxCharacters) {
            throw ChapterEditException(
                ChapterEditFailure.TOO_LARGE,
                "Chapter exceeds the $maxCharacters character limit",
            )
        }
        return normalized
    }

    private fun readAndVerify(file: File, expectedHash: String): String {
        val text = readUtf8(file)
        if (!text.sha256().equals(expectedHash, ignoreCase = true)) {
            throw ChapterEditException(
                ChapterEditFailure.CORRUPT_CURRENT,
                "Current chapter hash does not match Room metadata",
            )
        }
        return text
    }

    private fun readUtf8(file: File): String = try {
        file.readText(Charsets.UTF_8)
    } catch (failure: IOException) {
        throw ChapterEditException(ChapterEditFailure.FILE_IO, "Unable to read chapter", failure)
    }

    private fun cleanTemporary(vararg files: File) {
        files.forEach { file ->
            if (!fileOperations.delete(file)) {
                throw ChapterEditException(
                    ChapterEditFailure.FILE_IO,
                    "Unable to clean temporary chapter file",
                )
            }
        }
    }

    private fun editFailure(failure: Exception): ChapterEditException =
        failure as? ChapterEditException
            ?: ChapterEditException(ChapterEditFailure.FILE_IO, "Unable to edit chapter", failure)

    private fun unsafePath(chapterId: String, cause: Throwable? = null) = ChapterEditException(
        ChapterEditFailure.UNSAFE_PATH,
        "Chapter path leaves its private book directory: $chapterId",
        cause,
    )

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class EditPaths(
        val current: File,
        val newFile: File,
        val undo: File,
        val previousUndo: File,
        val rollbackCurrent: File,
    )

    private companion object {
        const val UNDO_DIRECTORY = ".undo"
        val SAFE_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
