package com.mkread.app.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class WaveSilenceTrimmerTest {
    @Test
    fun keepsSmallNaturalMarginsAroundSpeech() {
        val samples = ShortArray(48_000)
        for (index in 12_000 until 36_000) samples[index] = 2_000

        val bounds = WaveSilenceTrimmer.findSpeechBounds(samples, sampleRate = 24_000)

        assertEquals(11_040, bounds.first)
        assertEquals(37_920, bounds.last + 1)
    }
}
