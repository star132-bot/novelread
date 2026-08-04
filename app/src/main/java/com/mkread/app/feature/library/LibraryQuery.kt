package com.mkread.app.feature.library

import com.mkread.app.core.model.LibrarySort

data class LibraryQuery(
    val search: String = "",
    val sort: LibrarySort = LibrarySort.LAST_OPENED,
)

enum class LibraryFailure {
    TITLE_REQUIRED,
    TITLE_TOO_LONG,
    AUTHOR_TOO_LONG,
    FILE_DELETE,
    DATABASE_DELETE,
    RECONCILIATION,
}

class LibraryException(
    val failure: LibraryFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal data class NormalizedBookMetadata(
    val title: String,
    val author: String?,
)

internal fun normalizeBookMetadata(
    title: String,
    author: String?,
): NormalizedBookMetadata {
    val normalizedTitle = title.normalizeBookWhitespace()
    if (normalizedTitle.isEmpty()) {
        throw LibraryException(LibraryFailure.TITLE_REQUIRED, "Book title is required")
    }
    if (normalizedTitle.codePointCount(0, normalizedTitle.length) > MAX_METADATA_CODE_POINTS) {
        throw LibraryException(
            LibraryFailure.TITLE_TOO_LONG,
            "Book title exceeds $MAX_METADATA_CODE_POINTS Unicode code points",
        )
    }
    val normalizedAuthor = author
        ?.normalizeBookWhitespace()
        ?.takeIf(String::isNotEmpty)
    if (
        normalizedAuthor != null &&
        normalizedAuthor.codePointCount(0, normalizedAuthor.length) > MAX_METADATA_CODE_POINTS
    ) {
        throw LibraryException(
            LibraryFailure.AUTHOR_TOO_LONG,
            "Book author exceeds $MAX_METADATA_CODE_POINTS Unicode code points",
        )
    }
    return NormalizedBookMetadata(normalizedTitle, normalizedAuthor)
}

internal fun String.normalizeBookWhitespace(): String {
    val output = StringBuilder(length)
    var index = 0
    var pendingSpace = false
    while (index < length) {
        val codePoint = codePointAt(index)
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
            pendingSpace = output.isNotEmpty()
        } else {
            if (pendingSpace) output.append(' ')
            output.appendCodePoint(codePoint)
            pendingSpace = false
        }
        index += Character.charCount(codePoint)
    }
    return output.toString()
}

private const val MAX_METADATA_CODE_POINTS = 200
