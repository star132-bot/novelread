package com.mkread.app.core.files

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.LinkedHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

enum class SafeZipFailure {
    PATH_TRAVERSAL,
    ABSOLUTE_PATH,
    DUPLICATE_PATH,
    ENTRY_LIMIT,
    EXPANDED_SIZE_LIMIT,
    ENCRYPTED_OR_UNREADABLE,
    ENTRY_NOT_FOUND,
}

class SafeZipException(
    val failure: SafeZipFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class SafeZipReader(
    private val entryLimit: Int = ImportLimits.ZIP_ENTRIES,
    private val expandedByteLimit: Long = ImportLimits.EXPANDED_EPUB_BYTES,
) {
    fun open(file: File): SafeZipArchive {
        if (!file.isFile) {
            throw SafeZipException(
                SafeZipFailure.ENCRYPTED_OR_UNREADABLE,
                "ZIP source is not a readable file",
            )
        }
        val zipFile = try {
            ZipFile(file)
        } catch (failure: IOException) {
            throw unreadable(failure)
        }
        try {
            val entries = indexEntries(zipFile)
            validateExpandedBytes(zipFile, entries.values)
            return SafeZipArchive(zipFile, entries, expandedByteLimit)
        } catch (failure: Exception) {
            zipFile.close()
            when (failure) {
                is SafeZipException -> throw failure
                is IOException -> throw unreadable(failure)
                else -> throw failure
            }
        }
    }

    private fun indexEntries(zipFile: ZipFile): LinkedHashMap<String, ZipEntry> {
        val indexed = LinkedHashMap<String, ZipEntry>()
        val normalizedNames = HashSet<String>()
        val enumeration = zipFile.entries()
        var count = 0
        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            count += 1
            if (count > entryLimit) {
                throw SafeZipException(
                    SafeZipFailure.ENTRY_LIMIT,
                    "ZIP contains more than $entryLimit entries",
                )
            }
            val normalized = SafeZipPath.normalizeEntry(entry.name)
            if (!normalizedNames.add(normalized)) {
                throw SafeZipException(
                    SafeZipFailure.DUPLICATE_PATH,
                    "ZIP contains a duplicate normalized path: $normalized",
                )
            }
            if (!entry.isDirectory) {
                indexed[normalized] = entry
            }
        }
        return indexed
    }

    private fun validateExpandedBytes(
        zipFile: ZipFile,
        entries: Collection<ZipEntry>,
    ) {
        val buffer = ByteArray(ImportLimits.COPY_BUFFER_BYTES)
        var expandedBytes = 0L
        entries.forEach { entry ->
            try {
                zipFile.getInputStream(entry).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        if (expandedBytes > expandedByteLimit - read) {
                            throw SafeZipException(
                                SafeZipFailure.EXPANDED_SIZE_LIMIT,
                                "ZIP expands beyond $expandedByteLimit bytes",
                            )
                        }
                        expandedBytes += read
                    }
                }
            } catch (failure: SafeZipException) {
                throw failure
            } catch (failure: IOException) {
                throw unreadable(failure)
            }
        }
    }

    private fun unreadable(cause: Throwable) = SafeZipException(
        SafeZipFailure.ENCRYPTED_OR_UNREADABLE,
        "ZIP is encrypted, corrupt, or unreadable",
        cause,
    )
}

class SafeZipArchive internal constructor(
    private val zipFile: ZipFile,
    private val indexedEntries: Map<String, ZipEntry>,
    private val defaultReadLimit: Long,
) : Closeable {
    val entries: Set<String>
        get() = indexedEntries.keys.toSet()

    fun contains(path: String): Boolean = indexedEntries.containsKey(
        SafeZipPath.normalizeEntry(path),
    )

    fun read(
        path: String,
        byteLimit: Long = defaultReadLimit,
    ): ByteArray {
        val normalized = SafeZipPath.normalizeEntry(path)
        val entry = indexedEntries[normalized]
            ?: throw SafeZipException(
                SafeZipFailure.ENTRY_NOT_FOUND,
                "ZIP entry does not exist: $normalized",
            )
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(ImportLimits.COPY_BUFFER_BYTES)
        var count = 0L
        try {
            zipFile.getInputStream(entry).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    if (count > byteLimit - read) {
                        throw SafeZipException(
                            SafeZipFailure.EXPANDED_SIZE_LIMIT,
                            "ZIP entry exceeds its $byteLimit byte read limit",
                        )
                    }
                    output.write(buffer, 0, read)
                    count += read
                }
            }
        } catch (failure: SafeZipException) {
            throw failure
        } catch (failure: ZipException) {
            throw SafeZipException(
                SafeZipFailure.ENCRYPTED_OR_UNREADABLE,
                "ZIP entry is encrypted or corrupt: $normalized",
                failure,
            )
        } catch (failure: IOException) {
            throw SafeZipException(
                SafeZipFailure.ENCRYPTED_OR_UNREADABLE,
                "ZIP entry is unreadable: $normalized",
                failure,
            )
        }
        return output.toByteArray()
    }

    override fun close() {
        zipFile.close()
    }
}

object SafeZipPath {
    fun normalizeEntry(rawPath: String): String {
        val replaced = rawPath.replace('\\', '/')
        if (
            replaced.startsWith('/') ||
            replaced.startsWith("//") ||
            WINDOWS_DRIVE.matches(replaced)
        ) {
            throw SafeZipException(
                SafeZipFailure.ABSOLUTE_PATH,
                "Absolute ZIP paths are forbidden: $rawPath",
            )
        }
        val components = replaced.split('/')
        if (components.any { it == ".." }) {
            throw SafeZipException(
                SafeZipFailure.PATH_TRAVERSAL,
                "ZIP path traversal is forbidden: $rawPath",
            )
        }
        val normalized = components
            .filter { it.isNotEmpty() && it != "." }
            .joinToString("/")
        if (normalized.isEmpty()) {
            throw SafeZipException(SafeZipFailure.PATH_TRAVERSAL, "ZIP path is empty")
        }
        return normalized
    }

    fun resolve(baseDirectory: String, href: String): String {
        val relative = href.substringBefore('#').substringBefore('?')
        if (relative.isBlank()) {
            throw SafeZipException(SafeZipFailure.PATH_TRAVERSAL, "EPUB href is empty")
        }
        val normalizedRelative = normalizeEntry(relative)
        val combined = if (baseDirectory.isBlank()) {
            normalizedRelative
        } else {
            "$baseDirectory/$normalizedRelative"
        }
        return normalizeEntry(combined)
    }

    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:.*")
}
