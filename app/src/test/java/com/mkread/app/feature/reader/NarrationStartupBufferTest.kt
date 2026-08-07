package com.mkread.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NarrationStartupBufferTest {
    @Test
    fun releasesOneOrderedQueueWhenTargetSizeIsReached() {
        val buffer = NarrationStartupBuffer<String>(targetSize = 3)

        assertNull(buffer.add("one"))
        assertNull(buffer.add("two"))
        assertEquals(listOf("one", "two", "three"), buffer.add("three"))
        assertEquals(emptyList<String>(), buffer.drain())
    }

    @Test
    fun drainReleasesShortFinalQueue() {
        val buffer = NarrationStartupBuffer<String>(targetSize = 3)
        buffer.add("one")
        buffer.add("two")

        assertEquals(listOf("one", "two"), buffer.drain())
    }
}
