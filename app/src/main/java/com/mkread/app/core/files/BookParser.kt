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
    /** MKBook packages carry a stable book id and revision used for in-place updates. */
    val catalogId: String? = null,
    val revision: Int? = null,
    /** MKBook illustrations: package path (`images/<name>`) → SHA-256, verified while parsing. */
    val images: Map<String, String> = emptyMap(),
)

data class ParsedChapter(
    val title: String,
    val text: String,
    val externalId: String? = null,
    val volume: String? = null,
)

enum class BookParseFailure {
    INVALID_ENCODING,
    INVALID_CONTENT,
    NO_READABLE_CONTENT,
    SOURCE_TOO_LARGE,
    CHAPTER_TOO_LARGE,
    MALFORMED_EPUB,
    MALFORMED_MKBOOK,
    UNSUPPORTED_VERSION,
}

class BookParseException(
    val failure: BookParseFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
