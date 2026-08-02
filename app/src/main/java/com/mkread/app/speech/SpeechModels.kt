package com.mkread.app.speech

import java.io.File

data class VoiceReference(
    val audioFile: File,
    val transcript: String,
    val sampleRate: Int = 24_000,
)

enum class SpeechQuality(val numSteps: Int) { FLUENT(4), HIGH(8) }

data class SpeechRequest(
    val text: String,
    val reference: VoiceReference,
    val quality: SpeechQuality,
    val outputFile: File,
)

data class SpeechResult(
    val file: File,
    val sampleRate: Int,
    val sampleCount: Int,
    val generationMillis: Long,
)
