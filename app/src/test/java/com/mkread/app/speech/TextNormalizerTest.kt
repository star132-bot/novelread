package com.mkread.app.speech

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {
    private val normalizer: TextNormalizer
        get() = TextNormalizer(loadReviewedOverrides())

    @Test
    fun normalize_appliesNfkcToFullWidthLatinLettersAndDigits() {
        val normalized = normalizer.normalize("ＭＫｒｅａｄ Ｖｅｒｓｉｏｎ １２３")

        assertEquals("MKread Version 123", normalized.value)
    }

    @Test
    fun normalize_collapsesHorizontalAndVerticalWhitespaceToOneSpace() {
        val normalized = normalizer.normalize("  甲\t\t乙\r\n\n　丙\u2028丁  ")

        assertEquals("甲 乙 丙 丁", normalized.value)
    }

    @Test
    fun normalize_preservesCurlyQuotesAndChinesePunctuation() {
        val normalized = normalizer.normalize("“你好”，‘AI’。！？；：、")

        assertEquals("“你好”，‘A I’。！？；：、", normalized.value)
    }

    @Test
    fun normalize_canonicalizesRepeatedDotsAndChineseEllipsis() {
        val normalized = normalizer.normalize("等等... 再说……… 好……")

        assertEquals("等等…… 再说…… 好……", normalized.value)
    }

    @Test
    fun normalize_preservesDecimalNumbersAndTimes() {
        val normalized = normalizer.normalize("价格 3.14，时间 08:30，第 2026 版")

        assertEquals("价格 3.14，时间 08:30，第 2026 版", normalized.value)
    }

    @Test
    fun normalize_pronouncesUrlSchemeAndKeepsUrlNumbersAndDots() {
        val normalized = normalizer.normalize("访问 https://example.com/a.b?x=1.2")

        assertEquals("访问 H T T P S，example.com/a.b?x=1.2", normalized.value)
    }

    @Test
    fun normalize_appliesReviewedWholeTokenOverrides() {
        val normalized = normalizer.normalize("AI CPU GPU Wi-Fi")

        assertEquals("A I C P U G P U Wi Fi", normalized.value)
    }

    @Test
    fun normalize_handlesMixedChineseEnglishWithoutTranslationOrNumberConversion() {
        val normalized = normalizer.normalize("模型 ｖ２ 在2026年支持 AI。")

        assertEquals("模型 v2 在2026年支持 A I。", normalized.value)
    }

    @Test
    fun normalize_doesNotReplaceInsideUnicodeLetterOrDigitBoundaries() {
        val normalized = normalizer.normalize("OpenAI AI助手 βAI AI2 _AI_")

        assertEquals("OpenAI AI助手 βAI AI2 _A I_", normalized.value)
    }

    @Test
    fun overrides_rejectSourceThatSpansAWordBoundary() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            PronunciationOverrides.parse(
                """
                {
                  "schemaVersion": 1,
                  "overrides": [
                    {"source": "AI CPU", "replacement": "unsafe", "match": "whole-token"}
                  ]
                }
                """.trimIndent(),
            )
        }

        assertTrue(failure.message.orEmpty().contains("whitespace"))
        assertFalse(failure.message.orEmpty().contains("AI CPU"))
    }

    @Test
    fun normalize_rejectsInputThatBecomesBlankWithoutEchoingIt() {
        val raw = " \t\r\n　"

        val failure = assertThrows(IllegalArgumentException::class.java) {
            normalizer.normalize(raw)
        }

        assertFalse(failure.message.orEmpty().contains(raw))
    }

    @Test
    fun normalize_returnsStableHashAndVersionAcrossRuns() {
        val first = normalizer.normalize("ＡＩ  CPU... 版本２")
        val second = normalizer.normalize("ＡＩ  CPU... 版本２")

        assertEquals("A I C P U…… 版本2", first.value)
        assertEquals("7e41f8add549cede58afcb8490bf9362adb029eca87f06e621f4f8c7fa9a4f84", first.sha256)
        assertEquals(1, first.normalizerVersion)
        assertEquals(first, second)
    }

    private fun loadReviewedOverrides(): PronunciationOverrides =
        File("src/main/assets/${PronunciationOverrides.ASSET_PATH}")
            .inputStream()
            .buffered()
            .use(PronunciationOverrides::load)
}
