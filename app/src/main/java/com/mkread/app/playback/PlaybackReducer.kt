package com.mkread.app.playback

class PlaybackReducer {
    fun reduce(state: PlaybackState, event: PlaybackEvent): PlaybackReduction = when (event) {
        is PlaybackEvent.Load -> load(event.queue, event.from, event.playWhenReady)
        is PlaybackEvent.AudioReady -> audioReady(state, event)
        PlaybackEvent.Play -> play(state)
        PlaybackEvent.Pause -> pause(state)
        is PlaybackEvent.MediaEnded -> mediaEnded(state, event.sentenceId)
        PlaybackEvent.Next -> move(state, delta = 1)
        PlaybackEvent.Previous -> move(state, delta = -1)
        PlaybackEvent.Replay -> replay(state)
        is PlaybackEvent.SeekToSentence -> seek(state, event.sentenceId)
        is PlaybackEvent.QueueReplaced -> queueReplaced(state, event)
        is PlaybackEvent.GenerationFailed -> generationFailed(state, event)
        PlaybackEvent.FocusLoss,
        PlaybackEvent.NoisyOutput,
        -> interrupt(state)
        PlaybackEvent.FocusGain -> PlaybackReduction(state)
        PlaybackEvent.TimerExpired -> timerExpired(state)
        is PlaybackEvent.ServiceRestored -> restore(event)
    }

    private fun load(
        queue: PlaybackQueue,
        from: SentenceId,
        playWhenReady: Boolean,
    ): PlaybackReduction {
        val sentence = requireNotNull(queue.sentence(from)) { "Start sentence must belong to the queue" }
        return if (sentence.audioPath == null) {
            PlaybackReduction(
                PlaybackState.WaitingForAudio(queue, from, playWhenReady),
                listOf(
                    PlaybackEffect.RequestGeneration(queue.generationId, from),
                    PlaybackEffect.PersistCheckpoint(from, queue.generationId, playWhenReady),
                ),
            )
        } else if (playWhenReady) {
            PlaybackReduction(
                PlaybackState.Playing(queue, from),
                listOf(
                    PlaybackEffect.SetPlayerItems(listOf(sentence)),
                    PlaybackEffect.PlayPlayer,
                    PlaybackEffect.PersistCheckpoint(from, queue.generationId, wasPlaying = true),
                ),
            )
        } else {
            PlaybackReduction(
                PlaybackState.Paused(queue, from),
                listOf(
                    PlaybackEffect.SetPlayerItems(listOf(sentence)),
                    PlaybackEffect.PersistCheckpoint(from, queue.generationId, wasPlaying = false),
                ),
            )
        }
    }

    private fun audioReady(
        state: PlaybackState,
        event: PlaybackEvent.AudioReady,
    ): PlaybackReduction {
        val queue = state.queueOrNull() ?: return PlaybackReduction(state)
        if (event.generationId != queue.generationId || queue.sentence(event.sentenceId) == null) {
            return PlaybackReduction(state)
        }
        require(event.audioPath.isNotBlank()) { "Audio path must not be blank" }
        val updatedQueue = queue.replaceAudio(event.sentenceId, event.audioPath)
        if (state is PlaybackState.WaitingForAudio && state.current == event.sentenceId) {
            val sentence = requireNotNull(updatedQueue.sentence(event.sentenceId))
            return if (state.resumeWhenReady) {
                PlaybackReduction(
                    PlaybackState.Playing(updatedQueue, state.current),
                    listOf(
                        PlaybackEffect.SetPlayerItems(listOf(sentence)),
                        PlaybackEffect.PlayPlayer,
                        PlaybackEffect.PersistCheckpoint(state.current, queue.generationId, wasPlaying = true),
                    ),
                )
            } else {
                PlaybackReduction(
                    PlaybackState.Paused(updatedQueue, state.current),
                    listOf(
                        PlaybackEffect.SetPlayerItems(listOf(sentence)),
                        PlaybackEffect.PersistCheckpoint(state.current, queue.generationId, wasPlaying = false),
                    ),
                )
            }
        }
        return PlaybackReduction(state.withQueue(updatedQueue))
    }

    private fun play(state: PlaybackState): PlaybackReduction {
        if (state is PlaybackState.WaitingForAudio) {
            return PlaybackReduction(state.copy(resumeWhenReady = true))
        }
        val context = state.contextOrNull() ?: return PlaybackReduction(state)
        val sentence = requireNotNull(context.queue.sentence(context.current))
        if (sentence.audioPath == null) {
            return PlaybackReduction(
                PlaybackState.WaitingForAudio(context.queue, context.current, resumeWhenReady = true),
                listOf(PlaybackEffect.RequestGeneration(context.queue.generationId, context.current)),
            )
        }
        return PlaybackReduction(
            PlaybackState.Playing(context.queue, context.current),
            listOf(
                PlaybackEffect.SetPlayerItems(listOf(sentence)),
                PlaybackEffect.PlayPlayer,
                PlaybackEffect.PersistCheckpoint(
                    context.current,
                    context.queue.generationId,
                    wasPlaying = true,
                ),
            ),
        )
    }

