package com.mkread.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FoundationTest {
    @Test
    fun packageName_isStable() {
        assertEquals("com.mkread.app", BuildConfig.APPLICATION_ID)
    }
}
