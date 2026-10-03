package com.mkread.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextEmotionAnalyzerTest {
    private val analyzer = ContextEmotionAnalyzer.fromJson(TEST_LEXICON)

    @Test
    fun classifiesEveryStrongEmotion() {
        assertEquals(Emotion.JOY, analyze("她开心地笑着说：太好了！").emotion)
        assertEquals(Emotion.SADNESS, analyze("眼泪落下，她感到悲伤和绝望……").emotion)
        assertEquals(Emotion.ANGER, analyze("他愤怒地咬牙怒吼：可恶！").emotion)
        assertEquals(Emotion.TENSION, analyze("危险突然逼近，他猛地屏住呼吸！").emotion)
    }

    @Test
    fun neutralProseEnglishAndPunctuationAloneStayNeutral() {
        assertEquals(Emotion.NEUTRAL, analyze("天色渐晚，他把书放回桌面。").emotion)
        assertEquals(Emotion.NEUTRAL, analyze("The train arrived at platform three.").emotion)
        assertEquals(Emotion.NEUTRAL, analyze("！！！……").emotion)
    }

    @Test
    fun tiesAndNarrowLeadsStayNeutral() {
        assertEquals(Emotion.NEUTRAL, analyze("开心，却也难过。").emotion)

        val narrowLead = analyzer.analyze(
            EmotionContext(
                previous = "她感到开心和幸福。",
                current = "他很难过。",
                next = "",
            ),
        )
        assertEquals(Emotion.NEUTRAL, narrowLead.emotion)
        assertEquals(3, narrowLead.scores.getValue(Emotion.SADNESS))
        assertEquals(2, narrowLead.scores.getValue(Emotion.JOY))
    }

    @Test
    fun adjacentContextCanStrengthenAWeakCurrentDecision() {
        val decision = analyzer.analyze(
            EmotionContext(
                previous = "危险正在靠近。",
                current = "他突然回头。",
                next = "身后有人追赶。",
            ),
        )

        assertEquals(Emotion.TENSION, decision.emotion)
        assertEquals(5, decision.scores.getValue(Emotion.TENSION))
    }

    @Test
    fun punctuationBonusesRequireMatchingLexicon() {
        val anger = analyze("可恶！")
        val tension = analyze("危险……")
        val sadness = analyze("悲伤……")
        val joy = analyze("太好了！")

        assertEquals(5, anger.scores.getValue(Emotion.ANGER))
        assertEquals(5, tension.scores.getValue(Emotion.TENSION))
        assertEquals(4, sadness.scores.getValue(Emotion.SADNESS))
        assertEquals(4, joy.scores.getValue(Emotion.JOY))
    }

    @Test
    fun repeatedTermsAreCappedBeforePunctuationBonus() {
        val decision = analyze("愤怒，愤怒，愤怒，愤怒！")

        assertEquals(11, decision.scores.getValue(Emotion.ANGER))
        assertEquals(Emotion.ANGER, decision.emotion)
    }

    @Test
    fun asciiTokenMatchesDoNotLeakAcrossWordBoundaries() {
        val custom = ContextEmotionAnalyzer.fromJson(
            TEST_LEXICON.replace("\"开心\"", "\"开心\", \"joy\""),
        )

        assertEquals(Emotion.JOY, custom.analyze(EmotionContext(current = "joy!")).emotion)
        assertEquals(Emotion.NEUTRAL, custom.analyze(EmotionContext(current = "joyful!")).emotion)
    }

    @Test
    fun decisionsAreDeterministicAndExposeAllScores() {
        val context = EmotionContext(
            previous = "她流下眼泪。",
            current = "她感到绝望……",
            next = "失去的已经无法回来。",
        )

        val first = analyzer.analyze(context)
        repeat(20) { assertEquals(first, analyzer.analyze(context)) }
        assertEquals(1, first.ruleVersion)
        assertEquals(Emotion.entries.toSet(), first.scores.keys)
        assertTrue(first.scores.values.all { it >= 0 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownLexiconFields() {
        ContextEmotionAnalyzer.fromJson(TEST_LEXICON.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"extra\": true"))
    }

    private fun analyze(current: String): EmotionDecision =
        analyzer.analyze(EmotionContext(current = current))

    private companion object {
        val TEST_LEXICON = """
            {
              "schemaVersion": 1,
              "emotions": {
                "joy": ["开心", "喜悦", "幸福", "太好了", "笑着", "欣喜"],
                "sadness": ["悲伤", "难过", "绝望", "眼泪", "哭泣", "失去"],
                "anger": ["愤怒", "怒吼", "可恶", "混蛋", "咬牙", "暴怒"],
                "tension": ["突然", "猛地", "危险", "逃跑", "追赶", "屏住呼吸", "颤抖"]
              }
            }
        """.trimIndent()
    }
}
