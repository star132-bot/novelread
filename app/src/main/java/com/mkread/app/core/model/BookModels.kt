package com.mkread.app.core.model

enum class SourceType {
    TXT,
    EPUB,
}

enum class LibrarySort {
    LAST_OPENED,
    TITLE,
    IMPORTED,
}

data class BookSummary(
    val id: String,
    val title: String,
    val author: String?,
    val sourceType: SourceType,
    val coverPath: String?,
    val chapterTitle: String?,
    val progressFraction: Float,
    val lastOpenedAt: Long?,
    val folderId: String? = null,
)
