package com.mkread.app.playback

fun interface MonotonicClock {
    fun nowMillis(): Long
}

enum class SleepTimerPreset(
    val durationMillis: Long?,
) {
    OFF(null),
    MINUTES_10(10 * MILLIS_PER_MINUTE),
    MINUTES_20(20 * MILLIS_PER_MINUTE),
    MINUTES_30(30 * MILLIS_PER_MINUTE),
    MINUTES_45(45 * MILLIS_PER_MINUTE),
    MINUTES_60(60 * MILLIS_PER_MINUTE),
    MINUTES_90(90 * MILLIS_PER_MINUTE),
}

data class SleepTimerState(
    val scheduledAtElapsedMillis: Long?,
    val deadlineElapsedMillis: Long?,
    val stopAfterCurrentSentence: Boolean = false,
) {
    init {
        require((scheduledAtElapsedMillis == null) == (deadlineElapsedMillis == null)) {
            "Sleep timer timestamps must both be present or absent"
        }
        if (scheduledAtElapsedMillis != null && deadlineElapsedMillis != null) {
            require(scheduledAtElapsedMillis >= 0L) { "Sleep timer start must be non-negative" }
            require(deadlineElapsedMillis >= scheduledAtElapsedMillis) {
                "Sleep timer deadline must not precede its start"
            }
        } else {
            require(!stopAfterCurrentSentence) { "An inactive timer cannot defer a stop" }
        }
    }

    val isScheduled: Boolean
        get() = deadlineElapsedMillis != null

    companion object {
        val OFF = SleepTimerState(
            scheduledAtElapsedMillis = null,
            deadlineElapsedMillis = null,
        )
    }
}

sealed interface SleepTimerDecision {
    data object Inactive : SleepTimerDecision

    data class Active(
        val state: SleepTimerState,
        val remainingMillis: Long,
    ) : SleepTimerDecision

    data class StopAfterCurrentSentence(
        val state: SleepTimerState,
    ) : SleepTimerDecision

    data object StopNow : SleepTimerDecision

    data object CancelledAfterClockReset : SleepTimerDecision
}

class SleepTimer(
    private val clock: MonotonicClock,
) {
    fun schedule(
        preset: SleepTimerPreset,
        current: SleepTimerState = SleepTimerState.OFF,
    ): SleepTimerState {
        require(current == SleepTimerState.OFF || current.isScheduled) {
            "Current sleep timer state is invalid"
        }
        val duration = preset.durationMillis ?: return SleepTimerState.OFF
        val now = clock.nowMillis()
        require(now >= 0L) { "Elapsed realtime must be non-negative" }
        return SleepTimerState(
            scheduledAtElapsedMillis = now,
            deadlineElapsedMillis = Math.addExact(now, duration),
        )
    }

    fun cancel(current: SleepTimerState): SleepTimerState {
        require(current == SleepTimerState.OFF || current.isScheduled) {
            "Current sleep timer state is invalid"
        }
        return SleepTimerState.OFF
    }

    fun evaluate(
        state: SleepTimerState,
        sentenceActive: Boolean,
    ): SleepTimerDecision {
        if (!state.isScheduled) return SleepTimerDecision.Inactive
        val scheduledAt = requireNotNull(state.scheduledAtElapsedMillis)
        val deadline = requireNotNull(state.deadlineElapsedMillis)
        val now = clock.nowMillis()
        if (now < scheduledAt) return SleepTimerDecision.CancelledAfterClockReset
        if (state.stopAfterCurrentSentence) {
            return if (sentenceActive) {
                SleepTimerDecision.StopAfterCurrentSentence(state)
            } else {
                SleepTimerDecision.StopNow
            }
        }
        if (now < deadline) {
            return SleepTimerDecision.Active(state, remainingMillis = deadline - now)
        }
        return if (sentenceActive) {
            SleepTimerDecision.StopAfterCurrentSentence(
                state.copy(stopAfterCurrentSentence = true),
            )
        } else {
            SleepTimerDecision.StopNow
        }
    }

    fun onSentenceEnded(state: SleepTimerState): SleepTimerDecision =
        if (state.stopAfterCurrentSentence) {
            SleepTimerDecision.StopNow
        } else {
            SleepTimerDecision.Inactive
        }
}

private const val MILLIS_PER_MINUTE = 60_000L
