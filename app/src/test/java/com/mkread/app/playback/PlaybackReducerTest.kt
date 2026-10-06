package com.mkread.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReducerTest {
    private val reducer = PlaybackReducer()
    private val first = sentence("chapter-1", index = 0, start = 0, end = 8, audioPath = "first.wav")
    private val second = sentence("chapter-1", index = 1, start = 8, end = 16)
    private val third = sentence("chapter-2", index = 0, start = 0, end = 9, audioPath = "third.wav")
    private val queue = PlaybackQueue(generationId = 12L, sentences = listOf(first, second, third))

    @Test
    fun mediaEndAdvancesOnlyToImmediateNextSentenceAndWaitsForItsAudio() {
        val state = PlaybackState.Playing(queue = queue, current = first.id)

        val result = reducer.reduce(state, PlaybackEvent.MediaEnded(first.id))

        assertEquals(
            PlaybackState.WaitingForAudio(queue, second.id, resumeWhenReady = true),
            result.state,
        )
        assertEquals(
            listOf(
                PlaybackEffect.RequestGeneration(queue.generationId, second.id),
                PlaybackEffect.PersistCheckpoint(second.id, queue.generationId, wasPlaying = true),
            ),
            result.effects,
        )
    }

    @Test
    fun readyImmediateNextSentenceContinuesWithoutSkipping() {
        val readyQueue = queue.copy(sentences = queue.sentences.map { queued ->
            if (queued.id == second.id) queued.copy(audioPath = "second.wav") else queued
        })

        val result = reducer.reduce(
            PlaybackState.Playing(readyQueue, first.id),
            PlaybackEvent.MediaEnded(first.id),
        )

        assertEquals(PlaybackState.Playing(readyQueue, second.id), result.state)
        assertEquals(
            listOf(
                PlaybackEffect.SetPlayerItems(listOf(readyQueue.sentences[1])),
                PlaybackEffect.PlayPlayer,
                PlaybackEffect.PersistCheckpoint(second.id, readyQueue.generationId, wasPlaying = true),
            ),
            result.effects,
        )
    }

    @Test
    fun oldGenerationAudioResultIsIgnored() {
        val state = PlaybackState.WaitingForAudio(queue, second.id, resumeWhenReady = true)

        val result = reducer.reduce(
            state,
            PlaybackEvent.AudioReady(
                generationId = queue.generationId - 1,
                sentenceId = second.id,
                audioPath = "stale.wav",
            ),
        )

        assertEquals(state, result.state)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun previousAndNextClampAtQueueEdgesAcrossChapterBoundaries() {
        val atStart = PlaybackState.Paused(queue, first.id)
        assertEquals(atStart, reducer.reduce(atStart, PlaybackEvent.Previous).state)

        val beforeChapterBoundary = PlaybackState.Paused(queue, second.id)
        val next = reducer.reduce(beforeChapterBoundary, PlaybackEvent.Next)
        assertEquals(PlaybackState.Paused(queue, third.id), next.state)

        val atEnd = PlaybackState.Paused(queue, third.id)
        assertEquals(atEnd, reducer.reduce(atEnd, PlaybackEvent.Next).state)
    }

    @Test
    fun replayRetainsExactSentenceId() {
        val state = PlaybackState.Playing(queue, third.id)

        val result = reducer.reduce(state, PlaybackEvent.Replay)

        assertEquals(state, result.state)
        assertEquals(
            listOf(
                PlaybackEffect.SetPlayerItems(listOf(third)),
                PlaybackEffect.PlayPlayer,
            ),
            result.effects,
        )
    }

    @Test
    fun permanentGenerationFailureStopsOnSameSentence() {
        val state = PlaybackState.WaitingForAudio(queue, second.id, resumeWhenReady = true)

        val result = reducer.reduce(
            state,
            PlaybackEvent.GenerationFailed(
                generationId = queue.generationId,
                sentenceId = second.id,
                message = "音频生成失败",
                retryable = false,
            ),
        )

        assertEquals(PlaybackState.Failed(queue, second.id, "音频生成失败"), result.state)
        assertEquals(
            listOf(
                PlaybackEffect.PausePlayer,
                PlaybackEffect.PersistCheckpoint(second.id, queue.generationId, wasPlaying = false),
                PlaybackEffect.ShowError("音频生成失败", retryable = false),
            ),
            result.effects,
        )
    }

    @Test
    fun focusLossAndNoisyOutputPauseButFocusGainDoesNotAutoResume() {
        listOf(PlaybackEvent.FocusLoss, PlaybackEvent.NoisyOutput).forEach { event ->
            val result = reducer.reduce(PlaybackState.Playing(queue, first.id), event)
            assertEquals(PlaybackState.Paused(queue, first.id, PlaybackPauseReason.INTERRUPTION), result.state)
            assertTrue(result.effects.contains(PlaybackEffect.PausePlayer))
        }

        val paused = PlaybackState.Paused(queue, first.id, PlaybackPauseReason.INTERRUPTION)
        val focusGain = reducer.reduce(paused, PlaybackEvent.FocusGain)
        assertEquals(paused, focusGain.state)
        assertFalse(focusGain.effects.contains(PlaybackEffect.PlayPlayer))
    }

    @Test
    fun focusLossAndNoisyOutputCancelPendingResumeWhileWaitingForAudio() {
        listOf(PlaybackEvent.FocusLoss, PlaybackEvent.NoisyOutput).forEach { event ->
            val interrupted = reducer.reduce(
                PlaybackState.WaitingForAudio(queue, second.id, resumeWhenReady = true),
                event,
            )

            assertEquals(
                PlaybackState.Paused(queue, second.id, PlaybackPauseReason.INTERRUPTION),
                interrupted.state,
            )
            assertEquals(
                listOf(
                    PlaybackEffect.PausePlayer,
                    PlaybackEffect.PersistCheckpoint(
                        second.id,
                        queue.generationId,
                        wasPlaying = false,
                    ),
                ),
                interrupted.effects,
            )

            val ready = reducer.reduce(
                interrupted.state,
                PlaybackEvent.AudioReady(queue.generationId, second.id, "second.wav"),
            )
            assertTrue(ready.state is PlaybackState.Paused)
            assertFalse(ready.effects.contains(PlaybackEffect.PlayPlayer))
        }
    }

    @Test
    fun timerExpiryStopsOnlyAfterCurrentSentenceEnds() {
        val state = PlaybackState.Playing(queue, first.id)

        val marked = reducer.reduce(state, PlaybackEvent.TimerExpired)
        assertEquals(
            PlaybackState.Playing(queue, first.id, stopAfterCurrentSentence = true),
            marked.state,
        )
        assertFalse(marked.effects.contains(PlaybackEffect.StopService))

        val ended = reducer.reduce(marked.state, PlaybackEvent.MediaEnded(first.id))
        assertEquals(PlaybackState.Completed, ended.state)
        assertTrue(ended.effects.contains(PlaybackEffect.StopService))
    }

    @Test
    fun ordinaryServiceRestoreNeverAutoPlays() {
        val checkpoint = checkpoint(wasPlaying = true)

        val ordinary = reducer.reduce(
            PlaybackState.Idle,
            PlaybackEvent.ServiceRestored(queue, checkpoint, resumeRequested = false),
        )
        assertEquals(PlaybackState.Paused(queue, first.id), ordinary.state)
        assertFalse(ordinary.effects.contains(PlaybackEffect.PlayPlayer))

        val mediaButton = reducer.reduce(
            PlaybackState.Idle,
            PlaybackEvent.ServiceRestored(queue, checkpoint, resumeRequested = true),
        )
        assertEquals(PlaybackState.Playing(queue, first.id), mediaButton.state)
        assertTrue(mediaButton.effects.contains(PlaybackEffect.PlayPlayer))
    }

    @Test
    fun explicitMediaButtonResumeOverridesPriorPausedCheckpoint() {
        val result = reducer.reduce(
            PlaybackState.Idle,
            PlaybackEvent.ServiceRestored(
                queue = queue,
                checkpoint = checkpoint(wasPlaying = false),
                resumeRequested = true,
            ),
        )

        assertEquals(PlaybackState.Playing(queue, first.id), result.state)
        assertTrue(result.effects.contains(PlaybackEffect.PlayPlayer))
    }

    private fun checkpoint(wasPlaying: Boolean) = PlaybackCheckpoint(
        sentenceId = first.id,
        queueGenerationId = queue.generationId,
        speed = 1.0f,
        wasPlaying = wasPlaying,
        updatedAtEpochMillis = 123L,
    )

    private fun sentence(
        chapterId: String,
        index: Int,
        start: Int,
        end: Int,
        audioPath: String? = null,
    ) = QueuedSentence(
        id = SentenceId(
            bookId = "book-1",
            chapterId = chapterId,
            index = index,
            start = start,
            end = end,
        ),
        audioPath = audioPath,
    )
}
