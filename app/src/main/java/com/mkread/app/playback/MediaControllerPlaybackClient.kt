package com.mkread.app.playback

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MediaControllerPlaybackState(
    val currentSentenceId: SentenceId? = null,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val connected: Boolean = false,
)

class MediaControllerPlaybackClient internal constructor(
    private val connector: PlaybackControllerConnector,
    private val mainDispatcher: CoroutineDispatcher,
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
) {
    constructor(context: Context) : this(
        connector = Media3PlaybackControllerConnector(context.applicationContext),
        mainDispatcher = Dispatchers.Main.immediate,
    )

    private val released = AtomicBoolean(false)
    private val clientJob = SupervisorJob()
    private val mainScope = CoroutineScope(clientJob + mainDispatcher)
    private val pendingCommands = ArrayDeque<ControllerCommand>()
    private val _state = MutableStateFlow(MediaControllerPlaybackState())

    val state: StateFlow<MediaControllerPlaybackState> = _state.asStateFlow()

    private var controller: PlaybackController? = null
    private var connectionAttempt: PendingControllerConnection? = null
    private var connectionGeneration = 0L
    private var connecting = false
    private var retryCount = 0
    private var retryJob: Job? = null

    private val controllerStateListener = PlaybackControllerStateListener {
        mainScope.launch {
            controller?.let(::refreshState)
        }
    }

    init {
        require(retryDelayMillis > 0L) { "Retry delay must be positive" }
        mainScope.launch { connectIfNeeded() }
    }

    fun replaceQueue(
        items: List<MediaItem>,
        startIndex: Int,
        autoplay: Boolean,
    ) {
        require(items.isNotEmpty()) { "Playback queue must not be empty" }
        require(startIndex in items.indices) { "Start index is outside the playback queue" }
        requireSentenceItems(items)
        val queue = items.toList()
        submit { player ->
            if (!autoplay) player.pause()
            player.replaceQueue(queue, startIndex, C.TIME_UNSET)
            player.prepare()
            if (autoplay) player.play()
        }
    }

    fun append(items: List<MediaItem>) {
        if (items.isEmpty()) return
        requireSentenceItems(items)
        val appended = items.toList()
        submit { player -> player.append(appended) }
    }

    fun play() = submit(PlaybackController::play)

    fun pause() = submit(PlaybackController::pause)

    fun previous() = submit(PlaybackController::previous)

    fun next() = submit(PlaybackController::next)

    fun replay() = submit { player ->
        if (player.currentMediaId != null && player.currentIndex != C.INDEX_UNSET) {
            player.seekTo(player.currentIndex, 0L)
            player.play()
        }
    }

    fun setSpeed(speed: Float) {
        require(speed.isFinite() && speed in MIN_SPEED..MAX_SPEED) {
            "Playback speed must be between $MIN_SPEED and $MAX_SPEED"
        }
        submit { player -> player.setSpeed(speed) }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        mainScope.launch {
            retryJob?.cancel()
            retryJob = null
            connectionAttempt?.cancel()
            connectionAttempt = null
            pendingCommands.clear()
            detachController()
            markDisconnected()
            mainScope.cancel()
        }
    }

    private fun requireSentenceItems(items: List<MediaItem>) {
        require(items.all { SentenceMediaItemFactory.decode(it.mediaId) != null }) {
            "Every media item must contain a complete sentence id"
        }
    }

    private fun submit(command: ControllerCommand) {
        if (released.get()) return
        mainScope.launch {
            if (released.get()) return@launch
            val current = controller
            if (current == null) {
                pendingCommands.addLast(command)
                connectIfNeeded()
            } else {
                execute(current, command)
            }
        }
    }

    private fun connectIfNeeded() {
        if (
            released.get() ||
            controller != null ||
            connecting ||
            retryJob != null
        ) {
            return
        }
        connecting = true
        val generation = ++connectionGeneration
        val listener = object : PlaybackControllerConnectionListener {
            override fun onConnected(controller: PlaybackController) {
                mainScope.launch { handleConnected(generation, controller) }
            }

            override fun onConnectionFailed(error: Throwable) {
                mainScope.launch { handleConnectionFailure(generation) }
            }

            override fun onDisconnected() {
                mainScope.launch { handleDisconnected(generation) }
            }
        }
        val attempt = runCatching { connector.connect(listener) }
            .getOrElse {
                connecting = false
                handleConnectionFailure(generation)
                return
            }
        if (connecting && generation == connectionGeneration && !released.get()) {
            connectionAttempt = attempt
        } else if (released.get()) {
            attempt.cancel()
        }
    }

    private fun handleConnected(generation: Long, connectedController: PlaybackController) {
        if (released.get() || generation != connectionGeneration) {
            connectedController.release()
            return
        }
        connecting = false
        connectionAttempt = null
        if (!connectedController.connected) {
            connectedController.release()
            handleConnectionFailure(generation)
            return
        }
        retryJob?.cancel()
        retryJob = null
        retryCount = 0
        controller = connectedController
        connectedController.addStateListener(controllerStateListener)
        refreshState(connectedController)
        drainPendingCommands(connectedController)
    }

    private fun handleConnectionFailure(generation: Long) {
        if (released.get() || generation != connectionGeneration) return
        connecting = false
        connectionAttempt = null
        markDisconnected()
        scheduleReconnect()
    }

    private fun handleDisconnected(generation: Long) {
        if (released.get() || generation != connectionGeneration) return
        connecting = false
        connectionAttempt = null
        detachController()
        markDisconnected()
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (released.get() || retryJob != null) return
        val delayMillis = retryDelayFor(retryCount++)
        retryJob = mainScope.launch {
            delay(delayMillis)
            retryJob = null
            connectIfNeeded()
        }
    }

    private fun retryDelayFor(attempt: Int): Long {
        val multiplier = 1L shl attempt.coerceIn(0, MAX_RETRY_SHIFT)
        return (retryDelayMillis * multiplier).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
    }

    private fun drainPendingCommands(connectedController: PlaybackController) {
        while (controller === connectedController && pendingCommands.isNotEmpty()) {
            val command = pendingCommands.removeFirst()
            if (!execute(connectedController, command)) return
        }
    }

    private fun execute(
        connectedController: PlaybackController,
        command: ControllerCommand,
    ): Boolean = runCatching {
        command.run(connectedController)
        refreshState(connectedController)
    }.fold(
        onSuccess = { true },
        onFailure = {
            handleDisconnected(connectionGeneration)
            false
        },
    )

    private fun refreshState(connectedController: PlaybackController) {
        if (controller !== connectedController) return
        _state.value = MediaControllerPlaybackState(
            currentSentenceId = connectedController.currentMediaId
                ?.let(SentenceMediaItemFactory::decode),
            isPlaying = connectedController.isPlaying,
            speed = connectedController.speed,
            connected = connectedController.connected,
        )
    }

    private fun detachController() {
        val detached = controller ?: return
        controller = null
        runCatching { detached.removeStateListener(controllerStateListener) }
        runCatching { detached.release() }
    }

    private fun markDisconnected() {
        _state.value = _state.value.copy(
            currentSentenceId = null,
            isPlaying = false,
            connected = false,
        )
    }

    private fun interface ControllerCommand {
        fun run(controller: PlaybackController)
    }

    private companion object {
        const val DEFAULT_RETRY_DELAY_MILLIS = 1_000L
        const val MAX_RETRY_DELAY_MILLIS = 30_000L
        const val MAX_RETRY_SHIFT = 5
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2f
    }
}

