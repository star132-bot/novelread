package com.mkread.app.core.files

import java.io.File

interface BookParser {
    fun parse(source: File, sourceName: String = source.name): ParsedBook
}

data class ParsedBook(
    val title: String,
    val author: String?,
    val language: String?,
    val coverBytes: ByteArray?,
    val chapters: List<ParsedChapter>,
)

data class ParsedChapter(
    val title: String,
    val text: String,
)

enum class BookParseFailure {
    INVALID_ENCODING,
    INVALID_CONTENT,
    NO_READABLE_CONTENT,
    SOURCE_TOO_LARGE,
    CHAPTER_TOO_LARGE,
    MALFORMED_EPUB,
}

class BookParseException(
    val failure: BookParseFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
