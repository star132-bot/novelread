package com.mkread.app.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mkread.app.feature.reader.resetForNarrationReplacement
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun controllerLoadsLocalSentenceQueueAndHandlesTransportAndSpeed() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controller = MediaController.Builder(context, token)
            .buildAsync()
            .get(15, TimeUnit.SECONDS)
        val items = List(3) { index ->
            val file = File(context.cacheDir, "playback-test-$index.wav").apply {
                writeBytes(playableWave(seconds = 2))
            }
            SentenceMediaItemFactory.create(
                sentenceId = SentenceId("book", "chapter", index, index * 10, index * 10 + 9),
                waveFile = file,
                bookTitle = "测试小说",
                chapterTitle = "第一章",
            )
        }

        try {
            instrumentation.runOnMainSync {
                controller.setMediaItems(items)
                controller.prepare()
                controller.setPlaybackSpeed(1.25f)
                controller.play()
            }
            instrumentation.waitForIdleSync()

            val snapshot = onMain {
                Snapshot(
                    mediaItemCount = controller.mediaItemCount,
                    currentIndex = controller.currentMediaItemIndex,
                    speed = controller.playbackParameters.speed,
                    title = controller.currentMediaItem?.mediaMetadata?.title?.toString(),
                    sentence = controller.currentMediaItem?.mediaId?.let(SentenceMediaItemFactory::decode),
                )
            }
            assertEquals(3, snapshot.mediaItemCount)
            assertEquals(0, snapshot.currentIndex)
            assertEquals(1.25f, snapshot.speed)
            assertEquals("测试小说", snapshot.title)
            assertNotNull(snapshot.sentence)

            instrumentation.runOnMainSync {
                controller.pause()
                controller.seekToNextMediaItem()
            }
            assertEquals(1, onMain { controller.currentMediaItemIndex })
            instrumentation.runOnMainSync { controller.seekToPreviousMediaItem() }
            assertEquals(0, onMain { controller.currentMediaItemIndex })
            assertTrue(onMain { controller.isCommandAvailable(androidx.media3.common.Player.COMMAND_PLAY_PAUSE) })
        } finally {
            instrumentation.runOnMainSync {
                controller.stop()
                controller.release()
            }
            items.forEach { item -> item.localConfiguration?.uri?.path?.let(::File)?.delete() }
        }
    }

    @Test
    fun completedQueueRemainsSeekableAndCanReplayFromTheBeginning() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controller = MediaController.Builder(context, token)
            .buildAsync()
            .get(15, TimeUnit.SECONDS)
        val file = File(context.cacheDir, "playback-replay-test.wav").apply {
            writeBytes(playableWave(seconds = 2))
        }
        val item = SentenceMediaItemFactory.create(
            sentenceId = SentenceId("book", "chapter", 0, 0, 9),
            waveFile = file,
            bookTitle = "Replay test",
            chapterTitle = "Chapter",
        )

        try {
            instrumentation.runOnMainSync {
                controller.setMediaItem(item)
                controller.prepare()
                controller.play()
            }
            waitUntil { onMain { controller.playbackState == Player.STATE_ENDED } }

            instrumentation.runOnMainSync {
                controller.seekTo(0L)
                controller.play()
            }
            waitUntil {
                onMain { controller.isPlaying }
            }
            assertTrue(onMain { controller.currentPosition < 2_000L })
        } finally {
            instrumentation.runOnMainSync {
                controller.stop()
                controller.release()
            }
            file.delete()
        }
    }

    @Test
    fun narrationReplacementStopsAndClearsTheOldQueueImmediately() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controller = MediaController.Builder(context, token)
            .buildAsync()
            .get(15, TimeUnit.SECONDS)
        val file = File(context.cacheDir, "playback-replacement-test.wav").apply {
            writeBytes(playableWave(seconds = 2))
        }
        val item = SentenceMediaItemFactory.create(
            sentenceId = SentenceId("old-book", "old-chapter", 0, 0, 9),
            waveFile = file,
            bookTitle = "Old narration",
            chapterTitle = "Old chapter",
        )

        try {
            instrumentation.runOnMainSync {
                controller.setMediaItem(item)
                controller.prepare()
                controller.play()
            }
            waitUntil { onMain { controller.playWhenReady && controller.mediaItemCount == 1 } }

            instrumentation.runOnMainSync { controller.resetForNarrationReplacement() }

            assertFalse(onMain { controller.playWhenReady })
            assertEquals(0, onMain { controller.mediaItemCount })
        } finally {
            instrumentation.runOnMainSync { controller.release() }
            file.delete()
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(block) }
        return requireNotNull(value).getOrThrow()
    }

    private data class Snapshot(
        val mediaItemCount: Int,
        val currentIndex: Int,
        val speed: Float,
        val title: String?,
        val sentence: SentenceId?,
    )

    private fun playableWave(seconds: Int): ByteArray = playableWaveMillis(seconds * 1_000)

    private fun playableWaveMillis(milliseconds: Int): ByteArray {
        val sampleRate = 24_000
        val dataSize = sampleRate * milliseconds / 1_000 * 2
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray())
            writeLittleEndian32(36 + dataSize)
            write("WAVEfmt ".toByteArray())
            writeLittleEndian32(16)
            writeLittleEndian16(1)
            writeLittleEndian16(1)
            writeLittleEndian32(sampleRate)
            writeLittleEndian32(sampleRate * 2)
            writeLittleEndian16(2)
            writeLittleEndian16(16)
            write("data".toByteArray())
            writeLittleEndian32(dataSize)
            write(ByteArray(dataSize))
        }.toByteArray()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 30_000L
        while (!condition() && android.os.SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(25L)
        }
        assertTrue("Timed out waiting for playback state", condition())
    }

    private fun ByteArrayOutputStream.writeLittleEndian16(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
    }

    private fun ByteArrayOutputStream.writeLittleEndian32(value: Int) {
        writeLittleEndian16(value)
        writeLittleEndian16(value ushr 16)
    }
}
