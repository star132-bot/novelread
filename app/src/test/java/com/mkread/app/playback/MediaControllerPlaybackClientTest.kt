package com.mkread.app.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaControllerPlaybackClientTest {
    @Test
    fun connectedControllerPublishesAndRefreshesPlaybackState() = runTest {
        val dispatcher = TrackingDispatcher(StandardTestDispatcher(testScheduler))
        val connector = FakeConnector()
        val first = sentence(index = 0)
        val second = sentence(index = 1)
        val controller = FakeController(dispatcher).apply {
            snapshot(first, playing = true, playbackSpeed = 1.25f)
        }
        val client = client(connector, dispatcher)

        runCurrent()
        connector.succeed(controller)
        runCurrent()

        assertEquals(
            MediaControllerPlaybackState(
                currentSentenceId = first,
                isPlaying = true,
                speed = 1.25f,
                connected = true,
            ),
            client.state.value,
        )

        controller.snapshot(second, playing = false, playbackSpeed = 0.85f)
        controller.emitStateChanged()
        runCurrent()

        assertEquals(second, client.state.value.currentSentenceId)
        assertFalse(client.state.value.isPlaying)
        assertEquals(0.85f, client.state.value.speed)
        client.release()
        runCurrent()
    }

    @Test
    fun queueCommandsWaitForConnectionAndPreserveOrder() = runTest {
        val dispatcher = TrackingDispatcher(StandardTestDispatcher(testScheduler))
        val connector = FakeConnector()
        val controller = FakeController(dispatcher)
        val client = client(connector, dispatcher)
        val initial = listOf(mediaItem(0), mediaItem(1))
        val appended = listOf(mediaItem(2))

        client.replaceQueue(initial, startIndex = 1, autoplay = false)
        client.append(appended)
        runCurrent()

        assertTrue(controller.operations.isEmpty())
        connector.succeed(controller)
        runCurrent()

        assertEquals(
            listOf(
                "pause",
                "replace:2:1:${C.TIME_UNSET}",
                "prepare",
                "append:1",
            ),
            controller.operations,
        )
        client.release()
        runCurrent()
    }

    @Test
    fun transportAndSpeedCommandsRunThroughMainDispatcher() = runTest {
        val dispatcher = TrackingDispatcher(StandardTestDispatcher(testScheduler))
        val connector = FakeConnector()
        val controller = FakeController(dispatcher).apply {
            snapshot(sentence(index = 3), playing = false, playbackSpeed = 1f)
            currentIndex = 3
        }
        val client = client(connector, dispatcher)
        runCurrent()
        connector.succeed(controller)
        runCurrent()

        client.play()
        client.pause()
        client.previous()
        client.next()
        client.replay()
        client.setSpeed(1.35f)

        assertTrue(controller.operations.isEmpty())
        runCurrent()
        assertEquals(
            listOf(
                "play",
                "pause",
                "previous",
                "next",
                "seek:3:0",
                "play",
                "speed:1.35",
            ),
            controller.operations,
        )
        client.release()
        runCurrent()
    }

    @Test
    fun replayWithoutCurrentSentenceIsANoOp() = runTest {
        val dispatcher = TrackingDispatcher(StandardTestDispatcher(testScheduler))
        val connector = FakeConnector()
        val controller = FakeController(dispatcher).apply { currentIndex = 0 }
        val client = client(connector, dispatcher)
        runCurrent()
        connector.succeed(controller)
        runCurrent()

        client.replay()
        runCurrent()

        assertTrue(client.state.value.connected)
        assertTrue(controller.operations.isEmpty())
        client.release()
        runCurrent()
    }

    @Test
    fun failedConnectionRetriesAndDeliversPendingCommandOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connector = FakeConnector()
        val controller = FakeController(null)
        val client = client(connector, dispatcher, retryDelayMillis = 1_000L)

        client.play()
        runCurrent()
        assertEquals(1, connector.attemptCount)
        connector.fail(IllegalStateException("service unavailable"))
        runCurrent()
        assertFalse(client.state.value.connected)

        advanceTimeBy(999L)
        runCurrent()
        assertEquals(1, connector.attemptCount)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, connector.attemptCount)

        connector.succeed(controller)
        runCurrent()
        assertTrue(client.state.value.connected)
        assertEquals(listOf("play"), controller.operations)
        client.release()
        runCurrent()
    }

    @Test
    fun disconnectClearsVolatileStateAndReconnects() = runTest {
        val dispatcher = TrackingDispatcher(StandardTestDispatcher(testScheduler))
        val connector = FakeConnector()
        val firstController = FakeController(dispatcher).apply {
            snapshot(sentence(index = 2), playing = true, playbackSpeed = 1.1f)
        }
        val client = client(connector, dispatcher, retryDelayMillis = 500L)
        runCurrent()
        connector.succeed(firstController)
        runCurrent()

        connector.disconnect()
        runCurrent()

        assertFalse(client.state.value.connected)
        assertFalse(client.state.value.isPlaying)
        assertNull(client.state.value.currentSentenceId)
        assertTrue(firstController.released)

        advanceTimeBy(500L)
        runCurrent()
        val replacement = FakeController(dispatcher)
        connector.succeed(replacement)
        runCurrent()
        assertTrue(client.state.value.connected)
        client.release()
        runCurrent()
    }

    @Test
    fun releaseCancelsConnectionAndReleasesControllerOnMainDispatcher() = runTest {
        val dispatcher = TrackingDispatcher(StandardTestDispatcher(testScheduler))
        val connector = FakeConnector()
        val controller = FakeController(dispatcher)
        val client = client(connector, dispatcher)
        runCurrent()
        connector.succeed(controller)
        runCurrent()

        client.release()
        assertFalse(controller.released)
        runCurrent()

        assertTrue(controller.released)
        assertFalse(client.state.value.connected)
        client.play()
        runCurrent()
        assertTrue(controller.operations.isEmpty())
    }

    private fun client(
        connector: PlaybackControllerConnector,
        dispatcher: CoroutineDispatcher,
        retryDelayMillis: Long = 1_000L,
    ) = MediaControllerPlaybackClient(
        connector = connector,
        mainDispatcher = dispatcher,
        retryDelayMillis = retryDelayMillis,
    )

    private fun mediaItem(index: Int): MediaItem = MediaItem.Builder()
        .setMediaId(SentenceMediaItemFactory.mediaId(sentence(index)))
        .build()

    private fun sentence(index: Int) = SentenceId(
        bookId = "book",
        chapterId = "chapter",
        index = index,
        start = index * 10,
        end = index * 10 + 9,
    )

    private class FakeConnector : PlaybackControllerConnector {
        private val listeners = mutableListOf<PlaybackControllerConnectionListener>()
        private val attempts = mutableListOf<FakePendingConnection>()

        val attemptCount: Int
            get() = attempts.size

        override fun connect(listener: PlaybackControllerConnectionListener): PendingControllerConnection {
            listeners += listener
            return FakePendingConnection().also(attempts::add)
        }

        fun succeed(controller: PlaybackController) {
            listeners.last().onConnected(controller)
        }

        fun fail(error: Throwable) {
            listeners.last().onConnectionFailed(error)
        }

        fun disconnect() {
            listeners.last().onDisconnected()
        }
    }

    private class FakePendingConnection : PendingControllerConnection {
        override fun cancel() = Unit
    }

    private class FakeController(
        private val dispatcher: TrackingDispatcher?,
    ) : PlaybackController {
        private var stateListener: PlaybackControllerStateListener? = null
        private var mediaId: String? = null
        private var playing: Boolean = false
        private var playbackSpeed: Float = 1f

        val operations = mutableListOf<String>()
        var released = false
            private set
        override var currentIndex: Int = 0
        override val connected: Boolean
            get() = !released

        override val currentMediaId: String?
            get() = mediaId
        override val isPlaying: Boolean
            get() = playing
        override val speed: Float
            get() = playbackSpeed

        fun snapshot(sentenceId: SentenceId?, playing: Boolean, playbackSpeed: Float) {
            mediaId = sentenceId?.let(SentenceMediaItemFactory::mediaId)
            this.playing = playing
            this.playbackSpeed = playbackSpeed
        }

        fun emitStateChanged() {
            stateListener?.onPlaybackStateChanged()
        }

        override fun addStateListener(listener: PlaybackControllerStateListener) {
            requireMain()
            stateListener = listener
        }

        override fun removeStateListener(listener: PlaybackControllerStateListener) {
            requireMain()
            if (stateListener == listener) stateListener = null
        }

        override fun replaceQueue(items: List<MediaItem>, startIndex: Int, startPositionMillis: Long) {
            requireMain()
            operations += "replace:${items.size}:$startIndex:$startPositionMillis"
        }

        override fun append(items: List<MediaItem>) {
            requireMain()
            operations += "append:${items.size}"
        }

        override fun prepare() = record("prepare")

        override fun play() = record("play")

        override fun pause() = record("pause")

        override fun previous() = record("previous")

        override fun next() = record("next")

        override fun seekTo(index: Int, positionMillis: Long) {
            requireMain()
            operations += "seek:$index:$positionMillis"
        }

        override fun setSpeed(speed: Float) {
            requireMain()
            operations += "speed:$speed"
            playbackSpeed = speed
        }

        override fun release() {
            requireMain()
            released = true
        }

        private fun record(operation: String) {
            requireMain()
            operations += operation
        }

        private fun requireMain() {
            if (dispatcher != null) {
                check(dispatcher.isDispatching) { "Controller call escaped the main dispatcher" }
            }
        }
    }

    private class TrackingDispatcher(
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        var isDispatching = false
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            delegate.dispatch(context) {
                check(!isDispatching)
                isDispatching = true
                try {
                    block.run()
                } finally {
                    isDispatching = false
                }
            }
        }
    }
}
