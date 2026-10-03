package com.mkread.app.core.files

enum class StorageFailure {
    SOURCE_TOO_LARGE,
    CHAPTER_TOO_LARGE,
    COVER_TOO_LARGE,
    UNSUPPORTED_COVER,
    INVALID_PATH,
    TRANSACTION_EXISTS,
    BOOK_EXISTS,
    PROMOTION_FAILED,
    IO_ERROR,
}

class StorageException(
    val failure: StorageFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
