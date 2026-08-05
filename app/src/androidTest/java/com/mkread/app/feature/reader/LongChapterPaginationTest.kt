package com.mkread.app.feature.reader

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LongChapterPaginationTest {
    @Test(timeout = 60_000L)
    fun firstPagePrecedesCompleteNonOverlappingOffsetCoverage() = runBlocking {
        val chapter = deterministicLongChapter()
        val engine = AndroidPaginationEngine(
            typefaceResolver = { ReaderTestFont.typeface },
        )
        val startedAt = SystemClock.elapsedRealtimeNanos()
        var firstBatch: PaginationBatch? = null
        var finalBatch: PaginationBatch? = null
        var firstPageAt = 0L
        var completedAt = 0L
        var batchCount = 0

        withTimeout(60_000L) {
            engine.paginate(chapter, paginationSpec()).collect { batch ->
                batchCount += 1
                if (firstBatch == null) {
                    firstBatch = batch
                    firstPageAt = SystemClock.elapsedRealtimeNanos()
                }
                if (batch.complete) {
                    finalBatch = batch
                    completedAt = SystemClock.elapsedRealtimeNanos()
                }
            }
        }

        assertNotNull("Pagination must emit a first-page batch", firstBatch)
        assertNotNull("Pagination must emit a complete batch", finalBatch)
        val first = checkNotNull(firstBatch)
        val complete = checkNotNull(finalBatch)

        assertFalse("The first available page must precede full pagination", first.complete)
        assertTrue("The first batch must expose readable content", first.ranges.isNotEmpty())
        assertTrue("A long chapter must emit more than one batch", batchCount > 1)
        assertTrue(
            "Full pagination must add pages after the first batch",
            complete.ranges.size > first.ranges.size,
        )
        assertEquals(first.ranges, complete.ranges.take(first.ranges.size))
        assertTrue(complete.complete)
        assertContiguousCoverage(chapter, complete.ranges)
        assertTrue("First-page timing must precede completion timing", firstPageAt <= completedAt)

        val firstPageMillis = elapsedMillis(startedAt, firstPageAt)
        val fullPaginationMillis = elapsedMillis(startedAt, completedAt)
        val metrics =
            "long-pagination firstPageMs=$firstPageMillis " +
                "fullPaginationMs=$fullPaginationMillis pages=${complete.ranges.size} " +
                "characters=${chapter.length} batches=$batchCount"
        Log.i(LOG_TAG, metrics)
        println(metrics)
    }

    private fun assertContiguousCoverage(text: String, ranges: List<PageRange>) {
        assertTrue("A non-empty chapter must contain pages", ranges.isNotEmpty())
        var expectedStart = 0
        ranges.forEachIndexed { expectedIndex, range ->
            assertEquals("Page indexes must be stable", expectedIndex, range.index)
            assertEquals("Pages must have no gaps or overlap", expectedStart, range.start)
            assertTrue("Every page must advance", range.endExclusive > range.start)
            assertTrue("Page offsets must remain inside the chapter", range.endExclusive <= text.length)
            expectedStart = range.endExclusive
        }
        assertEquals("The final page must reach the chapter end", text.length, expectedStart)
    }

    private fun deterministicLongChapter(): String = buildString {
        repeat(PARAGRAPH_COUNT) { paragraph ->
            append("Paragraph ")
            append(paragraph + 1)
            append(": Every saved offset remains readable while pagination continues. ")
            append("Dialogue ")
            append(paragraph % 17)
            append(" keeps punctuation, e\u0301, CRLF, and emoji \uD83D\uDE80 intact.")
            append("\r\n")
        }
    }

    private fun paginationSpec() = PaginationSpec(
        widthPx = 360,
        heightPx = 540,
        densityDpi = 160,
        fontFamilyId = ReaderTestFont.FAMILY_ID,
        fontSizeSp = 18f,
        lineSpacingMultiplier = 1.2f,
        horizontalMarginPx = 16,
    )

    private fun elapsedMillis(startedAt: Long, endedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(endedAt - startedAt)

    private companion object {
        const val LOG_TAG = "MKreadPaginationGate"
        const val PARAGRAPH_COUNT = 3_000
    }
}
