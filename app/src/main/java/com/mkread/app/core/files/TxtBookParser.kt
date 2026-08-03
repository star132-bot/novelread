package com.mkread.app.core.files

import java.io.File
import java.io.IOException

class TxtBookParser(
    private val encodingDetector: TxtEncodingDetector = TxtEncodingDetector(),
    private val chapterDetector: TxtChapterDetector = TxtChapterDetector(),
) : BookParser {
    override fun parse(source: File): ParsedBook {
        if (!source.isFile) {
            throw BookParseException(BookParseFailure.INVALID_CONTENT, "TXT source is not a file")
        }
        if (source.length() > ImportLimits.SOURCE_BYTES) {
            throw BookParseException(
                BookParseFailure.SOURCE_TOO_LARGE,
                "TXT exceeds the ${ImportLimits.SOURCE_BYTES} byte limit",
            )
        }
        val bytes = try {
            source.readBytes()
        } catch (failure: IOException) {
            throw BookParseException(BookParseFailure.INVALID_CONTENT, "Unable to read TXT", failure)
        }
        val decoded = encodingDetector.decode(bytes)
        if (decoded.text.isBlank()) {
            throw BookParseException(
                BookParseFailure.NO_READABLE_CONTENT,
                "TXT contains no readable text",
            )
        }
        val chapters = chapterDetector.detect(decoded.text)
        if (chapters.any { it.text.length > ImportLimits.CHAPTER_CHARACTERS }) {
            throw BookParseException(
                BookParseFailure.CHAPTER_TOO_LARGE,
                "TXT contains a chapter over the character limit",
            )
        }
        return ParsedBook(
            title = deriveTitle(decoded.text, source),
            author = null,
            language = null,
            coverBytes = null,
            chapters = chapters,
        )
    }

    private fun deriveTitle(text: String, source: File): String {
        val firstNonEmpty = text.lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
        if (
            firstNonEmpty != null &&
            firstNonEmpty.codePointCount(0, firstNonEmpty.length) <= MAX_TITLE_CODE_POINTS &&
            !chapterDetector.isHeading(firstNonEmpty)
        ) {
            return firstNonEmpty
        }
        return source.nameWithoutExtension.ifBlank { FALLBACK_TITLE }
    }

    private companion object {
        const val MAX_TITLE_CODE_POINTS = 60
        const val FALLBACK_TITLE = "Untitled"
    }
}
