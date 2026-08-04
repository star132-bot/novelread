package com.mkread.app.core.files

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubFixtureFactory(
    private val root: File,
) {
    fun zip(
        name: String,
        entries: List<Pair<String, ByteArray>>,
    ): File = File(root, name).also { file ->
        ZipOutputStream(FileOutputStream(file)).use { output ->
            entries.forEach { (entryName, bytes) ->
                output.putNextEntry(ZipEntry(entryName))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    fun zipWithEntryCount(name: String, count: Int): File = File(root, name).also { file ->
        ZipOutputStream(FileOutputStream(file)).use { output ->
            repeat(count) { index ->
                output.putNextEntry(ZipEntry("entry-$index.txt"))
                output.closeEntry()
            }
        }
    }

    fun validEpub(name: String = "valid.epub"): File = epub(
        name = name,
        title = "Wind and Snow",
        author = "MK Author",
        language = "zh-CN",
        manifestItems = listOf(
            item("ch1", "ch1.xhtml"),
            item("ch2", "ch2.xhtml"),
            item("nav", "nav.xhtml", properties = "nav"),
            item("cover", "cover.png", mediaType = "image/png", properties = "cover-image"),
        ),
        spine = listOf("ch2", "ch1"),
        contentEntries = mapOf(
            "OPS/ch1.xhtml" to xhtml("First", "First body."),
            "OPS/ch2.xhtml" to richXhtml(),
            "OPS/nav.xhtml" to "<html><body><nav>Navigation only</nav></body></html>",
            "OPS/cover.png" to PNG_BYTES,
        ),
    )

    fun missingOptionalMetadata(): File = epub(
        name = "missing-metadata.epub",
        title = null,
        author = null,
        language = null,
        manifestItems = listOf(item("ch1", "ch1.xhtml")),
        spine = listOf("ch1"),
        contentEntries = mapOf("OPS/ch1.xhtml" to xhtml("Only", "Readable body.")),
    )

    fun malformedOptionalNav(): File = epub(
        name = "malformed-nav.epub",
        title = "Still Readable",
        manifestItems = listOf(
            item("ch1", "ch1.xhtml"),
            item("nav", "nav.xhtml", properties = "nav"),
        ),
        spine = listOf("ch1"),
        contentEntries = mapOf(
            "OPS/ch1.xhtml" to xhtml("Only", "Readable body."),
            "OPS/nav.xhtml" to "<html><nav><ol><li>broken",
        ),
    )

    fun unreadableThenReadableSpine(): File = epub(
        name = "skip-unreadable.epub",
        title = "Partial",
        manifestItems = listOf(
            item("missing", "missing.xhtml"),
            item("ch1", "ch1.xhtml"),
        ),
        spine = listOf("missing", "ch1"),
        contentEntries = mapOf("OPS/ch1.xhtml" to xhtml("Readable", "Kept body.")),
    )

    fun noReadableSpine(): File = epub(
        name = "no-readable.epub",
        title = "Empty",
        manifestItems = listOf(item("missing", "missing.xhtml")),
        spine = listOf("missing"),
        contentEntries = emptyMap(),
    )

    fun epub2Cover(): File = epub(
        name = "epub2-cover.epub",
        title = "EPUB 2 Cover",
        extraMetadata = "<meta name=\"cover\" content=\"cover\"/>",
        manifestItems = listOf(
            item("ch1", "ch1.xhtml"),
            item("cover", "cover.png", mediaType = "image/png"),
        ),
        spine = listOf("ch1"),
        contentEntries = mapOf(
            "OPS/ch1.xhtml" to xhtml("Only", "Readable body."),
            "OPS/cover.png" to PNG_BYTES,
        ),
    )

    fun unsafeSpineHref(): File = epub(
        name = "unsafe-href.epub",
        title = "Unsafe",
        manifestItems = listOf(item("outside", "../outside.xhtml")),
        spine = listOf("outside"),
        contentEntries = mapOf("outside.xhtml" to xhtml("Outside", "Must not load.")),
    )

    fun doctypeContainer(): File = zip(
        name = "doctype.epub",
        entries = listOf(
            "mimetype" to EPUB_MIMETYPE.toByteArray(Charsets.UTF_8),
            "META-INF/container.xml" to (
                """<!DOCTYPE container [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>""" +
                    """<container><rootfiles><rootfile full-path="OPS/content.opf"/></rootfiles></container>"""
                ).toByteArray(Charsets.UTF_8),
            "OPS/content.opf" to "<package/>".toByteArray(Charsets.UTF_8),
        ),
    )

    private fun epub(
        name: String,
        title: String?,
        author: String? = "Author",
        language: String? = "zh",
        extraMetadata: String = "",
        manifestItems: List<String>,
        spine: List<String>,
        contentEntries: Map<String, Any>,
    ): File {
        val metadata = buildString {
            if (title != null) append("<dc:title>$title</dc:title>")
            if (author != null) append("<dc:creator>$author</dc:creator>")
            if (language != null) append("<dc:language>$language</dc:language>")
        }
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf"
                     xmlns:dc="http://purl.org/dc/elements/1.1/"
                     version="3.0">
              <metadata>$metadata$extraMetadata</metadata>
              <manifest>${manifestItems.joinToString("")}</manifest>
              <spine>${spine.joinToString("") { "<itemref idref=\"$it\"/>" }}</spine>
            </package>
        """.trimIndent()
        val entries = mutableListOf(
            "mimetype" to EPUB_MIMETYPE.toByteArray(Charsets.UTF_8),
            "META-INF/container.xml" to CONTAINER_XML.toByteArray(Charsets.UTF_8),
            "OPS/content.opf" to opf.toByteArray(Charsets.UTF_8),
        )
        contentEntries.forEach { (path, value) ->
            val bytes = when (value) {
                is String -> value.toByteArray(Charsets.UTF_8)
                is ByteArray -> value
                else -> error("Unsupported fixture content")
            }
            entries += path to bytes
        }
        return zip(name, entries)
    }

    private fun item(
        id: String,
        href: String,
        mediaType: String = "application/xhtml+xml",
        properties: String? = null,
    ): String = buildString {
        append("<item id=\"$id\" href=\"$href\" media-type=\"$mediaType\"")
        if (properties != null) append(" properties=\"$properties\"")
        append("/>")
    }

    private fun xhtml(title: String, body: String): String = """
        <html xmlns="http://www.w3.org/1999/xhtml">
          <body><h1>$title</h1><p>$body</p></body>
        </html>
    """.trimIndent()

    private fun richXhtml(): String = """
        <html xmlns="http://www.w3.org/1999/xhtml">
          <head><style>.hidden { display: none; }</style></head>
          <body>
            <h1>Second</h1>
            <p>Second body.</p>
            <script>script must disappear</script>
            <nav>nav must disappear</nav>
            <p hidden="hidden">hidden must disappear</p>
            <p aria-hidden="true">aria hidden must disappear</p>
            <ul><li>List item</li></ul>
            <p>Line<br/>break</p>
            <hr/>
          </body>
        </html>
    """.trimIndent()

    companion object {
        const val EPUB_MIMETYPE = "application/epub+zip"
        val PNG_BYTES = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x00, 0x00, 0x00,
        )
        private val CONTAINER_XML = """
            <?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
              <rootfiles>
                <rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()
    }
}