internal fun interface PendingControllerConnection {
    fun cancel()
}

internal interface PlaybackControllerConnectionListener {
    fun onConnected(controller: PlaybackController)

    fun onConnectionFailed(error: Throwable)

    fun onDisconnected()
}

internal fun interface PlaybackControllerStateListener {
    fun onPlaybackStateChanged()
}

internal interface PlaybackControllerConnector {
    fun connect(listener: PlaybackControllerConnectionListener): PendingControllerConnection
}

internal interface PlaybackController {
    val connected: Boolean
    val currentMediaId: String?
    val currentIndex: Int
    val isPlaying: Boolean
    val speed: Float

    fun addStateListener(listener: PlaybackControllerStateListener)

    fun removeStateListener(listener: PlaybackControllerStateListener)

    fun replaceQueue(items: List<MediaItem>, startIndex: Int, startPositionMillis: Long)

    fun append(items: List<MediaItem>)

    fun prepare()

    fun play()

    fun pause()

    fun previous()

    fun next()

    fun seekTo(index: Int, positionMillis: Long)

    fun setSpeed(speed: Float)

    fun release()
}

private class Media3PlaybackControllerConnector(
    private val context: Context,
) : PlaybackControllerConnector {
    override fun connect(listener: PlaybackControllerConnectionListener): PendingControllerConnection {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setApplicationLooper(Looper.getMainLooper())
            .setListener(
                object : MediaController.Listener {
                    override fun onDisconnected(controller: MediaController) {
                        listener.onDisconnected()
                    }
                },
            )
            .buildAsync()
        future.addListener(
            {
                runCatching { future.get() }.fold(
                    onSuccess = { listener.onConnected(Media3PlaybackController(it)) },
                    onFailure = { error ->
                        listener.onConnectionFailed(error.cause ?: error)
                    },
                )
            },
            context.mainExecutor,
        )
        return PendingControllerConnection { MediaController.releaseFuture(future) }
    }
}

private class Media3PlaybackController(
    private val controller: MediaController,
) : PlaybackController {
    private val playerListeners = mutableMapOf<PlaybackControllerStateListener, Player.Listener>()

    override val connected: Boolean
        get() = controller.isConnected
    override val currentMediaId: String?
        get() = controller.currentMediaItem?.mediaId
    override val currentIndex: Int
        get() = controller.currentMediaItemIndex
    override val isPlaying: Boolean
        get() = controller.isPlaying
    override val speed: Float
        get() = controller.playbackParameters.speed

    override fun addStateListener(listener: PlaybackControllerStateListener) {
        val playerListener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                listener.onPlaybackStateChanged()
            }
        }
        playerListeners.put(listener, playerListener)?.let(controller::removeListener)
        controller.addListener(playerListener)
    }

    override fun removeStateListener(listener: PlaybackControllerStateListener) {
        playerListeners.remove(listener)?.let(controller::removeListener)
    }

    override fun replaceQueue(
        items: List<MediaItem>,
        startIndex: Int,
        startPositionMillis: Long,
    ) = controller.setMediaItems(items, startIndex, startPositionMillis)

    override fun append(items: List<MediaItem>) = controller.addMediaItems(items)

    override fun prepare() = controller.prepare()

    override fun play() = controller.play()

    override fun pause() = controller.pause()

    override fun previous() = controller.seekToPreviousMediaItem()

    override fun next() = controller.seekToNextMediaItem()

    override fun seekTo(index: Int, positionMillis: Long) = controller.seekTo(index, positionMillis)

    override fun setSpeed(speed: Float) = controller.setPlaybackSpeed(speed)

    override fun release() {
        playerListeners.values.forEach(controller::removeListener)
        playerListeners.clear()
        controller.release()
    }
}
