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
    /** Recording to clone; required for [VoiceModel.clonesVoices] models and ignored otherwise. */
    val reference: VoiceReference?,
    val quality: SpeechQuality,
    val outputFile: File,
    val model: VoiceModel = VoiceModel.ZIPVOICE,
    val speakerId: Int = 0,
)

data class SpeechResult(
    val file: File,
    val sampleRate: Int,
    val sampleCount: Int,
    val generationMillis: Long,
)
