package com.mkread.app.speech

interface SpeechEngine : AutoCloseable {
    suspend fun initialize(): Result<Unit>

    suspend fun generate(request: SpeechRequest): Result<SpeechResult>

    override fun close()
}
