package com.mkread.app.speech

data class NarrationSentence(
    val bookId: String,
    val bookContentSha256: String,
    val chapterId: String,
    val index: Int,
    val start: Int,
    val end: Int,
    val rawText: String,
    val bookTitle: String,
    val chapterTitle: String,
    val queueGenerationId: Long,
) {
    init {
        require(bookId.isNotBlank() && chapterId.isNotBlank()) { "Narration ids must not be blank" }
        require(bookContentSha256.isNotBlank()) { "Book content hash must not be blank" }
        require(index >= 0 && start >= 0 && end > start) { "Narration sentence range is invalid" }
        require(rawText.isNotBlank()) { "Narration sentence text must not be blank" }
        require(queueGenerationId >= 0L) { "Queue generation id must be non-negative" }
    }
}

data class NarrationSettings(
    val voiceId: String?,
    val styleId: String,
    val quality: SpeechQuality,
)
