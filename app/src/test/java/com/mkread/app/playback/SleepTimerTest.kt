package com.mkread.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {
    private val clock = FakeMonotonicClock(nowMillis = 1_000L)
    private val timer = SleepTimer(clock)

    @Test
    fun everyEnabledPresetUsesItsExactMonotonicDuration() {
        SleepTimerPreset.entries.filter { it != SleepTimerPreset.OFF }.forEach { preset ->
            val state = timer.schedule(preset)

            assertEquals(clock.nowMillis, state.scheduledAtElapsedMillis)
            assertEquals(clock.nowMillis + preset.durationMillis!!, state.deadlineElapsedMillis)
            assertFalse(state.stopAfterCurrentSentence)
        }
    }

    @Test
    fun replacingAndCancellingAreIdempotent() {
        val first = timer.schedule(SleepTimerPreset.MINUTES_10)
        clock.nowMillis += 2_000L
        val replacement = timer.schedule(SleepTimerPreset.MINUTES_45, first)

        assertEquals(clock.nowMillis, replacement.scheduledAtElapsedMillis)
        assertEquals(clock.nowMillis + SleepTimerPreset.MINUTES_45.durationMillis!!, replacement.deadlineElapsedMillis)
        assertEquals(SleepTimerState.OFF, timer.cancel(replacement))
        assertEquals(SleepTimerState.OFF, timer.cancel(SleepTimerState.OFF))
        assertEquals(SleepTimerState.OFF, timer.schedule(SleepTimerPreset.OFF, replacement))
    }

    @Test
    fun expiryDuringSentenceStopsOnlyAfterThatSentenceEnds() {
        val scheduled = timer.schedule(SleepTimerPreset.MINUTES_10)
        clock.nowMillis = scheduled.deadlineElapsedMillis!!

        val expired = timer.evaluate(scheduled, sentenceActive = true)
        assertTrue(expired is SleepTimerDecision.StopAfterCurrentSentence)
        val marked = (expired as SleepTimerDecision.StopAfterCurrentSentence).state
        assertTrue(marked.stopAfterCurrentSentence)
        assertEquals(SleepTimerDecision.StopNow, timer.onSentenceEnded(marked))
    }

    @Test
    fun expiryWhilePausedStopsImmediately() {
        val scheduled = timer.schedule(SleepTimerPreset.MINUTES_20)
        clock.nowMillis = scheduled.deadlineElapsedMillis!! + 1L

        assertEquals(SleepTimerDecision.StopNow, timer.evaluate(scheduled, sentenceActive = false))
    }

    @Test
    fun serviceRecreationRetainsAbsoluteDeadline() {
        val scheduled = timer.schedule(SleepTimerPreset.MINUTES_30)
        clock.nowMillis += 5 * 60_000L

        assertEquals(
            SleepTimerDecision.Active(scheduled, remainingMillis = 25 * 60_000L),
            timer.evaluate(scheduled, sentenceActive = true),
        )
    }

    @Test
    fun elapsedRealtimeResetCancelsInsteadOfStoppingImmediately() {
        val scheduled = timer.schedule(SleepTimerPreset.MINUTES_90)
        clock.nowMillis = scheduled.scheduledAtElapsedMillis!! - 1L

        assertEquals(
            SleepTimerDecision.CancelledAfterClockReset,
            timer.evaluate(scheduled, sentenceActive = true),
        )
    }

    @Test
    fun rebootCancelsEvenWhenNewUptimeExceedsOldDeadline() {
        clock.sessionId = "boot-before"
        val scheduled = timer.schedule(SleepTimerPreset.MINUTES_10)
        clock.sessionId = "boot-after"
        clock.nowMillis = scheduled.deadlineElapsedMillis!! + 60_000L

        assertEquals(
            SleepTimerDecision.CancelledAfterClockReset,
            timer.evaluate(scheduled, sentenceActive = true),
        )
    }

    @Test
    fun sentenceEndAtExpiredDeadlineStopsWithoutPriorTimerEvaluation() {
        val scheduled = timer.schedule(SleepTimerPreset.MINUTES_20)
        clock.nowMillis = scheduled.deadlineElapsedMillis!! + 5 * 60_000L

        assertEquals(SleepTimerDecision.StopNow, timer.onSentenceEnded(scheduled))
    }

    @Test
    fun sentenceEndDoesNothingBeforeExpiryWasMarked() {
        val scheduled = timer.schedule(SleepTimerPreset.MINUTES_60)

        assertEquals(SleepTimerDecision.Inactive, timer.onSentenceEnded(scheduled))
    }

    private class FakeMonotonicClock(
        var nowMillis: Long,
        var sessionId: String = "boot-1",
    ) : MonotonicClock {
        override fun nowMillis(): Long = nowMillis

        override fun sessionId(): String = sessionId
    }
}
