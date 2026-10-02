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

        assertEquals("MKread Version 一百二十三", normalized.value)
    }

    @Test
    fun normalize_collapsesHorizontalAndVerticalWhitespaceToOneSpace() {
        val normalized = normalizer.normalize("  甲\t\t乙\r\n\n　丙\u2028丁  ")

        assertEquals("甲乙丙丁", normalized.value)
    }

    @Test
    fun normalize_dropsQuotesAndCollapsesChinesePunctuation() {
        val normalized = normalizer.normalize("“你好”，‘AI’。！？；：、")

        assertEquals("你好，A I。", normalized.value)
    }

    @Test
    fun normalize_turnsEllipsesIntoSentencePauses() {
        val normalized = normalizer.normalize("等等... 再说……… 好……")

        assertEquals("等等。再说。好。", normalized.value)
    }

    @Test
    fun normalize_readsDecimalNumbersAndTimesInChinese() {
        val normalized = normalizer.normalize("价格 3.14，时间 08:30，第 2026 版")

        assertEquals("价格三点一四，时间八点三十分，第二千零二十六版", normalized.value)
    }

    @Test
    fun normalize_pronouncesUrlSchemeAndDropsUrlSymbols() {
        val normalized = normalizer.normalize("访问 https://example.com/a.b?x=1.2")

        assertEquals("访问 H T T P S，example.com，a.b?x等于一点二", normalized.value)
    }

    @Test
    fun normalize_appliesReviewedWholeTokenOverrides() {
        val normalized = normalizer.normalize("AI CPU GPU Wi-Fi")

        assertEquals("A I C P U G P U Wi Fi", normalized.value)
    }

    @Test
    fun normalize_handlesMixedChineseEnglishAndReadsNumbersInChinese() {
        val normalized = normalizer.normalize("模型 ｖ２ 在2026年支持 AI。")

        assertEquals("模型 v二在二零二六年支持 A I。", normalized.value)
    }

    @Test
    fun normalize_doesNotReplaceInsideUnicodeLetterOrDigitBoundaries() {
        val normalized = normalizer.normalize("OpenAI AI助手 βAI AI2 _AI_")

        assertEquals("OpenAI AI助手 AI AI二，A I", normalized.value)
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

        assertEquals("A I C P U。版本二", first.value)
        assertEquals(2, first.normalizerVersion)
        assertEquals(first, second)
    }

    @Test
    fun normalize_readsNovelSentenceWithSymbolsNumbersAndUnits() {
        val normalized = normalizer.normalize("【注：此处为第1段】~~ 他笑了笑……没有回答。2024年，他已经87岁了，体重65kg，手机号是13800138000。")

        assertEquals(
            "注：此处为第一段，他笑了笑。没有回答。二零二四年，他已经八十七岁了，体重六十五公斤，手机号是幺三八零零幺三八零零零。",
            normalized.value,
        )
    }

    @Test
    fun normalizeOrNull_returnsNullForSceneBreaks() {
        listOf("※※※", "＊＊＊", "——————", "……", "~~~").forEach { raw ->
            assertEquals(raw, null, normalizer.normalizeOrNull(raw))
        }
    }

    private fun loadReviewedOverrides(): PronunciationOverrides =
        File("src/main/assets/${PronunciationOverrides.ASSET_PATH}")
            .inputStream()
            .buffered()
            .use(PronunciationOverrides::load)
}
