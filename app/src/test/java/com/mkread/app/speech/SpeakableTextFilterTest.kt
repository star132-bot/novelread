package com.mkread.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakableTextFilterTest {
    @Test
    fun filter_removesQuotesAndBracketsButKeepsTheirContent() {
        assertEquals(
            "老人说道：你终于来了。",
            SpeakableTextFilter.filter("老人说道：“你终于来了。”"),
        )
        assertEquals("注，此处为第一段", SpeakableTextFilter.filter("【注，此处为第一段】"))
        assertEquals("他读完了三体", SpeakableTextFilter.filter("他读完了《三体》"))
    }

    @Test
    fun filter_turnsSeparatorsIntoSinglePauses() {
        assertEquals("他笑了笑，没有回答。", SpeakableTextFilter.filter("他笑了笑——没有回答。"))
        assertEquals("好啊，走吧", SpeakableTextFilter.filter("好啊~~走吧"))
        assertEquals("他笑了笑。没有回答。", SpeakableTextFilter.filter("他笑了笑……没有回答。"))
    }

    @Test
    fun filter_dropsDecorativeSymbolsAndEmoji() {
        assertEquals("好消息", SpeakableTextFilter.filter("★☆好消息♪😀"))
        assertEquals("", SpeakableTextFilter.filter("※※※"))
        assertEquals("", SpeakableTextFilter.filter("＊＊＊"))
    }

    @Test
    fun filter_keepsEnglishWordsAndSpacing() {
        assertEquals("Hello world, it's fine.", SpeakableTextFilter.filter("Hello  world, it's fine."))
        assertEquals("e mail 和 Wi Fi", SpeakableTextFilter.filter("e-mail & Wi-Fi"))
    }

    @Test
    fun filter_doesNotStartWithPunctuationOrRepeatIt() {
        assertEquals("然后呢？", SpeakableTextFilter.filter("……然后呢？！？"))
    }

    @Test
    fun hasSpeech_isFalseForPunctuationOnly() {
        assertFalse(SpeakableTextFilter.hasSpeech("。，！"))
        assertTrue(SpeakableTextFilter.hasSpeech("嗯。"))
    }
}
