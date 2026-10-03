package com.mkread.app.feature.library

import java.io.InputStream

data class ImportRequest(
    val displayName: String,
    val mimeType: String?,
    val openStream: () -> InputStream,
    val folderId: String? = null,
)

sealed interface ImportResult {
    data class Success(val bookId: String) : ImportResult

    /** A newer revision of an MKBook already on the shelf replaced the old copy. */
    data class Updated(val bookId: String, val replacedBookId: String) : ImportResult

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
    MALFORMED_MKBOOK,
    UNSUPPORTED_VERSION,
    NO_READABLE_CONTENT,
    STORAGE_FULL,
    SOURCE_UNAVAILABLE,
    INTERNAL_COMMIT,
}
