package com.mkread.app.speech

fun interface EmotionAnalyzer {
    fun analyze(context: EmotionContext): EmotionDecision
}
