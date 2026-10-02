package com.mkread.app.speech

/**
 * Rewrites Arabic numerals as spoken Mandarin so that every narration engine reads
 * dates, times, percentages, units and long digit strings the same way.
 */
object ChineseNumberVerbalizer {
    fun verbalize(input: String): String {
        if (input.none { it in '0'..'9' }) return input
        var text = input
        text = DATE.replace(text) { match ->
            val (year, month, day) = match.destructured
            "${digitsOf(year)}年${cardinal(month)}月${cardinal(day)}日"
        }
        text = YEAR_MONTH.replace(text) { match ->
            val (year, month) = match.destructured
            "${digitsOf(year)}年${cardinal(month)}月"
        }
        text = TIME.replace(text) { match ->
            val (hour, minute, second) = match.destructured
            buildString {
                append(cardinal(hour)).append('点')
                if (minute.toInt() != 0) append(minuteOf(minute)).append('分')
                if (second.isNotEmpty() && second.toInt() != 0) append(cardinal(second)).append('秒')
            }
        }
        text = CURRENCY_PREFIX.replace(text) { match ->
            val (symbol, amount) = match.destructured
            number(amount) + CURRENCY_NAMES.getValue(symbol)
        }
        text = PERCENT.replace(text) { match ->
            val (sign, amount, percent) = match.destructured
            val prefix = if (percent == "‰") "千分之" else "百分之"
            signOf(sign) + prefix + number(amount)
        }
        text = FRACTION.replace(text) { match ->
            val (numerator, denominator) = match.destructured
            "${cardinal(denominator)}分之${cardinal(numerator)}"
        }
        text = RANGE.replace(text) { match ->
            val (from, to) = match.destructured
            "${number(from)}到${number(to)}"
        }
        text = FOUR_DIGIT_YEAR.replace(text) { match -> digitsOf(match.groupValues[1]) + "年" }
        text = NUMBER_WITH_UNIT.replace(text) { match ->
            val (sign, amount, unit) = match.destructured
            signOf(sign) + number(amount) + UNIT_NAMES.getValue(unit)
        }
        text = TWO_BEFORE_MEASURE.replace(text, "两")
        text = NUMBER.replace(text) { match ->
            val (sign, amount) = match.destructured
            val spoken = if (amount.isDigitString()) digitString(amount) else number(amount)
            signOf(sign) + spoken
        }
        return text
    }

    /** Reads an integer such as 1024 as 一千零二十四. Supports values below 10^16. */
    fun cardinal(digits: String): String {
        val trimmed = digits.trimStart('0')
        if (trimmed.isEmpty()) return "零"
        if (trimmed.length > MAX_CARDINAL_DIGITS) return digitsOf(trimmed)
        val groups = trimmed.reversed().chunked(4).map { it.reversed() }
        val result = StringBuilder()
        var skippedGroup = false
        for (groupIndex in groups.indices.reversed()) {
            val group = groups[groupIndex].padStart(4, '0')
            if (group == "0000") {
                skippedGroup = result.isNotEmpty()
                continue
            }
            if (result.isNotEmpty() && (skippedGroup || group[0] == '0')) result.append('零')
            result.append(groupWords(group, leading = result.isEmpty()))
            result.append(GROUP_UNITS[groupIndex])
            skippedGroup = false
        }
        return result.toString()
    }

    fun digitsOf(digits: String): String = digits.map { DIGIT_WORDS[it - '0'] }.joinToString("")

    private fun number(amount: String): String {
        val dot = amount.indexOf('.')
        if (dot < 0) return cardinal(amount.replace(",", ""))
        val whole = amount.substring(0, dot).replace(",", "")
        return cardinal(whole) + "点" + digitsOf(amount.substring(dot + 1))
    }

    private fun groupWords(group: String, leading: Boolean): String {
        val result = StringBuilder()
        var zeroRun = false
        val started = group.indexOfFirst { it != '0' }
        for (index in started until 4) {
            val digit = group[index] - '0'
            if (digit == 0) {
                zeroRun = true
                continue
            }
            if (zeroRun) result.append('零')
            zeroRun = false
            val unit = DIGIT_UNITS[3 - index]
            val omitOne = leading && digit == 1 && unit == "十" && result.isEmpty()
            if (!omitOne) result.append(DIGIT_WORDS[digit])
            result.append(unit)
        }
        return result.toString()
    }

