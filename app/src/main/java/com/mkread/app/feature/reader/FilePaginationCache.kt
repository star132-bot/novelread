package com.mkread.app.feature.reader

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FilePaginationCache(
    cacheDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) : PaginationCache {
    private val root = cacheDir.toPath().toAbsolutePath().normalize().resolve("pagination")
    private val indexFile = root.resolve(INDEX_FILE_NAME)
    private val lock = Mutex()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = true
    }

    init {
        require(maxCacheBytes >= 0) { "Pagination cache limit must not be negative" }
    }

    override suspend fun load(key: PaginationKey, text: String): List<PageRange>? =
        withContext(ioDispatcher) { lock.withLock { loadLocked(key, text) } }

    override suspend fun store(
        key: PaginationKey,
        text: String,
        ranges: List<PageRange>,
    ): Boolean = withContext(ioDispatcher) {
        lock.withLock { storeLocked(key, text, ranges) }
    }

    override suspend fun invalidateChapter(chapterId: String) = withContext(ioDispatcher) {
        lock.withLock {
            val index = loadIndexLocked()
            val retained = index.entries.filter { entry ->
                if (entry.chapterId != chapterId) return@filter true
                deleteBody(entry.keyHash)
                false
            }
            if (retained.size != index.entries.size) writeIndexLocked(CacheIndex(entries = retained))
        }
    }

    private fun loadLocked(key: PaginationKey, text: String): List<PageRange>? {
        val file = bodyFile(key.cacheId())
        if (!Files.isRegularFile(file)) return null
        return try {
            val body = json.decodeFromString<CacheBody>(file.toFile().readText(Charsets.UTF_8))
            if (
                body.schemaVersion != CACHE_SCHEMA_VERSION ||
                body.keyHash != key.cacheId() ||
                body.chapterId != key.chapterId ||
                body.textLength != text.length ||
                !validPageRanges(text, body.ranges)
            ) {
                throw InvalidCacheException()
            }
            touch(file)
            body.ranges
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            deleteBody(key.cacheId())
            null
        }
    }

    private fun storeLocked(
        key: PaginationKey,
        text: String,
        ranges: List<PageRange>,
    ): Boolean {
        if (!validPageRanges(text, ranges)) return false
        val hash = key.cacheId()
        val target = bodyFile(hash)
        val partial = target.resolveSibling("${target.fileName}.partial")
        return try {
            Files.createDirectories(target.parent)
            val body = CacheBody(
                schemaVersion = CACHE_SCHEMA_VERSION,
                keyHash = hash,
                chapterId = key.chapterId,
                textLength = text.length,
                ranges = ranges,
            )
            FileOutputStream(partial.toFile()).use { output ->
                OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
                    writer.write(json.encodeToString(body))
                    writer.flush()
                    output.fd.sync()
                }
            }
            atomicReplace(partial, target)
            touch(target)

            val index = loadIndexLocked()
            val entries = index.entries
                .filterNot { it.keyHash == hash }
                .plus(CacheIndexEntry(hash, key.chapterId))
            writeIndexLocked(CacheIndex(entries = entries))
            evictLocked()
            true
        } catch (failure: CancellationException) {
            partial.toFile().delete()
            throw failure
        } catch (_: Exception) {
            partial.toFile().delete()
            false
        }
    }

    private fun loadIndexLocked(): CacheIndex {
        if (!Files.isRegularFile(indexFile)) return rebuildIndexLocked()
        return try {
            val index = json.decodeFromString<CacheIndex>(indexFile.toFile().readText(Charsets.UTF_8))
            if (index.schemaVersion != CACHE_SCHEMA_VERSION || index.entries.any { !isHash(it.keyHash) }) {
                throw InvalidCacheException()
            }
            index
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            rebuildIndexLocked()
        }
    }

    private fun rebuildIndexLocked(): CacheIndex {
        val entries = mutableListOf<CacheIndexEntry>()
        if (Files.isDirectory(root)) {
            Files.list(root).use { prefixes ->
                prefixes.filter { Files.isDirectory(it) }.forEach { prefix ->
                    Files.list(prefix).use { files ->
                        files.filter { it.fileName.toString().endsWith(".json") }.forEach { file ->
                            val hash = file.fileName.toString().removeSuffix(".json")
                            if (!isHash(hash)) return@forEach
                            try {
                                val body = json.decodeFromString<CacheBody>(file.toFile().readText(Charsets.UTF_8))
                                if (
                                    body.schemaVersion == CACHE_SCHEMA_VERSION &&
                                    body.keyHash == hash &&
                                    body.chapterId.isNotBlank()
                                ) {
                                    entries += CacheIndexEntry(hash, body.chapterId)
                                }
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (_: Exception) {
                                file.toFile().delete()
                            }
                        }
                    }
                }
            }
        }
        return CacheIndex(entries = entries).also(::writeIndexLocked)
    }

    private fun evictLocked() {
        val bodies = bodyFiles()
        var total = bodies.sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }
        if (total <= maxCacheBytes) return

        val removed = mutableSetOf<String>()
        bodies.sortedBy { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(Long.MIN_VALUE) }
            .forEach { file ->
                if (total <= maxCacheBytes) return@forEach
                val size = runCatching { Files.size(file) }.getOrDefault(0L)
                if (file.toFile().delete()) {
                    total -= size
                    removed += file.fileName.toString().removeSuffix(".json")
                }
            }
        if (removed.isNotEmpty()) {
            val index = loadIndexLocked()
            writeIndexLocked(CacheIndex(entries = index.entries.filterNot { it.keyHash in removed }))
        }
    }

    private fun bodyFiles(): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        val result = mutableListOf<Path>()
        Files.list(root).use { prefixes ->
            prefixes.filter { Files.isDirectory(it) }.forEach { prefix ->
                Files.list(prefix).use { files ->
                    files.filter {
                        it.fileName.toString().endsWith(".json") &&
                            isHash(it.fileName.toString().removeSuffix(".json"))
                    }.forEach(result::add)
                }
            }
        }
        return result
    }

    private fun bodyFile(hash: String): Path {
        require(isHash(hash)) { "Invalid pagination cache hash" }
        return root.resolve(hash.take(2)).resolve("$hash.json")
    }

    private fun deleteBody(hash: String) {
        if (isHash(hash)) bodyFile(hash).toFile().delete()
    }

    private fun touch(file: Path) {
        runCatching { Files.setLastModifiedTime(file, FileTime.fromMillis(clock())) }
    }

    private fun writeIndexLocked(index: CacheIndex) {
        Files.createDirectories(root)
        val partial = indexFile.resolveSibling("$INDEX_FILE_NAME.partial")
        FileOutputStream(partial.toFile()).use { output ->
            OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
                writer.write(json.encodeToString(index))
                writer.flush()
                output.fd.sync()
            }
        }
        atomicReplace(partial, indexFile)
    }

    private fun atomicReplace(source: Path, target: Path) {
        Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun isHash(value: String): Boolean = HASH.matches(value)

    @Serializable
    private data class CacheBody(
        val schemaVersion: Int,
        val keyHash: String,
        val chapterId: String,
        val textLength: Int,
        val ranges: List<PageRange>,
    )

    @Serializable
    private data class CacheIndex(
        val schemaVersion: Int = CACHE_SCHEMA_VERSION,
        val entries: List<CacheIndexEntry> = emptyList(),
    )

    @Serializable
    private data class CacheIndexEntry(
        val keyHash: String,
        val chapterId: String,
    )

    private class InvalidCacheException : Exception()

    private companion object {
        const val CACHE_SCHEMA_VERSION = 1
        const val INDEX_FILE_NAME = "index.json"
        const val DEFAULT_MAX_CACHE_BYTES = 100L * 1024L * 1024L
        val HASH = Regex("[0-9a-f]{64}")
    }
}
