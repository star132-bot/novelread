package com.mkread.app.speech

enum class Emotion(
    val wireName: String,
) {
    NEUTRAL("neutral"),
    JOY("joy"),
    SADNESS("sadness"),
    ANGER("anger"),
    TENSION("tension"),
    ;

    companion object {
        fun fromWireName(value: String): Emotion? = entries.firstOrNull { it.wireName == value }
    }
}

data class EmotionContext(
    val previous: String = "",
    val current: String,
    val next: String = "",
)

data class EmotionDecision(
    val emotion: Emotion,
    val scores: Map<Emotion, Int>,
    val ruleVersion: Int = 1,
)
