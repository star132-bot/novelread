package com.mkread.app.core.files

import java.io.File
import java.io.ByteArrayInputStream
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import org.w3c.dom.Document
import org.w3c.dom.NodeList

class EpubBookParser(
    private val zipReader: SafeZipReader = SafeZipReader(),
) : BookParser {
    override fun parse(source: File): ParsedBook {
        try {
            zipReader.open(source).use { archive ->
                requireEpubMimetype(archive)
                val packagePath = packagePath(archive)
                val packageDocument = SecureXml.parse(archive.read(packagePath, MAX_XML_BYTES))
                val packageDirectory = packagePath.substringBeforeLast('/', "")
                val manifest = parseManifest(packageDocument)
                val chapters = parseSpine(
                    archive = archive,
                    document = packageDocument,
                    packageDirectory = packageDirectory,
                    manifest = manifest,
                )
                if (chapters.isEmpty()) {
                    throw BookParseException(
                        BookParseFailure.NO_READABLE_CONTENT,
                        "EPUB spine contains no readable chapters",
                    )
                }
                return ParsedBook(
                    title = packageDocument.firstText("title")
                        ?: source.nameWithoutExtension.ifBlank { "Untitled" },
                    author = packageDocument.firstText("creator"),
                    language = packageDocument.firstText("language"),
                    coverBytes = readCover(
                        archive,
                        packageDocument,
                        packageDirectory,
                        manifest,
                    ),
                    chapters = chapters,
                )
            }
        } catch (failure: BookParseException) {
            throw failure
        } catch (failure: Exception) {
            throw BookParseException(
                BookParseFailure.MALFORMED_EPUB,
                "EPUB container is malformed or unsafe",
                failure,
            )
        }
    }

    private fun requireEpubMimetype(archive: SafeZipArchive) {
        val mimetype = archive.read(MIMETYPE_PATH, MAX_MIMETYPE_BYTES)
            .toString(Charsets.US_ASCII)
        if (mimetype != EPUB_MIMETYPE) {
            throw BookParseException(BookParseFailure.MALFORMED_EPUB, "Invalid EPUB mimetype")
        }
    }

    private fun packagePath(archive: SafeZipArchive): String {
        val container = SecureXml.parse(archive.read(CONTAINER_PATH, MAX_XML_BYTES))
        val rootfile = container.elements("rootfile").firstOrNull()
            ?: throw BookParseException(BookParseFailure.MALFORMED_EPUB, "Missing EPUB rootfile")
        val path = rootfile.getAttribute("full-path")
        return SafeZipPath.normalizeEntry(path)
    }

    private fun parseManifest(document: Document): Map<String, ManifestItem> = buildMap {
        document.elements("item").forEach { element ->
            val id = element.getAttribute("id").trim()
            val href = element.getAttribute("href").trim()
            if (id.isNotEmpty() && href.isNotEmpty()) {
                put(
                    id,
                    ManifestItem(
                        id = id,
                        href = href,
                        mediaType = element.getAttribute("media-type").trim(),
                        properties = element.getAttribute("properties")
                            .split(Regex("\\s+"))
                            .filter(String::isNotBlank)
                            .toSet(),
                    ),
                )
            }
        }
    }

    private fun parseSpine(
        archive: SafeZipArchive,
        document: Document,
        packageDirectory: String,
        manifest: Map<String, ManifestItem>,
    ): List<ParsedChapter> = buildList {
        document.elements("itemref").forEach { itemref ->
            if (itemref.getAttribute("linear").equals("no", ignoreCase = true)) return@forEach
            val item = manifest[itemref.getAttribute("idref")] ?: return@forEach
            if (item.mediaType !in XHTML_MEDIA_TYPES) return@forEach
            val path = try {
                SafeZipPath.resolve(packageDirectory, item.href)
            } catch (failure: SafeZipException) {
                throw BookParseException(
                    BookParseFailure.MALFORMED_EPUB,
                    "Unsafe EPUB spine href",
                    failure,
                )
            }
            val bytes = try {
                archive.read(path, MAX_XHTML_BYTES)
            } catch (failure: SafeZipException) {
                if (
                    failure.failure == SafeZipFailure.ENTRY_NOT_FOUND ||
                    failure.failure == SafeZipFailure.ENCRYPTED_OR_UNREADABLE
                ) {
                    return@forEach
                }
                throw failure
            }
            val chapter = try {
                parseXhtml(bytes, path)
            } catch (failure: BookParseException) {
                throw failure
            } catch (_: Exception) {
                return@forEach
            }
            if (chapter.text.isNotBlank()) add(chapter)
        }
    }

    private fun parseXhtml(bytes: ByteArray, path: String): ParsedChapter {
        val document = ByteArrayInputStream(bytes).use { input ->
            Jsoup.parse(input, null, "", Parser.xmlParser())
        }
        document.select("script, style, nav, [hidden], [aria-hidden=true]").remove()
        val root = document.body() ?: document.children().firstOrNull() ?: document
        val heading = root.selectFirst("h1, h2, h3, h4, h5, h6")
        val title = heading?.text()?.trim()?.takeIf(String::isNotEmpty)
            ?: path.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Chapter" }
        heading?.remove()
        val text = renderText(root)
        if (text.length > ImportLimits.CHAPTER_CHARACTERS) {
            throw BookParseException(
                BookParseFailure.CHAPTER_TOO_LARGE,
                "EPUB chapter exceeds the character limit",
            )
        }
        return ParsedChapter(title = title, text = text)
    }

    private fun renderText(root: Node): String {
        val rendered = StringBuilder()
        NodeTraversor.traverse(
            object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    when (node) {
                        is TextNode -> rendered.append(node.wholeText)
                        is Element -> when (node.normalName()) {
                            "br" -> rendered.ensureNewline()
                            "hr" -> {
                                rendered.ensureNewline()
                                rendered.append("* * *")
                                rendered.ensureNewline()
                            }
                            "li" -> {
                                rendered.ensureNewline()
                                rendered.append("- ")
                            }
                            in BLOCK_TAGS -> rendered.ensureNewline()
                        }
                    }
                }

                override fun tail(node: Node, depth: Int) {
                    if (node is Element && (node.normalName() in BLOCK_TAGS || node.normalName() == "li")) {
                        rendered.ensureNewline()
                    }
                }
            },
            root,
        )
        return normalizeRendered(rendered.toString())
    }

    private fun normalizeRendered(value: String): String {
        val lines = value
            .replace('\u00a0', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map { line -> line.replace(INLINE_WHITESPACE, " ").trim() }
        val output = ArrayList<String>()
        var blankRun = 0
        lines.forEach { line ->
            if (line.isEmpty()) {
                blankRun += 1
                if (blankRun <= 2 && output.isNotEmpty()) output += ""
            } else {
                blankRun = 0
                output += line
            }
        }
        while (output.lastOrNull().isNullOrEmpty()) output.removeAt(output.lastIndex)
        return output.joinToString("\n")
    }

    private fun readCover(
        archive: SafeZipArchive,
        document: Document,
        packageDirectory: String,
        manifest: Map<String, ManifestItem>,
    ): ByteArray? {
        val epub3 = manifest.values.firstOrNull { "cover-image" in it.properties }
        val epub2Id = document.elements("meta")
            .firstOrNull { it.getAttribute("name").equals("cover", ignoreCase = true) }
            ?.getAttribute("content")
        val item = epub3 ?: manifest[epub2Id] ?: return null
        val path = try {
            SafeZipPath.resolve(packageDirectory, item.href)
        } catch (_: SafeZipException) {
            return null
        }
        val bytes = try {
            archive.read(path, MAX_COVER_BYTES)
        } catch (_: SafeZipException) {
            return null
        }
        return bytes.takeIf(::hasSupportedImageSignature)
    }

    private fun hasSupportedImageSignature(bytes: ByteArray): Boolean =
        bytes.startsWith(JPEG_SIGNATURE) ||
            bytes.startsWith(PNG_SIGNATURE) ||
            (
                bytes.size >= 12 &&
                    bytes.copyOfRange(0, 4).contentEquals(RIFF_SIGNATURE) &&
                    bytes.copyOfRange(8, 12).contentEquals(WEBP_SIGNATURE)
                )

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

    private fun Document.firstText(localName: String): String? = elements(localName)
        .firstOrNull()
        ?.textContent
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    private fun Document.elements(localName: String): List<org.w3c.dom.Element> {
        val namespaced = getElementsByTagNameNS("*", localName)
        val nodes = if (namespaced.length > 0) namespaced else getElementsByTagName(localName)
        return nodes.asElements()
    }

    private fun NodeList.asElements(): List<org.w3c.dom.Element> = buildList {
        repeat(length) { index ->
            val node = item(index)
            if (node is org.w3c.dom.Element) add(node)
        }
    }

    private fun StringBuilder.ensureNewline() {
        if (isNotEmpty() && last() != '\n') append('\n')
    }

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: Set<String>,
    )

    private companion object {
        const val MIMETYPE_PATH = "mimetype"
        const val EPUB_MIMETYPE = "application/epub+zip"
        const val CONTAINER_PATH = "META-INF/container.xml"
        const val MAX_MIMETYPE_BYTES = 128L
        const val MAX_XML_BYTES = 5L * 1024L * 1024L
        const val MAX_XHTML_BYTES = 20L * 1024L * 1024L
        const val MAX_COVER_BYTES = 10L * 1024L * 1024L
        val XHTML_MEDIA_TYPES = setOf("application/xhtml+xml", "text/html")
        val BLOCK_TAGS = setOf(
            "address", "article", "aside", "blockquote", "div", "footer", "header",
            "h1", "h2", "h3", "h4", "h5", "h6", "main", "ol", "p", "pre",
            "section", "table", "ul",
        )
        val INLINE_WHITESPACE = Regex("[\\t\\u000b\\u000c ]+")
        val JPEG_SIGNATURE = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        val RIFF_SIGNATURE = "RIFF".toByteArray(Charsets.US_ASCII)
        val WEBP_SIGNATURE = "WEBP".toByteArray(Charsets.US_ASCII)
    }
}