    private fun minuteOf(minute: String): String =
        if (minute.length == 2 && minute[0] == '0') "零" + cardinal(minute) else cardinal(minute)

    /** Long digit runs (phone numbers, IDs, codes) and zero-padded numbers are read digit by digit. */
    private fun String.isDigitString(): Boolean =
        all { it in '0'..'9' } && (length >= DIGIT_STRING_LENGTH || (length > 1 && startsWith('0')))

    private fun digitString(digits: String): String =
        if (digits.length == 11 && digits.startsWith('1')) {
            digits.map { if (it == '1') "幺" else DIGIT_WORDS[it - '0'] }.joinToString("")
        } else {
            digitsOf(digits)
        }

    private fun signOf(sign: String): String = if (sign.isEmpty()) "" else "负"

    private const val MAX_CARDINAL_DIGITS = 16
    private const val DIGIT_STRING_LENGTH = 7
    private val DIGIT_WORDS = listOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")
    private val DIGIT_UNITS = listOf("", "十", "百", "千")
    private val GROUP_UNITS = listOf("", "万", "亿", "万亿")

    private const val NOT_DIGIT_BEFORE = "(?<![0-9.])"
    private const val NOT_DIGIT_AFTER = "(?![0-9])"
    private const val AMOUNT = "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)"
    private const val SIGN = "((?<![0-9A-Za-z])-)?"

    private val DATE = Regex("$NOT_DIGIT_BEFORE(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?$NOT_DIGIT_AFTER")
    private val YEAR_MONTH = Regex("$NOT_DIGIT_BEFORE(\\d{4})年(\\d{1,2})月")
    private val TIME = Regex("$NOT_DIGIT_BEFORE([01]?\\d|2[0-4])[:：]([0-5]\\d)(?:[:：]([0-5]\\d))?$NOT_DIGIT_AFTER")
    private val CURRENCY_NAMES = mapOf("¥" to "元", "￥" to "元", "$" to "美元", "€" to "欧元", "£" to "英镑")
    private val CURRENCY_PREFIX = Regex("([¥￥$€£])\\s?$AMOUNT")
    private val PERCENT = Regex("$SIGN$AMOUNT\\s?([%％‰])")
    private val FRACTION = Regex("$NOT_DIGIT_BEFORE(\\d{1,4})/(\\d{1,4})$NOT_DIGIT_AFTER")
    private val RANGE = Regex("$NOT_DIGIT_BEFORE(\\d+(?:\\.\\d+)?)\\s?[-~～—]\\s?(\\d+(?:\\.\\d+)?)$NOT_DIGIT_AFTER(?!年)")
    private val FOUR_DIGIT_YEAR = Regex("$NOT_DIGIT_BEFORE(\\d{4})年")

    private val UNIT_NAMES = linkedMapOf(
        "km/h" to "公里每小时",
        "°C" to "摄氏度",
        "℃" to "摄氏度",
        "°F" to "华氏度",
        "℉" to "华氏度",
        "°" to "度",
        "kg" to "公斤",
        "KG" to "公斤",
        "km" to "公里",
        "KM" to "公里",
        "cm" to "厘米",
        "mm" to "毫米",
        "ml" to "毫升",
        "mL" to "毫升",
        "mg" to "毫克",
        "m²" to "平方米",
        "m³" to "立方米",
        "min" to "分钟",
        "g" to "克",
        "m" to "米",
        "h" to "小时",
        "s" to "秒",
        "L" to "升",
        "t" to "吨",
    )
    private val NUMBER_WITH_UNIT = Regex(
        "$SIGN$AMOUNT\\s?(" + UNIT_NAMES.keys.joinToString("|") { Regex.escape(it) } + ")(?![A-Za-z])",
    )
    private val NUMBER = Regex("$SIGN$AMOUNT")
    private val TWO_BEFORE_MEASURE = Regex(
        "(?<![0-9.第])2(?![0-9.])(?=个|只|条|本|次|天|位|名|件|张|把|句|种|双|斤|公斤|分钟|小时|周|辆|匹|头|杯|碗|口|声|步|眼|年|岁|层|间|家|份|封|首|颗|根|块|片|座|枚|节)",
    )
}
