package com.mkread.app.speech

import android.content.Context
import android.content.res.AssetManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

fun interface SpeechAssetSource {
    fun open(relativePath: String): InputStream
}

data class SpeechAssetInstallResult(
    val replacedPaths: List<String>,
)

class SpeechAssetInstaller(
    private val filesDir: File,
    private val source: SpeechAssetSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val stagingName: () -> String = { UUID.randomUUID().toString() },
) {
    constructor(context: Context) : this(
        filesDir = context.filesDir,
        source = SpeechAssetSource { relativePath ->
            context.assets.open(relativePath, AssetManager.ACCESS_STREAMING)
        },
    )

    /**
     * Installs the bundled speech files whose paths start with one of [prefixes] (all files
     * when null). Files recorded as installed with a matching size are trusted without
     * re-hashing, so repeated calls cost a few stat() calls instead of hashing hundreds of MB.
     *
     * With [pruneOthers], model packs outside [prefixes] are deleted to reclaim storage.
     */
    suspend fun install(
        prefixes: Collection<String>? = null,
        pruneOthers: Boolean = false,
    ): SpeechAssetInstallResult = withContext(ioDispatcher) {
        val manifestBytes = source.open(MANIFEST_ASSET_PATH).use { input ->
            input.readBounded(MAX_MANIFEST_BYTES)
        }
        val manifest = SpeechAssetManifest.parse(manifestBytes.toString(Charsets.UTF_8))
        val wanted = manifest.files.filter { record ->
            prefixes == null || prefixes.any { record.relativePath.startsWith(it) }
        }
        require(wanted.isNotEmpty()) { "No bundled speech assets match the requested voice" }
        val installed = completionFile().readManifestOrNull()?.files.orEmpty()
            .associateBy(SpeechAssetFile::relativePath)
            .toMutableMap()
        if (pruneOthers && prefixes != null) {
            pruneModelPacks(keep = prefixes, installed = installed)
        }
        val filesToReplace = wanted.filterNot { record ->
            val file = installedFile(record)
            if (installed[record.relativePath] == record) {
                file.isFile && file.length() == record.byteSize
            } else {
                file.matches(record)
            }
        }

        if (filesToReplace.isEmpty()) {
            val recorded = wanted.all { installed[it.relativePath] == it }
            if (!recorded) writeCompletion(installed.apply { wanted.forEach { put(it.relativePath, it) } })
            return@withContext SpeechAssetInstallResult(emptyList())
        }

        val stagingRoot = File(filesDir, STAGING_DIRECTORY)
        val stagingDirectory = File(stagingRoot, stagingName())
        check(!stagingDirectory.exists()) { "Speech staging directory already exists" }
        check(stagingDirectory.mkdirs()) { "Unable to create speech staging directory" }

        try {
            for (record in filesToReplace) {
                currentCoroutineContext().ensureActive()
                val stagedFile = File(stagingDirectory, record.relativePath)
                requireDirectory(stagedFile.parentFile, "speech asset staging")
                source.open(record.relativePath).use { input ->
                    FileOutputStream(stagedFile).use { output ->
                        val digest = MessageDigest.getInstance(SHA_256)
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var copiedBytes = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            copiedBytes += count
                            require(copiedBytes <= record.byteSize) {
                                "Speech asset exceeds declared size: ${record.relativePath}"
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        require(copiedBytes == record.byteSize) {
                            "Speech asset size mismatch: ${record.relativePath}"
                        }
                        require(digest.toHex() == record.sha256) {
                            "Speech asset hash mismatch: ${record.relativePath}"
                        }
                    }
                }
            }

            currentCoroutineContext().ensureActive()
            filesToReplace.forEach { record ->
                val destination = installedFile(record)
                requireDirectory(destination.parentFile, "installed speech asset")
                atomicReplace(File(stagingDirectory, record.relativePath), destination)
            }
            writeCompletion(installed.apply { wanted.forEach { put(it.relativePath, it) } })

            SpeechAssetInstallResult(filesToReplace.map { it.relativePath })
        } finally {
            stagingDirectory.deleteRecursively()
            if (stagingRoot.listFiles().isNullOrEmpty()) {
                stagingRoot.delete()
            }
        }
    }

    private fun installedFile(record: SpeechAssetFile) = File(filesDir, record.relativePath)

    private fun writeCompletion(records: Map<String, SpeechAssetFile>) {
        val completion = completionFile()
        if (records.isEmpty()) {
            completion.delete()
            return
        }
        val json = buildString {
            append("{\"schemaVersion\":").append(SpeechAssetManifest.SUPPORTED_SCHEMA_VERSION).append(",\"files\":[")
            records.values.sortedBy(SpeechAssetFile::relativePath).forEachIndexed { index, record ->
                if (index > 0) append(',')
                append("{\"path\":").append(JSONObject.quote(record.relativePath))
                append(",\"size\":").append(record.byteSize)
                append(",\"sha256\":\"").append(record.sha256).append("\"}")
            }
            append("]}")
        }
        val partial = File(filesDir, "$COMPLETION_FILE.partial")
        partial.writeText(json, Charsets.UTF_8)
        atomicReplace(partial, completion)
    }

    /** Deletes installed model packs (models/<pack>/) that are not covered by [keep]. */
    private fun pruneModelPacks(keep: Collection<String>, installed: MutableMap<String, SpeechAssetFile>) {
        val modelsRoot = File(filesDir, MODELS_DIRECTORY)
        val removed = modelsRoot.listFiles().orEmpty()
            .filter { pack -> pack.isDirectory && keep.none { it.startsWith("$MODELS_DIRECTORY/${pack.name}/") } }
        if (removed.isEmpty()) return
        removed.forEach { pack ->
            installed.keys.removeAll { it.startsWith("$MODELS_DIRECTORY/${pack.name}/") }
        }
        writeCompletion(installed)
        removed.forEach(File::deleteRecursively)
    }

    private fun completionFile() = File(filesDir, COMPLETION_FILE)

    private fun File.matches(record: SpeechAssetFile): Boolean {
        if (!isFile || length() != record.byteSize) return false
        return inputStream().use { input ->
            val digest = MessageDigest.getInstance(SHA_256)
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
            digest.toHex() == record.sha256
        }
    }

    private fun File.readManifestOrNull(): SpeechAssetManifest? {
        if (!isFile || length() > MAX_MANIFEST_BYTES) return null
        return runCatching {
            SpeechAssetManifest.parse(readText(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= maxBytes) { "Speech asset manifest is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun MessageDigest.toHex(): String = digest().joinToString("") { byte ->
        "%02x".format(byte)
    }

    private fun atomicReplace(source: File, destination: File) {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun requireDirectory(directory: File?, description: String) {
        check(directory != null && (directory.isDirectory || directory.mkdirs())) {
            "Unable to create $description directory"
        }
    }

    private companion object {
        const val MANIFEST_ASSET_PATH = "speech-assets.json"
        const val COMPLETION_FILE = "speech-assets.complete.json"
        const val MODELS_DIRECTORY = "models"
        const val STAGING_DIRECTORY = "speech-staging"
        const val SHA_256 = "SHA-256"
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
    }
}