    private fun pause(state: PlaybackState): PlaybackReduction {
        val context = state.contextOrNull() ?: return PlaybackReduction(state)
        if (state !is PlaybackState.Playing && state !is PlaybackState.Preparing &&
            state !is PlaybackState.WaitingForAudio
        ) {
            return PlaybackReduction(state)
        }
        return PlaybackReduction(
            PlaybackState.Paused(context.queue, context.current),
            listOf(
                PlaybackEffect.PausePlayer,
                PlaybackEffect.PersistCheckpoint(
                    context.current,
                    context.queue.generationId,
                    wasPlaying = false,
                ),
            ),
        )
    }

    private fun mediaEnded(state: PlaybackState, ended: SentenceId): PlaybackReduction {
        val playing = state as? PlaybackState.Playing ?: return PlaybackReduction(state)
        if (playing.current != ended) return PlaybackReduction(state)
        if (playing.stopAfterCurrentSentence) {
            return PlaybackReduction(
                PlaybackState.Completed,
                listOf(
                    PlaybackEffect.PersistCheckpoint(ended, playing.queue.generationId, wasPlaying = false),
                    PlaybackEffect.StopService,
                ),
            )
        }
        val index = playing.queue.sentences.indexOfFirst { it.id == ended }
        if (index == playing.queue.sentences.lastIndex) {
            return PlaybackReduction(
                PlaybackState.Completed,
                listOf(
                    PlaybackEffect.PersistCheckpoint(ended, playing.queue.generationId, wasPlaying = false),
                    PlaybackEffect.StopService,
                ),
            )
        }
        return moveTo(playing.queue, playing.queue.sentences[index + 1], shouldPlay = true)
    }

    private fun move(state: PlaybackState, delta: Int): PlaybackReduction {
        val context = state.contextOrNull() ?: return PlaybackReduction(state)
        val currentIndex = context.queue.sentences.indexOfFirst { it.id == context.current }
        if (currentIndex < 0) return PlaybackReduction(state)
        val targetIndex = (currentIndex + delta).coerceIn(0, context.queue.sentences.lastIndex)
        if (targetIndex == currentIndex) return PlaybackReduction(state)
        return moveTo(
            queue = context.queue,
            sentence = context.queue.sentences[targetIndex],
            shouldPlay = state is PlaybackState.Playing ||
                (state is PlaybackState.WaitingForAudio && state.resumeWhenReady),
        )
    }

    private fun moveTo(
        queue: PlaybackQueue,
        sentence: QueuedSentence,
        shouldPlay: Boolean,
    ): PlaybackReduction = if (sentence.audioPath == null) {
        PlaybackReduction(
            PlaybackState.WaitingForAudio(queue, sentence.id, resumeWhenReady = shouldPlay),
            listOf(
                PlaybackEffect.RequestGeneration(queue.generationId, sentence.id),
                PlaybackEffect.PersistCheckpoint(sentence.id, queue.generationId, wasPlaying = shouldPlay),
            ),
        )
    } else {
        PlaybackReduction(
            if (shouldPlay) PlaybackState.Playing(queue, sentence.id) else PlaybackState.Paused(queue, sentence.id),
            buildList {
                add(PlaybackEffect.SetPlayerItems(listOf(sentence)))
                if (shouldPlay) add(PlaybackEffect.PlayPlayer)
                add(PlaybackEffect.PersistCheckpoint(sentence.id, queue.generationId, shouldPlay))
            },
        )
    }

    private fun replay(state: PlaybackState): PlaybackReduction {
        val context = state.contextOrNull() ?: return PlaybackReduction(state)
        val sentence = requireNotNull(context.queue.sentence(context.current))
        if (sentence.audioPath == null) {
            return PlaybackReduction(
                PlaybackState.WaitingForAudio(context.queue, context.current, resumeWhenReady = true),
                listOf(PlaybackEffect.RequestGeneration(context.queue.generationId, context.current)),
            )
        }
        return PlaybackReduction(
            PlaybackState.Playing(
                context.queue,
                context.current,
                stopAfterCurrentSentence = (state as? PlaybackState.Playing)?.stopAfterCurrentSentence == true,
            ),
            listOf(PlaybackEffect.SetPlayerItems(listOf(sentence)), PlaybackEffect.PlayPlayer),
        )
    }

