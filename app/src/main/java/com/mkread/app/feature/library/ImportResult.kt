package com.mkread.app.feature.library

import java.io.InputStream

data class ImportRequest(
    val displayName: String,
    val mimeType: String?,
    val openStream: () -> InputStream,
)

sealed interface ImportResult {
    data class Success(val bookId: String) : ImportResult

    data class Duplicate(val existingBookId: String) : ImportResult

    data class Failure(
        val code: ImportFailureCode,
        val userMessage: String,
    ) : ImportResult
}

enum class ImportFailureCode {
    SIZE_LIMIT,
    UNSUPPORTED_TYPE,
    ENCODING,
    MALFORMED_EPUB,
    NO_READABLE_CONTENT,
    STORAGE_FULL,
    SOURCE_UNAVAILABLE,
    INTERNAL_COMMIT,
}
