package com.mkread.app.core.files

import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MkBookParserTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val parser = MkBookParser()

    @Test
    fun validPackage_importsChaptersInManifestOrderWithIds() {
        val book = parser.parse(buildPackage())

        assertEquals("夜行者", book.title)
        assertEquals("某某", book.author)
        assertEquals("yexing-zhe", book.catalogId)
        assertEquals(3, book.revision)
        assertEquals(listOf("第1章 夜访", "第2章 旧事"), book.chapters.map { it.title })
        assertEquals(listOf("c0001", "c0002"), book.chapters.map { it.externalId })
        assertEquals("第一卷", book.chapters.first().volume)
        assertEquals("夜色渐深。\n※※※\n他来了。", book.chapters.first().text)
    }

    @Test
    fun tamperedChapter_isRejected() {
        val source = buildPackage(tamper = { path, bytes -> if (path == "chapters/c0002.txt") bytes + 0x41 else bytes })

        val failure = assertThrows(BookParseException::class.java) { parser.parse(source) }

        assertEquals(BookParseFailure.MALFORMED_MKBOOK, failure.failure)
    }

    @Test
    fun newerFormatVersion_asksForAnUpgrade() {
        val failure = assertThrows(BookParseException::class.java) {
            parser.parse(buildPackage(formatVersion = 3))
        }

        assertEquals(BookParseFailure.UNSUPPORTED_VERSION, failure.failure)
    }

    @Test
    fun wrongCharacterCount_isRejected() {
        val failure = assertThrows(BookParseException::class.java) {
            parser.parse(buildPackage(charsDelta = 1))
        }

        assertEquals(BookParseFailure.MALFORMED_MKBOOK, failure.failure)
    }

    @Test
    fun chapterPathMustMatchItsId() {
        val failure = assertThrows(BookParseException::class.java) {
            parser.parse(buildPackage(pathOverride = "chapters/other.txt"))
        }

        assertEquals(BookParseFailure.MALFORMED_MKBOOK, failure.failure)
    }

    @Test
    fun illustrations_areVerifiedAndReported() {
        val book = parser.parse(
            buildPackage(formatVersion = 2, chapters = ILLUSTRATED, images = mapOf("images/map.png" to PNG)),
        )

        assertEquals(mapOf("images/map.png" to PNG.sha256()), book.images)
        assertEquals("![地图](images/map.png)\n夜色渐深。", book.chapters.first().text)
    }

    @Test
    fun illustrations_needFormatVersion2() {
        val failure = assertThrows(BookParseException::class.java) {
            parser.parse(buildPackage(formatVersion = 1, chapters = ILLUSTRATED, images = mapOf("images/map.png" to PNG)))
        }

        assertEquals(BookParseFailure.MALFORMED_MKBOOK, failure.failure)
    }

    @Test
    fun undeclaredIllustration_isRejected() {
        val failure = assertThrows(BookParseException::class.java) {
            parser.parse(buildPackage(formatVersion = 2, chapters = ILLUSTRATED))
        }

        assertEquals(BookParseFailure.MALFORMED_MKBOOK, failure.failure)
    }

    @Test
    fun tamperedIllustration_isRejected() {
        val failure = assertThrows(BookParseException::class.java) {
            parser.parse(
                buildPackage(
                    formatVersion = 2,
                    chapters = ILLUSTRATED,
                    images = mapOf("images/map.png" to PNG),
                    tamper = { path, bytes -> if (path == "images/map.png") bytes + 0x41 else bytes },
                ),
            )
        }

        assertEquals(BookParseFailure.MALFORMED_MKBOOK, failure.failure)
    }

    @Test
    fun illustrationWhoseContentIsNotItsType_isRejected() {
        val failure = assertThrows(BookParseException::class.java) {
            parser.parse(
                buildPackage(formatVersion = 2, chapters = ILLUSTRATED, images = mapOf("images/map.png" to JPEG)),
            )
        }

        assertEquals(BookParseFailure.MALFORMED_MKBOOK, failure.failure)
    }

    private fun buildPackage(
        formatVersion: Int = 1,
        charsDelta: Int = 0,
        pathOverride: String? = null,
        chapters: List<Triple<String, String, String>> = PLAIN,
        images: Map<String, ByteArray> = emptyMap(),
        tamper: (String, ByteArray) -> ByteArray = { _, bytes -> bytes },
    ): File {
        val chapterJson = chapters.mapIndexed { index, (id, title, text) ->
            val bytes = text.toByteArray(Charsets.UTF_8)
            val path = if (index == 0 && pathOverride != null) pathOverride else "chapters/$id.txt"
            val volume = if (index == 0) ""","volume":"第一卷"""" else ""
            """{"id":"$id","title":"$title"$volume,"path":"$path",""" +
                """"chars":${text.codePointCount(0, text.length) + charsDelta},"sha256":"${bytes.sha256()}"}"""
        }
        val imageJson = if (images.isEmpty()) {
            ""
        } else {
            images.entries.joinToString(",", prefix = ",\"images\":[", postfix = "]") { (path, bytes) ->
                """{"path":"$path","size":${bytes.size},"sha256":"${bytes.sha256()}"}"""
            }
        }
        val manifest = """
            {"format":"mkbook","formatVersion":$formatVersion,"id":"yexing-zhe","revision":3,
             "metadata":{"title":"夜行者","author":"某某","language":"zh-CN"},
             "chapters":[${chapterJson.joinToString(",")}]$imageJson}
        """.trimIndent()
        val file = temporaryFolder.newFile("book.mkbook")
        ZipOutputStream(file.outputStream()).use { zip ->
            val mimetype = MkBookParser.MIMETYPE.toByteArray(Charsets.US_ASCII)
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimetype.size.toLong()
                    crc = CRC32().apply { update(mimetype) }.value
                },
            )
            zip.write(mimetype)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("mkbook.json"))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            chapters.forEach { (id, _, text) ->
                val path = "chapters/$id.txt"
                zip.putNextEntry(ZipEntry(path))
                zip.write(tamper(path, text.toByteArray(Charsets.UTF_8)))
                zip.closeEntry()
            }
            images.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(tamper(path, bytes))
                zip.closeEntry()
            }
        }
        return file
    }

    private companion object {
        val PLAIN = listOf(
            Triple("c0001", "第1章 夜访", "夜色渐深。\n※※※\n他来了。"),
            Triple("c0002", "第2章 旧事", "老人掏出一张照片。"),
        )
        val ILLUSTRATED = listOf(
            Triple("c0001", "第1章 夜访", "![地图](images/map.png)\n夜色渐深。"),
            Triple("c0002", "第2章 旧事", "老人掏出一张照片。\n![](images/map.png)"),
        )
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) + ByteArray(16)
        val JPEG = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte()) + ByteArray(16)
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { "%02x".format(it) }
}