    private fun seek(state: PlaybackState, sentenceId: SentenceId): PlaybackReduction {
        val context = state.contextOrNull() ?: return PlaybackReduction(state)
        val sentence = context.queue.sentence(sentenceId) ?: return PlaybackReduction(state)
        return moveTo(
            context.queue,
            sentence,
            shouldPlay = state is PlaybackState.Playing ||
                (state is PlaybackState.WaitingForAudio && state.resumeWhenReady),
        )
    }

    private fun queueReplaced(
        state: PlaybackState,
        event: PlaybackEvent.QueueReplaced,
    ): PlaybackReduction {
        val shouldPlay = state is PlaybackState.Playing ||
            (state is PlaybackState.WaitingForAudio && state.resumeWhenReady)
        return load(event.queue, event.from, shouldPlay)
    }

    private fun generationFailed(
        state: PlaybackState,
        event: PlaybackEvent.GenerationFailed,
    ): PlaybackReduction {
        val context = state.contextOrNull() ?: return PlaybackReduction(state)
        if (context.queue.generationId != event.generationId || context.current != event.sentenceId) {
            return PlaybackReduction(state)
        }
        return PlaybackReduction(
            PlaybackState.Failed(context.queue, context.current, event.message),
            listOf(
                PlaybackEffect.PausePlayer,
                PlaybackEffect.PersistCheckpoint(
                    context.current,
                    context.queue.generationId,
                    wasPlaying = false,
                ),
                PlaybackEffect.ShowError(event.message, event.retryable),
            ),
        )
    }

    private fun interrupt(state: PlaybackState): PlaybackReduction {
        val context = when (state) {
            is PlaybackState.Playing -> QueueContext(state.queue, state.current)
            is PlaybackState.Preparing -> QueueContext(state.queue, state.current)
            is PlaybackState.WaitingForAudio -> QueueContext(state.queue, state.current)
            else -> return PlaybackReduction(state)
        }
        return PlaybackReduction(
            PlaybackState.Paused(context.queue, context.current, PlaybackPauseReason.INTERRUPTION),
            listOf(
                PlaybackEffect.PausePlayer,
                PlaybackEffect.PersistCheckpoint(
                    context.current,
                    context.queue.generationId,
                    wasPlaying = false,
                ),
            ),
        )
    }

    private fun timerExpired(state: PlaybackState): PlaybackReduction = when (state) {
        is PlaybackState.Playing -> PlaybackReduction(state.copy(stopAfterCurrentSentence = true))
        is PlaybackState.Preparing,
        is PlaybackState.WaitingForAudio,
        is PlaybackState.Paused,
        is PlaybackState.Failed,
        -> PlaybackReduction(
            PlaybackState.Completed,
            listOf(PlaybackEffect.PausePlayer, PlaybackEffect.StopService),
        )
        PlaybackState.Idle,
        PlaybackState.Completed,
        -> PlaybackReduction(state)
    }

    private fun restore(event: PlaybackEvent.ServiceRestored): PlaybackReduction {
        if (event.checkpoint.queueGenerationId != event.queue.generationId ||
            event.queue.sentence(event.checkpoint.sentenceId) == null
        ) {
            return PlaybackReduction(PlaybackState.Idle)
        }
        return load(
            event.queue,
            event.checkpoint.sentenceId,
            playWhenReady = event.resumeRequested,
        )
    }

    private data class QueueContext(val queue: PlaybackQueue, val current: SentenceId)

    private fun PlaybackState.contextOrNull(): QueueContext? = when (this) {
        is PlaybackState.Preparing -> QueueContext(queue, current)
        is PlaybackState.Playing -> QueueContext(queue, current)
        is PlaybackState.Paused -> QueueContext(queue, current)
        is PlaybackState.WaitingForAudio -> QueueContext(queue, current)
        is PlaybackState.Failed -> QueueContext(queue, current)
        PlaybackState.Idle,
        PlaybackState.Completed,
        -> null
    }

    private fun PlaybackState.queueOrNull(): PlaybackQueue? = contextOrNull()?.queue

    private fun PlaybackState.withQueue(queue: PlaybackQueue): PlaybackState = when (this) {
        is PlaybackState.Preparing -> copy(queue = queue)
        is PlaybackState.Playing -> copy(queue = queue)
        is PlaybackState.Paused -> copy(queue = queue)
        is PlaybackState.WaitingForAudio -> copy(queue = queue)
        is PlaybackState.Failed -> copy(queue = queue)
        PlaybackState.Idle,
        PlaybackState.Completed,
        -> this
    }
}
