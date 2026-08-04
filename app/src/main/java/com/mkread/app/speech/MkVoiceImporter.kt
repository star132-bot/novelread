package com.mkread.app.speech

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.concurrent.withLock

class MkVoiceImportException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

data class ImportedVoice(
    val id: String,
    val displayName: String,
    val languages: List<String>,
    val reference: VoiceReference,
    val manifestFile: File,
    val styles: List<ImportedVoiceStyle>,
)

data class ImportedVoiceStyle(
    val emotion: String,
    val audioFile: File,
    val transcript: String,
)

class MkVoiceImporter(
    private val voicesRoot: File,
) {
    init {
        recoverInterruptedInstalls()
    }

    fun importPackage(source: File, replace: Boolean = false): ImportedVoice {
        if (!source.isFile || source.length() !in 1..MAX_PACKAGE_BYTES) {
            fail("invalid_package", "MKvoice package is missing or exceeds 250 MiB")
        }

        requireDirectory(voicesRoot)
        val packageData = readPackage(source)
        val id = packageData.manifest.id
        return processLock(id).withLock {
            withFileLock(id) {
                recoverInterruptedInstall(id)
                installLocked(packageData, replace)
            }
        }
    }

    private fun recoverInterruptedInstalls() {
        if (!voicesRoot.isDirectory) return
        voicesRoot.listFiles().orEmpty()
            .mapNotNull { file ->
                file.name.takeIf { file.isDirectory && it.startsWith('.') && it.endsWith(BACKUP_SUFFIX) }
                    ?.removePrefix(".")
                    ?.removeSuffix(BACKUP_SUFFIX)
                    ?.takeIf(VOICE_ID::matches)
            }
            .forEach { id ->
                processLock(id).withLock { withFileLock(id) { recoverInterruptedInstall(id) } }
            }
    }

    private fun <T> withFileLock(id: String, action: () -> T): T {
        val lockFile = File(voicesRoot, ".$id.lock")
        return FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { action() }
        }
    }

    private fun installLocked(packageData: PackageData, replace: Boolean): ImportedVoice {
        val destination = File(voicesRoot, packageData.manifest.id)
        if (destination.exists() && !replace) {
            fail("destination_exists", "Voice is already installed: ${packageData.manifest.id}")
        }
        val staging = File(voicesRoot, ".${packageData.manifest.id}.staging")
        val backup = File(voicesRoot, ".${packageData.manifest.id}$BACKUP_SUFFIX")
        staging.deleteRecursively()
        if (backup.exists()) fail("install_failed", "Interrupted voice replacement could not be recovered")

        try {
            requireDirectory(File(staging, "prompts"))
            writeAndSync(File(staging, MANIFEST_PATH), packageData.manifestBytes)
            writeAndSync(File(staging, CHECKSUMS_PATH), packageData.checksumsBytes)
            packageData.payloads.forEach { (path, content) ->
                val output = File(staging, path)
                requireDirectory(output.parentFile)
                writeAndSync(output, content)
            }
            packageData.styles.forEach { style ->
                val sampleCount = WaveValidator.requirePlayable(File(staging, style.audioPath))
                if (sampleCount > MAX_PROMPT_SAMPLES) fail("invalid_prompt_audio", "Voice prompt must not exceed 60 seconds")
            }

            var movedExisting = false
            try {
                if (destination.exists()) {
                    atomicMove(destination, backup)
                    movedExisting = true
                }
                atomicMove(staging, destination)
                if (movedExisting) backup.deleteRecursively()
            } catch (failure: Exception) {
                if (movedExisting && !destination.exists() && backup.exists()) {
                    try {
                        atomicMove(backup, destination)
                    } catch (rollback: Exception) {
                        throw MkVoiceImportException("rollback_failed", "Voice replacement interrupted; backup retained", rollback)
                    }
                }
                throw failure
            }
        } catch (failure: MkVoiceImportException) {
            throw failure
        } catch (failure: Exception) {
            throw MkVoiceImportException("install_failed", "Unable to install MKvoice package", failure)
        } finally {
            staging.deleteRecursively()
            if (destination.exists()) backup.deleteRecursively()
        }

        val neutral = packageData.styles.first { it.emotion == "neutral" }
        return ImportedVoice(
            id = packageData.manifest.id,
            displayName = packageData.manifest.displayName,
            languages = packageData.manifest.languages,
            reference = VoiceReference(File(destination, neutral.audioPath), packageData.transcripts.getValue(neutral.transcriptPath), SAMPLE_RATE),
            manifestFile = File(destination, MANIFEST_PATH),
            styles = packageData.styles.map { style ->
                ImportedVoiceStyle(style.emotion, File(destination, style.audioPath), packageData.transcripts.getValue(style.transcriptPath))
            },
        )
    }

    private fun recoverInterruptedInstall(id: String) {
        val destination = File(voicesRoot, id)
        val backup = File(voicesRoot, ".$id$BACKUP_SUFFIX")
        val staging = File(voicesRoot, ".${id}.staging")
        if (!destination.exists() && backup.exists()) atomicMove(backup, destination)
        if (destination.exists() && backup.exists()) backup.deleteRecursively()
        if (staging.exists()) staging.deleteRecursively()
    }

    private fun readPackage(source: File): PackageData {
        try {
            ZipFile(source).use { archive ->
                val entries = indexEntries(archive)
                val manifestBytes = archive.readBounded(entries.getValue(MANIFEST_PATH), MAX_MANIFEST_BYTES)
                val manifestContent = manifestBytes.decodeUtf8(MANIFEST_PATH)
                StrictJsonFieldScanner(manifestContent).rejectDuplicates()
                val manifest = parseManifest(manifestContent)
                val payloadPaths = manifest.styles.flatMap { listOf(it.audioPath, it.transcriptPath) }.toSet()
                val expectedEntries = setOf(MANIFEST_PATH, CHECKSUMS_PATH) + payloadPaths
                if (entries.keys != expectedEntries) {
                    fail("unexpected_entries", "MKvoice package entries do not match the declared styles")
                }

                val checksumsBytes = archive.readBounded(entries.getValue(CHECKSUMS_PATH), MAX_CHECKSUMS_BYTES)
                val checksumsContent = checksumsBytes.decodeUtf8(CHECKSUMS_PATH)
                StrictJsonFieldScanner(checksumsContent).rejectDuplicates()
                val checksums = parseChecksums(checksumsContent, payloadPaths)
                val payloads = payloadPaths.associateWith { path ->
                    val limit = if (manifest.styles.any { it.transcriptPath == path }) MAX_TRANSCRIPT_BYTES else MAX_AUDIO_BYTES
                    archive.readBounded(entries.getValue(path), limit)
                }
                checksums.forEach { (path, expected) ->
                    val content = payloads.getValue(path)
                    if (content.sha256() != expected) {
                        fail("checksum_mismatch", "MKvoice payload checksum does not match: $path")
                    }
                }
                val transcripts = manifest.styles.associate { style ->
                    val transcript = payloads.getValue(style.transcriptPath).decodeUtf8(style.transcriptPath).trim()
                    val nonWhitespace = transcript.codePoints().filter { !Character.isWhitespace(it) }.count()
                    if (nonWhitespace !in 1..MAX_TRANSCRIPT_CODE_POINTS) {
                        fail("invalid_transcript", "Voice transcript must contain 1-10000 non-whitespace characters")
                    }
                    style.transcriptPath to transcript
                }
                return PackageData(
                    manifest = manifest,
                    styles = manifest.styles,
                    transcripts = transcripts,
                    manifestBytes = manifestBytes,
                    checksumsBytes = checksumsBytes,
                    payloads = payloads,
                )
            }
        } catch (failure: MkVoiceImportException) {
            throw failure
        } catch (failure: ZipException) {
            throw MkVoiceImportException("invalid_package", "MKvoice package is not a readable ZIP archive", failure)
        } catch (failure: Exception) {
            throw MkVoiceImportException("invalid_package", "Unable to read MKvoice package", failure)
        }
    }

    private fun indexEntries(archive: ZipFile): Map<String, ZipEntry> {
        val entries = linkedMapOf<String, ZipEntry>()
        val foldedNames = hashSetOf<String>()
        val enumeration = archive.entries()
        var expandedBytes = 0L
        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            if (entries.size >= MAX_ENTRIES) fail("entry_limit", "MKvoice package contains too many entries")
            requireSafeEntry(entry)
            val folded = entry.name.lowercase(Locale.ROOT)
            if (!foldedNames.add(folded)) fail("duplicate_entry", "MKvoice package contains colliding entry names")
            if (entry.size < 0L || entry.compressedSize < 0L) fail("invalid_entry", "MKvoice entry size is unavailable")
            if (entry.size > 0L && (entry.compressedSize == 0L || entry.size > entry.compressedSize * MAX_COMPRESSION_RATIO)) {
                fail("compression_ratio", "MKvoice entry compression ratio exceeds 200:1")
            }
            if (expandedBytes > MAX_PACKAGE_BYTES - entry.size) fail("package_too_large", "MKvoice package expands beyond 250 MiB")
            expandedBytes += entry.size
            entries[entry.name] = entry
        }
        return entries
    }

    private fun requireSafeEntry(entry: ZipEntry) {
        val name = entry.name
        val parts = name.split('/')
        val utf8Length = name.toByteArray(Charsets.UTF_8).size
        if (
            entry.isDirectory ||
            name.isEmpty() || utf8Length > MAX_PATH_BYTES ||
            name.startsWith('/') || '\\' in name || ':' in name || '\u0000' in name ||
            parts.any { it.isEmpty() || it == "." || it == ".." } ||
            name.any { it.code < 32 || it.code == 127 }
        ) {
            fail("unsafe_entry", "MKvoice package contains an unsafe archive entry")
        }
    }

    private fun parseManifest(content: String): Manifest {
        val root = Json.parseToJsonElement(content).requireObject("manifest")
        root.requireExactKeys(
            "manifest",
            "schemaVersion", "id", "displayName", "engine", "languages", "creator", "consent", "styles", "checksums",
        )
        if (root.requiredInt("schemaVersion") != SCHEMA_VERSION) fail("unsupported_schema", "Only MKvoice schema version 1 is supported")
        val id = root.requiredString("id")
        if (id.length > 120 || !VOICE_ID.matches(id)) fail("invalid_id", "Voice id must be a lowercase reverse-domain id")
        val displayName = root.requiredString("displayName")
        displayName.requireCodePointLength("displayName", 1, 80)
        if (root.requiredString("engine") != ENGINE_ID) fail("unsupported_engine", "MKvoice package uses an unsupported speech engine")
        val languages = root.requiredArray("languages").map { it.requireString("languages") }
        if (languages.isEmpty() || languages.distinct().size != languages.size || languages.any { it !in SUPPORTED_LANGUAGES }) {
            fail("invalid_languages", "MKvoice package contains unsupported or duplicate languages")
        }
        root.requiredString("creator").requireCodePointLength("creator", 1, 200)
        val consent = root.requiredObject("consent")
        consent.requireExactKeys("consent", "declared", "statement")
        if (consent.requiredBoolean("declared") != true) fail("consent_required", "Voice authorization must be declared")
        consent.requiredString("statement").requireCodePointLength("statement", 10, 500)
        if (root.requiredString("checksums") != CHECKSUMS_PATH) fail("invalid_manifest", "Manifest must declare checksums.json")

        val styles = root.requiredArray("styles").mapIndexed { index, element ->
            val path = "styles[$index]"
            val style = element.requireObject(path)
            style.requireExactKeys(path, "emotion", "audio", "transcript", "sampleRate")
            val emotion = style.requiredString("emotion")
            if (emotion !in SUPPORTED_EMOTIONS) fail("invalid_styles", "Unsupported style emotion: $emotion")
            val audioPath = style.requiredString("audio").also { requirePayloadPath(it, "$path.audio") }
            val transcriptPath = style.requiredString("transcript").also { requirePayloadPath(it, "$path.transcript") }
            if (audioPath == transcriptPath || style.requiredInt("sampleRate") != SAMPLE_RATE) {
                fail("invalid_styles", "Style paths and sample rate do not match the Android contract")
            }
            Style(emotion, audioPath, transcriptPath)
        }
        if (styles.isEmpty() || styles.count { it.emotion == "neutral" } != 1 || styles.map { it.emotion }.distinct().size != styles.size) {
            fail("invalid_styles", "Styles must include neutral exactly once and have unique emotions")
        }
        val paths = styles.flatMap { listOf(it.audioPath, it.transcriptPath) }
        if (paths.map { it.lowercase(Locale.ROOT) }.distinct().size != paths.size) fail("invalid_styles", "Style payload paths collide")
        return Manifest(id, displayName, languages, styles)
    }

    private fun parseChecksums(content: String, expectedPaths: Set<String>): Map<String, String> {
        val root = Json.parseToJsonElement(content).requireObject("checksums")
        if (root.keys != expectedPaths) {
            fail("invalid_checksums", "Checksums must exactly cover the neutral prompt files")
        }
        return root.mapValues { (path, value) ->
            val digest = value.requireString(path)
            if (!SHA_256.matches(digest)) fail("invalid_checksums", "Checksum is not lowercase SHA-256: $path")
            digest
        }
    }

    private fun requirePayloadPath(path: String, field: String) {
        val parts = path.split('/')
        if (
            path.isEmpty() || path.toByteArray(Charsets.UTF_8).size > MAX_PATH_BYTES ||
            path.startsWith('/') || '\\' in path || ':' in path ||
            parts.any { it.isEmpty() || it == "." || it == ".." } ||
            path.any { it.code < 32 || it.code == 127 } ||
            path == MANIFEST_PATH || path == CHECKSUMS_PATH
        ) {
            fail("unsafe_path", "$field is not a safe relative payload path")
        }
    }

    private fun ZipFile.readBounded(entry: ZipEntry, maximumBytes: Int): ByteArray {
        if (entry.size !in 0..maximumBytes.toLong()) fail("entry_too_large", "MKvoice entry exceeds its size limit: ${entry.name}")
        val output = ByteArrayOutputStream()
        getInputStream(entry).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > maximumBytes) fail("entry_too_large", "MKvoice entry exceeds its size limit: ${entry.name}")
                output.write(buffer, 0, count)
            }
            if (total.toLong() != entry.size) fail("size_mismatch", "MKvoice entry size changed while reading: ${entry.name}")
        }
        return output.toByteArray()
    }

    private fun ByteArray.decodeUtf8(path: String): String {
        if (size >= 3 && this[0] == 0xef.toByte() && this[1] == 0xbb.toByte() && this[2] == 0xbf.toByte()) {
            fail("invalid_encoding", "$path must be UTF-8 without BOM")
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(this))
                .toString()
        } catch (failure: Exception) {
            throw MkVoiceImportException("invalid_encoding", "$path is not strict UTF-8", failure)
        }
    }

    private fun JsonElement.requireObject(path: String): JsonObject = this as? JsonObject
        ?: fail("invalid_manifest", "$path must be a JSON object")

    private fun JsonElement.requireString(path: String): String {
        val primitive = this as? JsonPrimitive
        if (primitive == null || !primitive.isString) fail("invalid_manifest", "$path must be a string")
        return primitive.content
    }

    private fun JsonObject.requiredString(name: String): String = get(name)?.requireString(name)
        ?: fail("invalid_manifest", "Manifest field is missing: $name")

    private fun JsonObject.requiredInt(name: String): Int {
        val primitive = get(name) as? JsonPrimitive
        if (primitive == null || primitive.isString || primitive.intOrNull == null) fail("invalid_manifest", "$name must be an integer")
        return primitive.intOrNull!!
    }

    private fun JsonObject.requiredBoolean(name: String): Boolean {
        val primitive = get(name) as? JsonPrimitive
        if (primitive == null || primitive.isString || primitive.booleanOrNull == null) fail("invalid_manifest", "$name must be a boolean")
        return primitive.booleanOrNull!!
    }

    private fun JsonObject.requiredArray(name: String): JsonArray = get(name) as? JsonArray
        ?: fail("invalid_manifest", "$name must be an array")

    private fun JsonObject.requiredObject(name: String): JsonObject = get(name) as? JsonObject
        ?: fail("invalid_manifest", "$name must be an object")

    private fun JsonObject.requireExactKeys(path: String, vararg expected: String) {
        if (keys != expected.toSet()) fail("invalid_manifest", "$path fields do not match the MKvoice contract")
    }

    private fun String.requireCodePointLength(field: String, minimum: Int, maximum: Int) {
        val count = codePointCount(0, length)
        if (isBlank() || count !in minimum..maximum) fail("invalid_manifest", "$field length is outside the MKvoice contract")
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance(SHA_256_ALGORITHM)
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun writeAndSync(destination: File, content: ByteArray) {
        requireDirectory(destination.parentFile)
        FileOutputStream(destination).use { output ->
            output.write(content)
            output.fd.sync()
        }
    }

    private fun atomicMove(source: File, destination: File) {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun requireDirectory(directory: File?) {
        if (directory == null || (!directory.isDirectory && !directory.mkdirs())) {
            fail("install_failed", "Unable to create voice installation directory")
        }
    }

    private data class Manifest(
        val id: String,
        val displayName: String,
        val languages: List<String>,
        val styles: List<Style>,
    )

    private data class Style(
        val emotion: String,
        val audioPath: String,
        val transcriptPath: String,
    )

    private data class PackageData(
        val manifest: Manifest,
        val styles: List<Style>,
        val transcripts: Map<String, String>,
        val manifestBytes: ByteArray,
        val checksumsBytes: ByteArray,
        val payloads: Map<String, ByteArray>,
    )

    private companion object {
        const val SCHEMA_VERSION = 1
        const val ENGINE_ID = "zipvoice-distill-int8-zh-en"
        const val MANIFEST_PATH = "manifest.json"
        const val CHECKSUMS_PATH = "checksums.json"
        const val SAMPLE_RATE = 24_000
        const val MAX_PROMPT_SAMPLES = 60 * SAMPLE_RATE
        const val MAX_ENTRIES = 64
        const val MAX_PATH_BYTES = 240
        const val MAX_COMPRESSION_RATIO = 200L
        const val MAX_PACKAGE_BYTES = 250L * 1024L * 1024L
        const val MAX_MANIFEST_BYTES = 128 * 1024
        const val MAX_CHECKSUMS_BYTES = 256 * 1024
        const val MAX_TRANSCRIPT_BYTES = 64 * 1024
        const val MAX_TRANSCRIPT_CODE_POINTS = 10_000
        const val MAX_AUDIO_BYTES = 3 * 1024 * 1024
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val SHA_256_ALGORITHM = "SHA-256"
        const val BACKUP_SUFFIX = ".backup"
        val VOICE_ID = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+$")
        val SHA_256 = Regex("^[0-9a-f]{64}$")
        val SUPPORTED_LANGUAGES = setOf("zh-CN", "en")
        val SUPPORTED_EMOTIONS = setOf("neutral", "joy", "sadness", "anger", "tension")
        val IMPORT_LOCKS = ConcurrentHashMap<String, ReentrantLock>()

        fun processLock(id: String): ReentrantLock = IMPORT_LOCKS.computeIfAbsent(id) { ReentrantLock() }

        fun fail(code: String, message: String): Nothing = throw MkVoiceImportException(code, message)
    }
}

private class StrictJsonFieldScanner(
    private val content: String,
) {
    private var index = 0

    fun rejectDuplicates() {
        skipWhitespace()
        scanValue()
    }

    private fun scanValue() {
        skipWhitespace()
        if (index >= content.length) return
        when (content[index]) {
            '{' -> scanObject()
            '[' -> scanArray()
            '"' -> scanString()
            else -> {
                while (index < content.length && content[index] !in VALUE_DELIMITERS) index += 1
            }
        }
    }

    private fun scanObject() {
        index += 1
        val keys = hashSetOf<String>()
        skipWhitespace()
        if (consume('}')) return
        while (index < content.length) {
            skipWhitespace()
            if (index >= content.length || content[index] != '"') return
            val key = scanString()
            if (!keys.add(key)) {
                throw MkVoiceImportException("duplicate_field", "JSON object repeats field: $key")
            }
            skipWhitespace()
            if (!consume(':')) return
            scanValue()
            skipWhitespace()
            if (consume('}')) return
            if (!consume(',')) return
        }
    }

    private fun scanArray() {
        index += 1
        skipWhitespace()
        if (consume(']')) return
        while (index < content.length) {
            scanValue()
            skipWhitespace()
            if (consume(']')) return
            if (!consume(',')) return
        }
    }

    private fun scanString(): String {
        val start = index
        index += 1
        while (index < content.length) {
            when (content[index]) {
                '\\' -> {
                    index += 1
                    if (index < content.length) index += 1
                }
                '"' -> {
                    index += 1
                    val literal = content.substring(start, index)
                    return (Json.parseToJsonElement(literal) as JsonPrimitive).content
                }
                else -> index += 1
            }
        }
        return content.substring(start)
    }

    private fun consume(character: Char): Boolean {
        if (index >= content.length || content[index] != character) return false
        index += 1
        return true
    }

    private fun skipWhitespace() {
        while (index < content.length && content[index].isWhitespace()) index += 1
    }

    private companion object {
        val VALUE_DELIMITERS = charArrayOf(',', ']', '}', ' ', '\t', '\r', '\n')
    }
}
