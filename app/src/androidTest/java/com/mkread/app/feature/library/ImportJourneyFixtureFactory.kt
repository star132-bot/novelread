package com.mkread.app.feature.library

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportJourneyFixtureFactory(
    private val root: File,
) {
    fun validEpub(): File = writeZip(
        name = "valid-journey.epub",
        entries = listOf(
            "mimetype" to EPUB_MIMETYPE,
            "META-INF/container.xml" to CONTAINER_XML,
            "OPS/content.opf" to VALID_OPF,
            "OPS/first.xhtml" to xhtml("第一章", "山路尽头亮起了第一盏灯。"),
            "OPS/second.xhtml" to xhtml("第二章", "晨雾散开，旧城的屋顶出现在河对岸。"),
        ),
    )

    fun malformedEpub(): File = writeZip(
        name = "malformed-journey.epub",
        entries = listOf(
            "mimetype" to EPUB_MIMETYPE,
            "META-INF/container.xml" to CONTAINER_XML.replace(
                "OPS/content.opf",
                "OPS/missing.opf",
            ),
        ),
    )

    private fun writeZip(
        name: String,
        entries: List<Pair<String, String>>,
    ): File = File(root, name).also { target ->
        ZipOutputStream(FileOutputStream(target)).use { output ->
            entries.forEach { (path, content) ->
                output.putNextEntry(ZipEntry(path).apply { time = 0L })
                output.write(content.toByteArray(Charsets.UTF_8))
                output.closeEntry()
            }
        }
    }

    private fun xhtml(title: String, body: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml">
          <body>
            <h1>$title</h1>
            <p>$body</p>
          </body>
        </html>
    """.trimIndent()

    private companion object {
        const val EPUB_MIMETYPE = "application/epub+zip"
        val CONTAINER_XML = """
            <?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
              <rootfiles>
                <rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()
        val VALID_OPF = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf"
                     xmlns:dc="http://purl.org/dc/elements/1.1/"
                     version="3.0">
              <metadata>
                <dc:title>山海旧闻</dc:title>
                <dc:creator>林舟</dc:creator>
                <dc:language>zh-CN</dc:language>
              </metadata>
              <manifest>
                <item id="first" href="first.xhtml" media-type="application/xhtml+xml"/>
                <item id="second" href="second.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="second"/>
                <itemref idref="first"/>
              </spine>
            </package>
        """.trimIndent()
    }
}
