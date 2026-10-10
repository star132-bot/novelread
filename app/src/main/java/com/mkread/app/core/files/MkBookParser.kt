package com.mkread.app.core.files

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Parses MKBook packages (docs/formats/mkbook-v1.md). Every chapter is checked against the
 * manifest's path, SHA-256 and character count, so a package either imports exactly as
 * published or is rejected.
 */
class MkBookParser(
    private val zipReader: SafeZipReader = SafeZipReader(expandedByteLimit = ImportLimits.EXPANDED_MKBOOK_BYTES),
) : BookParser {
    override fun parse(source: File, sourceName: String): ParsedBook {
        try {
            zipReader.open(source).use { archive ->
                val mimetype = archive.read(MIMETYPE_PATH, MIMETYPE.length.toLong() + 1L)
                malformedUnless(mimetype.toString(Charsets.US_ASCII) == MIMETYPE) { "mimetype is not MKBook" }
                val manifest = Json.parseToJsonElement(
                    archive.read(MANIFEST_PATH, MAX_MANIFEST_BYTES).decodeUtf8(),
                ) as? JsonObject ?: malformed("Manifest is not an object")

                malformedUnless(manifest.string("format") == FORMAT) { "format must be mkbook" }
                val version = (manifest["formatVersion"] as? JsonPrimitive)?.intOrNull
                    ?: malformed("formatVersion is missing")
                if (version > FORMAT_VERSION) {
                    throw BookParseException(
                        BookParseFailure.UNSUPPORTED_VERSION,
                        "MKBook formatVersion $version is newer than this app supports",
                    )
                }
                malformedUnless(version >= 1) { "formatVersion is invalid" }
                val bookId = manifest.string("id")
                malformedUnless(bookId != null && BOOK_ID.matches(bookId)) { "Book id is invalid" }
                val revision = (manifest["revision"] as? JsonPrimitive)?.intOrNull
                malformedUnless(revision != null && revision >= 1) { "revision must be a positive integer" }

                val metadata = manifest["metadata"] as? JsonObject ?: malformed("metadata is missing")
                val title = metadata.string("title")
                malformedUnless(title != null && title.length in 1..MAX_TITLE_LENGTH) { "Book title is invalid" }

                val images = readImages(archive, manifest, version)
                val entries = manifest["chapters"] as? JsonArray ?: malformed("chapters is missing")
                malformedUnless(entries.size in 1..MAX_CHAPTERS) { "Chapter count is out of range" }
                val seenIds = HashSet<String>()
                val chapters = entries.mapIndexed { index, element ->
                    val entry = element as? JsonObject ?: malformed("Chapter ${index + 1} is not an object")
                    readChapter(archive, entry, index, seenIds).also { chapter ->
                        if (version >= IMAGES_FORMAT_VERSION) {
                            InlineImage.lineRanges(chapter.text).forEach { line ->
                                val image = InlineImage.parse(chapter.text.substring(line.first, line.last + 1))
                                malformedUnless(image != null && image.path in images) {
                                    "Chapter ${index + 1} refers to an undeclared image"
                                }
                            }
                        }
                    }
                }

                return ParsedBook(
                    title = title!!,
                    author = metadata.string("author")?.takeIf(String::isNotBlank),
                    language = metadata.string("language"),
                    coverBytes = metadata.string("cover")?.let { cover ->
                        malformedUnless(cover in COVER_PATHS) { "Cover path is invalid" }
                        archive.read(cover, ImportLimits.COVER_BYTES)
                    },
                    chapters = chapters,
                    catalogId = bookId,
                    revision = revision,
                    images = images,
                )
            }
        } catch (failure: BookParseException) {
            throw failure
        } catch (failure: Exception) {
            throw BookParseException(BookParseFailure.MALFORMED_MKBOOK, "MKBook package is malformed", failure)
        }
    }

    /** Checks the `images` list (spec §1.4) and returns path → SHA-256. */
    private fun readImages(archive: SafeZipArchive, manifest: JsonObject, version: Int): Map<String, String> {
        val element = manifest["images"] ?: return emptyMap()
        malformedUnless(version >= IMAGES_FORMAT_VERSION) { "images requires formatVersion 2" }
        val entries = element as? JsonArray ?: malformed("images is not an array")
        malformedUnless(entries.size <= ImportLimits.IMAGES) { "Too many images" }
        val images = LinkedHashMap<String, String>()
        entries.forEachIndexed { index, item ->
            val entry = item as? JsonObject ?: malformed("Image ${index + 1} is not an object")
            val path = entry.string("path")
            malformedUnless(path != null && InlineImage.PATH.matches(path) && path !in images) {
                "Image ${index + 1} path is invalid or duplicated"
            }
            val size = (entry["size"] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull
            malformedUnless(size != null && size in 1..ImportLimits.IMAGE_BYTES) { "Image ${index + 1} size is invalid" }
            val bytes = archive.read(path!!, ImportLimits.IMAGE_BYTES)
            malformedUnless(bytes.size.toLong() == size) { "Image ${index + 1} size does not match" }
            val sha256 = bytes.sha256()
            malformedUnless(sha256 == entry.string("sha256")) { "Image ${index + 1} hash does not match" }
            malformedUnless(imageExtension(bytes) == path.substringAfterLast('.')) {
                "Image ${index + 1} is not the image type its name says"
            }
            images[path] = sha256
        }
        return images
    }

    private fun imageExtension(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "jpg"
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> "png"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "webp"
        else -> null
    }

    private fun readChapter(
        archive: SafeZipArchive,
        entry: JsonObject,
        index: Int,
        seenIds: MutableSet<String>,
    ): ParsedChapter {
        val id = entry.string("id")
        malformedUnless(id != null && CHAPTER_ID.matches(id) && seenIds.add(id)) {
            "Chapter ${index + 1} id is invalid or duplicated"
        }
        val title = entry.string("title")
        malformedUnless(title != null && title.length in 1..MAX_TITLE_LENGTH) { "Chapter ${index + 1} title is invalid" }
        val path = entry.string("path")
        malformedUnless(path == "chapters/$id.txt") { "Chapter ${index + 1} path is invalid" }
        val bytes = archive.read(path!!, MAX_CHAPTER_BYTES)
        malformedUnless(bytes.sha256() == entry.string("sha256")) { "Chapter ${index + 1} hash does not match" }
        val text = bytes.decodeUtf8()
        val characters = text.codePointCount(0, text.length)
        if (characters > ImportLimits.CHAPTER_CHARACTERS) {
            throw BookParseException(BookParseFailure.CHAPTER_TOO_LARGE, "Chapter ${index + 1} is too large")
        }
        malformedUnless((entry["chars"] as? JsonPrimitive)?.intOrNull == characters) {
            "Chapter ${index + 1} character count does not match"
        }
        return ParsedChapter(
            title = title!!,
            text = text,
            externalId = id,
            volume = entry.string("volume")?.takeIf(String::isNotBlank),
        )
    }

    private fun ByteArray.decodeUtf8(): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    } catch (failure: CharacterCodingException) {
        throw BookParseException(BookParseFailure.MALFORMED_MKBOOK, "MKBook text is not UTF-8", failure)
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun malformed(message: String): Nothing =
        throw BookParseException(BookParseFailure.MALFORMED_MKBOOK, message)

    private inline fun malformedUnless(condition: Boolean, message: () -> String) {
        if (!condition) malformed(message())
    }

    companion object {
        const val MIMETYPE = "application/vnd.mkread.book+zip"
        const val MIMETYPE_PATH = "mimetype"
        private const val MANIFEST_PATH = "mkbook.json"
        private const val FORMAT = "mkbook"
        /** Version 2 adds illustrations; version 1 books are plain text. */
        private const val FORMAT_VERSION = 2
        private const val IMAGES_FORMAT_VERSION = 2
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        private const val MAX_TITLE_LENGTH = 200
        private const val MAX_CHAPTERS = 20_000
        private const val MAX_MANIFEST_BYTES = 8L * 1024L * 1024L
        private const val MAX_CHAPTER_BYTES = ImportLimits.CHAPTER_CHARACTERS * 4L
        private val BOOK_ID = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
        private val CHAPTER_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val COVER_PATHS = setOf("cover.jpg", "cover.png", "cover.webp")
    }
}
