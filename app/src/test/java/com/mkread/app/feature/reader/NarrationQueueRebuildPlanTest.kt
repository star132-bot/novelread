package com.mkread.app.feature.reader

import com.mkread.app.playback.SentenceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NarrationQueueRebuildPlanTest {
    @Test
    fun preservesCurrentSentenceAndRemovesOnlyTheQueuedTail() {
        val current = sentence(bookId = "book", index = 2)

        val plan = planEmotionQueueRebuild(
            expectedBookId = "book",
            currentSentence = current,
            currentIndex = 2,
            mediaItemCount = 5,
        )

        assertEquals(current, plan?.resumeAfter)
        assertEquals(3, plan?.removeFromIndex)
    }

    @Test
    fun resetsInsteadOfPreservingAnUnrelatedOrMissingQueue() {
        assertNull(
            planEmotionQueueRebuild(
                expectedBookId = "book",
                currentSentence = sentence(bookId = "other", index = 0),
                currentIndex = 0,
                mediaItemCount = 1,
            ),
        )
        assertNull(
            planEmotionQueueRebuild(
                expectedBookId = "book",
                currentSentence = null,
                currentIndex = -1,
                mediaItemCount = 0,
            ),
        )
    }

    private fun sentence(bookId: String, index: Int) = SentenceId(
        bookId = bookId,
        chapterId = "chapter",
        index = index,
        start = index * 10,
        end = index * 10 + 9,
    )
}
