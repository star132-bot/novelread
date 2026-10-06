package com.mkread.app.speech

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

enum class PronunciationOverrideMatch {
    LITERAL,
    WHOLE_TOKEN,
}

data class PronunciationOverride(
    val source: String,
    val replacement: String,
    val match: PronunciationOverrideMatch,
)

class PronunciationOverrides private constructor(
    private val entries: List<PronunciationOverride>,
) {
    internal fun applyTo(input: String): String {
        if (entries.isEmpty()) return input

        val result = StringBuilder(input.length)
        var index = 0
        while (index < input.length) {
            val match = entries.firstOrNull { entry -> entry.matches(input, index) }
            if (match == null) {
                val codePoint = input.codePointAt(index)
                result.appendCodePoint(codePoint)
                index += Character.charCount(codePoint)
            } else {
                result.append(match.replacement)
                index += match.source.length
            }
        }
        return result.toString()
    }

    companion object {
        const val ASSET_PATH = "speech/pronunciation-zh-en.json"
        const val SCHEMA_VERSION = 1

        private const val MAX_JSON_BYTES = 64 * 1024
        private const val MAX_OVERRIDE_COUNT = 128
        private const val MAX_SOURCE_CODE_POINTS = 64
        private const val MAX_REPLACEMENT_CODE_POINTS = 128

        fun load(input: InputStream): PronunciationOverrides {
            val bytes = input.readBounded(MAX_JSON_BYTES)
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val json = try {
                decoder.decode(ByteBuffer.wrap(bytes)).toString()
            } catch (failure: Exception) {
                throw IllegalArgumentException("Pronunciation override JSON is not valid UTF-8", failure)
            }
            return parse(json)
        }

        fun parse(json: String): PronunciationOverrides {
            require(json.toByteArray(StandardCharsets.UTF_8).size <= MAX_JSON_BYTES) {
                "Pronunciation override JSON exceeds the size limit"
            }
            val root = try {
                Json.parseToJsonElement(json) as? JsonObject
            } catch (failure: Exception) {
                throw IllegalArgumentException("Pronunciation override JSON is invalid", failure)
            } ?: throw IllegalArgumentException("Pronunciation override JSON root must be an object")

            root.requireOnlyKeys(setOf("schemaVersion", "overrides"), "root")
            val schemaVersion = root.requiredInt("schemaVersion", "root")
            require(schemaVersion == SCHEMA_VERSION) {
                "Unsupported pronunciation override schema version"
            }
            val records = root["overrides"] as? JsonArray
                ?: throw IllegalArgumentException("Pronunciation override JSON must contain an overrides array")
            require(records.size <= MAX_OVERRIDE_COUNT) {
                "Pronunciation override count exceeds the limit"
            }

            val parsed = records.mapIndexed { index, element ->
                val record = element as? JsonObject
                    ?: throw IllegalArgumentException("Pronunciation override entry $index must be an object")
                record.requireOnlyKeys(setOf("source", "replacement", "match"), "entry $index")
                parseEntry(
                    source = record.requiredString("source", "entry $index"),
                    replacement = record.requiredString("replacement", "entry $index"),
                    matchName = record.requiredString("match", "entry $index"),
                    index = index,
                )
            }
            require(parsed.map { it.source }.toSet().size == parsed.size) {
                "Pronunciation override sources must be unique after normalization"
            }
            return PronunciationOverrides(parsed.sortedLongestFirst())
        }

        fun of(entries: Iterable<PronunciationOverride>): PronunciationOverrides {
            val validated = entries.mapIndexed { index, entry ->
                parseEntry(entry.source, entry.replacement, entry.match.jsonName, index)
            }
            require(validated.size <= MAX_OVERRIDE_COUNT) {
                "Pronunciation override count exceeds the limit"
            }
            require(validated.map { it.source }.toSet().size == validated.size) {
                "Pronunciation override sources must be unique after normalization"
            }
            return PronunciationOverrides(validated.sortedLongestFirst())
        }

        fun empty(): PronunciationOverrides = PronunciationOverrides(emptyList())

        private fun parseEntry(
            source: String,
            replacement: String,
            matchName: String,
            index: Int,
        ): PronunciationOverride {
            val normalizedSource = source.normalizeNfkcPreservingNarrationPunctuation()
            val normalizedReplacement = replacement.normalizeNfkcPreservingNarrationPunctuation()
                .collapseWhitespace()
            require(normalizedSource.codePointLength() in 1..MAX_SOURCE_CODE_POINTS) {
                "Pronunciation override entry $index has an invalid source length"
            }
            require(!normalizedSource.hasWhitespace()) {
                "Pronunciation override source must not contain whitespace"
            }
            require(!normalizedSource.hasControlCharacter()) {
                "Pronunciation override source must not contain control characters"
            }
            require(normalizedReplacement.codePointLength() in 1..MAX_REPLACEMENT_CODE_POINTS) {
                "Pronunciation override entry $index has an invalid replacement length"
            }
            require(!normalizedReplacement.hasControlCharacter()) {
                "Pronunciation override replacement must not contain control characters"
            }
            val match = when (matchName) {
                "literal" -> PronunciationOverrideMatch.LITERAL
                "whole-token" -> PronunciationOverrideMatch.WHOLE_TOKEN
                else -> throw IllegalArgumentException("Pronunciation override entry $index has an invalid match mode")
            }
            if (match == PronunciationOverrideMatch.WHOLE_TOKEN) {
                require(normalizedSource.startsAndEndsWithLetterOrDigit()) {
                    "Whole-token pronunciation override must start and end with a letter or digit"
                }
            }
            return PronunciationOverride(normalizedSource, normalizedReplacement, match)
        }
    }
}

