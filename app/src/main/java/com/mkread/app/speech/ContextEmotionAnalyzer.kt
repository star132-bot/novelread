package com.mkread.app.speech

import java.io.InputStream
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

class ContextEmotionAnalyzer private constructor(
    private val termsByEmotion: Map<Emotion, List<String>>,
) : EmotionAnalyzer {
    override fun analyze(context: EmotionContext): EmotionDecision {
        val scores = Emotion.entries.associateWithTo(linkedMapOf()) { 0 }
        CLASSIFIED_EMOTIONS.forEach { emotion ->
            val terms = termsByEmotion.getValue(emotion)
            val currentHits = countHits(context.current, terms)
            val lexicalScore = (
                currentHits * CURRENT_SENTENCE_WEIGHT +
                    countHits(context.previous, terms) * ADJACENT_SENTENCE_WEIGHT +
                    countHits(context.next, terms) * ADJACENT_SENTENCE_WEIGHT
                ).coerceAtMost(MAX_LEXICAL_SCORE)
            scores[emotion] = lexicalScore + punctuationBonus(
                emotion = emotion,
                currentText = context.current,
                currentHasLexiconHit = currentHits > 0,
            )
        }

        val ranked = CLASSIFIED_EMOTIONS.sortedByDescending(scores::getValue)
        val highest = scores.getValue(ranked[0])
        val second = scores.getValue(ranked[1])
        val winner = if (highest == 0 || highest == second || highest - second < MINIMUM_LEAD) {
            Emotion.NEUTRAL
        } else {
            ranked[0]
        }
        return EmotionDecision(
            emotion = winner,
            scores = scores.toMap(),
        )
    }

    private fun countHits(text: String, terms: List<String>): Int = terms.sumOf { term ->
        var count = 0
        var searchFrom = 0
        while (searchFrom < text.length) {
            val match = text.indexOf(term, startIndex = searchFrom, ignoreCase = false)
            if (match < 0) break
            if (!term.isAsciiWord() || text.hasAsciiTokenBoundaries(match, match + term.length)) {
                count += 1
            }
            searchFrom = match + term.length
        }
        count
    }

    private fun punctuationBonus(
        emotion: Emotion,
        currentText: String,
        currentHasLexiconHit: Boolean,
    ): Int {
        if (!currentHasLexiconHit) return 0
        val hasExclamation = currentText.any { it == '!' || it == '！' }
        val hasEllipsis = currentText.contains('…') || currentText.contains("...")
        return when (emotion) {
            Emotion.ANGER -> if (hasExclamation) 2 else 0
            Emotion.TENSION -> if (hasExclamation || hasEllipsis) 2 else 0
            Emotion.SADNESS -> if (hasEllipsis) 1 else 0
            Emotion.JOY -> if (hasExclamation) 1 else 0
            Emotion.NEUTRAL -> 0
        }
    }

    companion object {
        private const val CURRENT_SENTENCE_WEIGHT = 3
        private const val ADJACENT_SENTENCE_WEIGHT = 1
        private const val MAX_LEXICAL_SCORE = 9
        private const val MINIMUM_LEAD = 2
        private const val SUPPORTED_SCHEMA_VERSION = 1
        private val CLASSIFIED_EMOTIONS = listOf(
            Emotion.JOY,
            Emotion.SADNESS,
            Emotion.ANGER,
            Emotion.TENSION,
        )
        private val REQUIRED_ROOT_KEYS = setOf("schemaVersion", "emotions")
        private val REQUIRED_EMOTION_KEYS = CLASSIFIED_EMOTIONS.mapTo(linkedSetOf()) { it.wireName }

        fun fromInputStream(input: InputStream): ContextEmotionAnalyzer =
            input.bufferedReader(Charsets.UTF_8).use { fromJson(it.readText()) }

        fun fromJson(content: String): ContextEmotionAnalyzer {
            val root = Json.parseToJsonElement(content) as? JsonObject
                ?: throw IllegalArgumentException("Emotion lexicon root must be an object")
            require(root.keys == REQUIRED_ROOT_KEYS) { "Emotion lexicon root fields are invalid" }
            val schemaVersion = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
            require(schemaVersion == SUPPORTED_SCHEMA_VERSION) { "Unsupported emotion lexicon schema" }
            val emotions = root["emotions"] as? JsonObject
                ?: throw IllegalArgumentException("Emotion lexicon emotions must be an object")
            require(emotions.keys == REQUIRED_EMOTION_KEYS) { "Emotion lexicon groups are invalid" }

            val allTerms = hashSetOf<String>()
            val parsed = CLASSIFIED_EMOTIONS.associateWith { emotion ->
                val values = emotions[emotion.wireName] as? JsonArray
                    ?: throw IllegalArgumentException("Emotion lexicon group must be an array: ${emotion.wireName}")
                require(values.isNotEmpty()) { "Emotion lexicon group is empty: ${emotion.wireName}" }
                values.map { element ->
                    val primitive = element as? JsonPrimitive
                    require(primitive != null && primitive.isString) {
                        "Emotion lexicon term must be a string: ${emotion.wireName}"
                    }
                    primitive.content.also { term ->
                        require(term.isNotBlank()) { "Emotion lexicon term must not be blank" }
                        require(term.codePointCount(0, term.length) <= MAX_TERM_CODE_POINTS) {
                            "Emotion lexicon term is too long"
                        }
                        require(allTerms.add(term.lowercase(Locale.ROOT))) {
                            "Emotion lexicon term is duplicated"
                        }
                    }
                }.sortedByDescending(String::length)
            }
            return ContextEmotionAnalyzer(parsed)
        }

        private const val MAX_TERM_CODE_POINTS = 32
    }
}

private fun String.isAsciiWord(): Boolean = isNotEmpty() && all { it.isLetterOrDigit() && it.code < 128 }

private fun String.hasAsciiTokenBoundaries(start: Int, end: Int): Boolean {
    val beforeIsWord = start > 0 && this[start - 1].isLetterOrDigit()
    val afterIsWord = end < length && this[end].isLetterOrDigit()
    return !beforeIsWord && !afterIsWord
}
