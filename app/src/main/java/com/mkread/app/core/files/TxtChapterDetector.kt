package com.mkread.app.core.files

class TxtChapterDetector {
    fun isHeading(line: String): Boolean {
        if (line.codePointCount(0, line.length) > MAX_HEADING_CODE_POINTS) return false
        return HEADING.matches(line)
    }

    fun detect(normalizedText: String): List<ParsedChapter> {
        val lines = normalizedText.split('\n')
        var offset = 0
        val headings = buildList {
            lines.forEachIndexed { index, line ->
                if (isHeading(line)) {
                    add(Heading(index = index, startOffset = offset, title = line.trim()))
                }
                offset += line.length + 1
            }
        }
        val confident = headings.size >= 2 || (
            headings.size == 1 &&
                headings.single().startOffset.toLong() * 10L <= normalizedText.length.toLong()
            )
        if (!confident) {
            return listOf(ParsedChapter(title = DEFAULT_CHAPTER_TITLE, text = normalizedText))
        }

        val firstHeadingIndex = headings.first().index
        return headings.mapIndexed { headingIndex, heading ->
            val nextHeadingLine = headings.getOrNull(headingIndex + 1)?.index ?: lines.size
            val body = ArrayList<String>()
            if (headingIndex == 0 && firstHeadingIndex > 0) {
                body += lines.subList(0, firstHeadingIndex)
            }
            body += lines.subList(heading.index + 1, nextHeadingLine)
            ParsedChapter(
                title = heading.title,
                text = body.joinToString("\n").trimBoundaryBlankLines(),
            )
        }.filter { chapter -> chapter.text.isNotBlank() }
    }

    private fun String.trimBoundaryBlankLines(): String {
        val lines = split('\n')
        val firstContent = lines.indexOfFirst { it.isNotBlank() }
        if (firstContent < 0) return ""
        val lastContent = lines.indexOfLast { it.isNotBlank() }
        return lines.subList(firstContent, lastContent + 1).joinToString("\n")
    }

    private data class Heading(
        val index: Int,
        val startOffset: Int,
        val title: String,
    )

    companion object {
        const val DEFAULT_CHAPTER_TITLE = "正文"
        private const val MAX_HEADING_CODE_POINTS = 80
        private val HEADING = Regex(
            pattern = """^\s*(?:(?:第\s*[零〇一二两三四五六七八九十百千万0-9]{1,12}\s*[章节卷回部篇])|(?:[卷部篇]\s*[零〇一二两三四五六七八九十百千万0-9]{1,12})|(?:序章|楔子|后记|尾声))(?:[\s:：._-]+.{0,60})?\s*$""",
            option = RegexOption.IGNORE_CASE,
        )
    }
}
