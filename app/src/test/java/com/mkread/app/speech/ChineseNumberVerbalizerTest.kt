package com.mkread.app.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class ChineseNumberVerbalizerTest {
    @Test
    fun cardinal_readsIntegersWithZerosAndLargeUnits() {
        val cases = mapOf(
            "0" to "零",
            "7" to "七",
            "10" to "十",
            "15" to "十五",
            "20" to "二十",
            "35" to "三十五",
            "101" to "一百零一",
            "110" to "一百一十",
            "1001" to "一千零一",
            "1024" to "一千零二十四",
            "10000" to "一万",
            "10005" to "一万零五",
            "105000" to "十万五千",
            "1000000" to "一百万",
            "100000001" to "一亿零一",
            "120030000" to "一亿二千零三万",
        )
        cases.forEach { (digits, spoken) -> assertEquals(digits, spoken, ChineseNumberVerbalizer.cardinal(digits)) }
    }

    @Test
    fun verbalize_readsYearsDigitByDigitButDurationsAsCardinals() {
        assertEquals("二零二四年，他已经八十七岁了", ChineseNumberVerbalizer.verbalize("2024年，他已经87岁了"))
        assertEquals("我等了你整整三十五年", ChineseNumberVerbalizer.verbalize("我等了你整整35年"))
    }

    @Test
    fun verbalize_readsDatesAndTimes() {
        assertEquals("二零二六年十月二日", ChineseNumberVerbalizer.verbalize("2026-10-02"))
        assertEquals("二零二六年三月", ChineseNumberVerbalizer.verbalize("2026年3月"))
        assertEquals("八点三十分", ChineseNumberVerbalizer.verbalize("08:30"))
        assertEquals("十点零五分", ChineseNumberVerbalizer.verbalize("10:05"))
        assertEquals("二十三点", ChineseNumberVerbalizer.verbalize("23:00"))
    }

    @Test
    fun verbalize_readsDecimalsPercentagesAndFractions() {
        assertEquals("价格三点一四", ChineseNumberVerbalizer.verbalize("价格3.14"))
        assertEquals("百分之六十五", ChineseNumberVerbalizer.verbalize("65%"))
        assertEquals("负百分之二点五", ChineseNumberVerbalizer.verbalize("-2.5%"))
        assertEquals("四分之三", ChineseNumberVerbalizer.verbalize("3/4"))
    }

    @Test
    fun verbalize_readsUnitsAndCurrency() {
        assertEquals("体重六十五公斤", ChineseNumberVerbalizer.verbalize("体重65kg"))
        assertEquals("跑了十公里", ChineseNumberVerbalizer.verbalize("跑了10 km"))
        assertEquals("三十七点五摄氏度", ChineseNumberVerbalizer.verbalize("37.5℃"))
        assertEquals("九十九元", ChineseNumberVerbalizer.verbalize("¥99"))
        assertEquals("一千二百五十美元", ChineseNumberVerbalizer.verbalize("$1,250"))
    }

    @Test
    fun verbalize_readsLongDigitRunsOneDigitAtATime() {
        assertEquals("手机号是幺三八零零幺三八零零零", ChineseNumberVerbalizer.verbalize("手机号是13800138000"))
        assertEquals("编号零零七", ChineseNumberVerbalizer.verbalize("编号007"))
        assertEquals("验证码八八四二九一三", ChineseNumberVerbalizer.verbalize("验证码8842913"))
    }

    @Test
    fun verbalize_usesLiangOnlyForDigitTwoBeforeMeasureWords() {
        assertEquals("两个人走了两天", ChineseNumberVerbalizer.verbalize("2个人走了2天"))
        assertEquals("第二天", ChineseNumberVerbalizer.verbalize("第2天"))
        assertEquals("二次元", ChineseNumberVerbalizer.verbalize("二次元"))
        assertEquals("十二个", ChineseNumberVerbalizer.verbalize("12个"))
    }

    @Test
    fun verbalize_readsRanges() {
        assertEquals("三到五个", ChineseNumberVerbalizer.verbalize("3-5个"))
    }

    @Test
    fun verbalize_leavesTextWithoutDigitsUntouched() {
        val text = "夜色渐深，林默推开那扇木门。"
        assertEquals(text, ChineseNumberVerbalizer.verbalize(text))
    }
}
