package com.mkread.app.core.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TxtChapterDetectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val detector = TxtChapterDetector()

    @Test
    fun reviewedChineseHeadings_areRecognizedWithSurroundingWhitespace() {
        val headings = listOf(
            "第1章 初见",
            "第一百二十章 风雪",
            "第 8 节 重逢",
            "卷二 北境",
            "序章",
            "楔子",
            "后记",
            "尾声",
        )

        headings.forEach { heading ->
            assertTrue("Expected heading: $heading", detector.isHeading("  $heading  "))
        }
    }

    @Test
    fun headingMustBeAnchoredAndAtMost80CodePoints() {
        assertFalse(detector.isHeading("他说：第1章 初见"))
        assertFalse(detector.isHeading("正文里的第2章不是标题"))
        assertFalse(detector.isHeading("第1章 " + "长".repeat(81)))
    }

    @Test
    fun twoHeadings_splitInOrder_withoutDroppingBodyText() {
        val normalized = "第1章 初见\n第一段\n第二段\n第二章 重逢\n第三段"

        val chapters = detector.detect(normalized)

        assertEquals(listOf("第1章 初见", "第二章 重逢"), chapters.map { it.title })
        assertEquals(listOf("第一段\n第二段", "第三段"), chapters.map { it.text })
        val reconstructed = chapters.joinToString("\n") { chapter ->
            "${chapter.title}\n${chapter.text}"
        }
        assertEquals(normalized, reconstructed)
    }

    @Test
    fun emptyVolumeHeadingsAreNotReturnedAsReadableChapters() {
        val normalized = "第一卷\n第一章 初见\n第一章正文\n第二卷\n第二章 重逢\n第二章正文"

        val chapters = detector.detect(normalized)

        assertEquals(listOf("第一章 初见", "第二章 重逢"), chapters.map { it.title })
        assertEquals(listOf("第一章正文", "第二章正文"), chapters.map { it.text })
    }

    @Test
    fun oneHeadingNearStart_isAccepted_butLateSingleHeadingFallsBack() {
        val nearStart = detector.detect("第1章 开始\n正文")
        val lateHeadingText = "前言内容".repeat(30) + "\n第1章 太晚\n正文"
        val late = detector.detect(lateHeadingText)

        assertEquals("第1章 开始", nearStart.single().title)
        assertEquals("正文", nearStart.single().text)
        assertEquals("正文", late.single().title)
        assertEquals(lateHeadingText, late.single().text)
    }

    @Test
    fun noHeadings_returnsOneSyntheticBodyChapter() {
        val text = fixture("no-headings.txt")

        val chapters = detector.detect(text)

        assertEquals(1, chapters.size)
        assertEquals("正文", chapters.single().title)
        assertEquals(text, chapters.single().text)
    }

    @Test
    fun parserDerivesTitleFromShortNonHeadingFirstLine() {
        val file = temporaryFolder.newFile("fallback-name.txt").apply {
            writeText("夜雨归途\n\n第一段正文", Charsets.UTF_8)
        }

        val parsed = TxtBookParser().parse(file)

        assertEquals("夜雨归途", parsed.title)
        assertEquals(1, parsed.chapters.size)
    }

    @Test
    fun parserUsesFilenameWhenFirstLineIsHeading() {
        val file = temporaryFolder.newFile("fallback-name.txt").apply {
            writeText("第一章 初见\n正文", Charsets.UTF_8)
        }

        val parsed = TxtBookParser().parse(file)

        assertEquals("fallback-name", parsed.title)
        assertEquals("第一章 初见", parsed.chapters.single().title)
    }

    private fun fixture(name: String): String = requireNotNull(
        javaClass.getResourceAsStream("/txt/$name"),
    ).bufferedReader(Charsets.UTF_8).use { reader ->
        reader.readText().trimEnd('\r', '\n')
    }
}
