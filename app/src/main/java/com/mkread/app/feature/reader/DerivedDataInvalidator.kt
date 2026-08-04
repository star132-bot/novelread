package com.mkread.app.feature.reader

fun interface DerivedDataInvalidator {
    suspend fun invalidateChapter(chapterId: String, oldHash: String, newHash: String)
}

class PaginationDerivedDataInvalidator(
    private val paginationCache: PaginationCache,
) : DerivedDataInvalidator {
    override suspend fun invalidateChapter(chapterId: String, oldHash: String, newHash: String) {
        paginationCache.invalidateChapter(chapterId)
    }
}
