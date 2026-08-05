package com.mkread.app.speech

import java.io.File

sealed interface GenerationState {
    data class Ready(
        val sentence: NarrationSentence,
        val file: File,
        val cacheKey: String,
        val fromCache: Boolean,
    ) : GenerationState

    data class Blocked(
        val sentence: NarrationSentence,
        val reason: String,
        val retryable: Boolean,
    ) : GenerationState

    data class StorageLow(
        val sentence: NarrationSentence,
        val reason: String,
    ) : GenerationState
}
