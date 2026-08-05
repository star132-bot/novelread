package com.mkread.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCacheKeyTest {
    @Test
    fun calculate_isDeterministicAndUsesTheSpecifiedFieldOrder() {
        val input = baseInput()

        val first = AudioCacheKey.calculate(input)
        val second = AudioCacheKey.calculate(input)

        assertEquals(first, second)
        assertEquals(
            "2c7cf59ebc73e60140bd951a89a828ff24423c65a064dc668dfbbb9bf4aeab71",
            first,
        )
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun calculate_changesWhenAnyInputFieldChanges() {
        val input = baseInput()
        val original = AudioCacheKey.calculate(input)
        val variants = listOf(
            input.copy(bookContentSha256 = "other-book-hash"),
            input.copy(chapterId = "other-chapter"),
            input.copy(sentenceStart = input.sentenceStart + 1),
            input.copy(sentenceEnd = input.sentenceEnd + 1),
            input.copy(normalizedTextSha256 = "other-text-hash"),
            input.copy(voicePackageSha256 = "other-voice-hash"),
            input.copy(styleId = "other-style"),
            input.copy(qualityId = "other-quality"),
            input.copy(generationConfigurationVersion = 2),
        ).map(AudioCacheKey::calculate)

        variants.forEach { variant -> assertNotEquals(original, variant) }
        assertEquals(variants.size, variants.toSet().size)
    }

    @Test
    fun lengthPrefixesPreventDelimiterCollisions() {
        val left = baseInput().copy(
            bookContentSha256 = "book|chapter",
            chapterId = "one",
        )
        val right = baseInput().copy(
            bookContentSha256 = "book",
            chapterId = "chapter|one",
        )

        assertEquals(
            listOf(left.bookContentSha256, left.chapterId).joinToString("|"),
            listOf(right.bookContentSha256, right.chapterId).joinToString("|"),
        )
        assertNotEquals(AudioCacheKey.calculate(left), AudioCacheKey.calculate(right))
    }

    @Test
    fun playbackSpeedCannotBeSuppliedToTheKeyFunction() {
        val inputFields = AudioCacheKeyInput::class.java.declaredFields.map { it.name }
        val publicCalculateMethods = AudioCacheKey::class.java.methods
            .filter { it.name == "calculate" }

        assertFalse(inputFields.any { it.contains("speed", ignoreCase = true) })
        assertEquals(1, publicCalculateMethods.size)
        assertEquals(
            listOf(AudioCacheKeyInput::class.java),
            publicCalculateMethods.single().parameterTypes.toList(),
        )
    }

    private fun baseInput() = AudioCacheKeyInput(
        bookContentSha256 = "book-hash",
        chapterId = "chapter:1|part",
        sentenceStart = 12,
        sentenceEnd = 34,
        normalizedTextSha256 = "text-hash",
        voicePackageSha256 = "voice-hash",
        styleId = "neutral|warm",
        qualityId = "fluent:high",
    )
}