private val PronunciationOverrideMatch.jsonName: String
    get() = when (this) {
        PronunciationOverrideMatch.LITERAL -> "literal"
        PronunciationOverrideMatch.WHOLE_TOKEN -> "whole-token"
    }

private fun PronunciationOverride.matches(input: String, start: Int): Boolean {
    if (!input.startsWith(source, start)) return false
    if (match == PronunciationOverrideMatch.LITERAL) return true

    val end = start + source.length
    val hasLetterOrDigitBefore = start > 0 && Character.isLetterOrDigit(input.codePointBefore(start))
    val hasLetterOrDigitAfter = end < input.length && Character.isLetterOrDigit(input.codePointAt(end))
    return !hasLetterOrDigitBefore && !hasLetterOrDigitAfter
}

private fun List<PronunciationOverride>.sortedLongestFirst(): List<PronunciationOverride> =
    sortedWith(
        compareByDescending<PronunciationOverride> { it.source.codePointLength() }
            .thenByDescending { it.source.length }
            .thenBy { it.source },
    )

private fun JsonObject.requireOnlyKeys(expected: Set<String>, location: String) {
    require(keys == expected) { "Pronunciation override JSON $location has missing or unknown fields" }
}

private fun JsonObject.requiredString(name: String, location: String): String {
    val value = this[name] as? JsonPrimitive
    require(value != null && value.isString) {
        "Pronunciation override JSON $location field $name must be a string"
    }
    return value.content
}

private fun JsonObject.requiredInt(name: String, location: String): Int {
    val value = this[name] as? JsonPrimitive
    require(value != null && !value.isString && value.intOrNull != null) {
        "Pronunciation override JSON $location field $name must be an integer"
    }
    return requireNotNull(value.intOrNull)
}

private fun InputStream.readBounded(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4 * 1024)
    var byteCount = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (read == 0) continue
        require(byteCount <= limit - read) { "Pronunciation override JSON exceeds the size limit" }
        output.write(buffer, 0, read)
        byteCount += read
    }
    return output.toByteArray()
}

private fun String.codePointLength(): Int = codePointCount(0, length)

private fun String.hasWhitespace(): Boolean = anyCodePoint { codePoint ->
    Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
}

private fun String.hasControlCharacter(): Boolean = anyCodePoint(Character::isISOControl)

private fun String.startsAndEndsWithLetterOrDigit(): Boolean =
    Character.isLetterOrDigit(codePointAt(0)) && Character.isLetterOrDigit(codePointBefore(length))

private inline fun String.anyCodePoint(predicate: (Int) -> Boolean): Boolean {
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        if (predicate(codePoint)) return true
        index += Character.charCount(codePoint)
    }
    return false
}
