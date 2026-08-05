package com.mkread.app.feature.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPaginationEngineTest {
    private val engine = AndroidPaginationEngine(
        typefaceResolver = { ReaderTestFont.typeface },
    )

    @Test
    fun emptyChapterEmitsOneCompleteEmptyBatch() = runBlocking {
        val batches = engine.paginate("", spec()).toList()

        assertEquals(listOf(PaginationBatch(emptyList(), complete = true)), batches)
    }

    @Test
    fun pagesCoverEmojiAndDialogueWithoutOverlap() = runBlocking {
        val text = ("她说：“等等……别走！” ".repeat(100)) + "结尾。"
        val final = engine.paginate(text, spec()).last()

        assertTrue(final.complete)
        assertTrue(validPageRanges(text, final.ranges))
        assertEquals(text.length, final.ranges.last().endExclusive)
    }

    @Test(timeout = 60_000L)
    fun longChapterPublishesFirstBatchBeforeCompletePagination() = runBlocking {
        val text = ("This is a long paragraph for progressive layout. ".repeat(8_000))
        var firstBatch: PaginationBatch? = null
        val batches = withTimeout(60_000L) {
            engine.paginate(text, spec()).toList().also { firstBatch = it.firstOrNull() }
        }

        assertTrue(firstBatch?.ranges?.isNotEmpty() == true)
        assertFalse(firstBatch?.complete == true)
        assertTrue(batches.last().complete)
        assertTrue(validPageRanges(text, batches.last().ranges))
    }

    @Test
    fun viewportAndTypographyChangesProduceDifferentBoundaries() = runBlocking {
        val text = ("一段需要分页的内容，用来验证窗口和字号变化。 ".repeat(500))
        val narrow = engine.paginate(text, spec(widthPx = 280)).last().ranges
        val wide = engine.paginate(text, spec(widthPx = 520)).last().ranges
        val large = engine.paginate(text, spec(fontSizeSp = 25f)).last().ranges
        val scaled = engine.paginate(text, spec(fontScale = 1.5f)).last().ranges

        assertFalse(narrow == wide)
        assertFalse(narrow == large)
        assertFalse(narrow == scaled)
    }

    @Test(timeout = 10_000L)
    fun cancellationAfterFirstBatchStopsPaginationPromptly() = runBlocking {
        val text = "取消测试。 ".repeat(20_000)
        val received = AtomicBoolean(false)
        try {
            withTimeout(10_000L) {
                engine.paginate(text, spec()).collect {
                    received.set(true)
                    throw CancellationException("stop after first batch")
                }
            }
        } catch (_: CancellationException) {
            // Expected cancellation path.
        }
        assertTrue(received.get())
    }

    @Test
    fun cacheHitEmitsOnlyTheStoredCompleteBatch() = runBlocking {
        val text = "cached"
        val ranges = listOf(PageRange(0, 0, text.length))
        val key = PaginationKey("chapter-1", "hash-1", spec())
        val cache = RecordingCache(ranges)
        val cachedEngine = AndroidPaginationEngine(
            cache = cache,
            typefaceResolver = { ReaderTestFont.typeface },
        )

        val batches = cachedEngine.paginate(key, text, spec()).toList()

        assertEquals(listOf(PaginationBatch(ranges, complete = true)), batches)
        assertEquals(1, cache.loads)
        assertEquals(0, cache.stores)
    }

    @Test
    fun nonPositiveContentViewportIsRejected() = runBlocking {
        try {
            engine.paginate("text", spec(widthPx = 20)).toList()
            fail("Expected invalid content width")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }

    private fun spec(
        widthPx: Int = 320,
        fontSizeSp: Float = 18f,
        fontScale: Float = 1f,
    ) = PaginationSpec(
        widthPx = widthPx,
        heightPx = 420,
        densityDpi = 160,
        fontFamilyId = ReaderTestFont.FAMILY_ID,
        fontSizeSp = fontSizeSp,
        fontScale = fontScale,
        lineSpacingMultiplier = 1.2f,
        horizontalMarginPx = 16,
    )

    private class RecordingCache(
        private val ranges: List<PageRange>,
    ) : PaginationCache {
        var loads = 0
        var stores = 0

        override suspend fun load(key: PaginationKey, text: String): List<PageRange>? {
            loads += 1
            return ranges
        }

        override suspend fun store(
            key: PaginationKey,
            text: String,
            ranges: List<PageRange>,
        ): Boolean {
            stores += 1
            return true
        }

        override suspend fun invalidateChapter(chapterId: String) = Unit
    }
}
