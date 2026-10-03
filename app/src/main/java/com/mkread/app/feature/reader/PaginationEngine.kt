package com.mkread.app.feature.reader

import kotlinx.coroutines.flow.Flow

data class PaginationBatch(
    val ranges: List<PageRange>,
    val complete: Boolean,
)

interface PaginationEngine {
    fun paginate(text: String, spec: PaginationSpec): Flow<PaginationBatch>

    fun paginate(
        key: PaginationKey,
        text: String,
        spec: PaginationSpec,
    ): Flow<PaginationBatch> = paginate(text, spec)
}
