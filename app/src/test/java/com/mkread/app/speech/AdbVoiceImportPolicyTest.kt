package com.mkread.app.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbVoiceImportPolicyTest {
    @Test
    fun defaultImportDoesNotReplaceAnExistingVoice() {
        assertFalse(replaceExistingVoiceForMethod("import"))
    }

    @Test
    fun replacementRequiresTheExplicitProviderMethod() {
        assertTrue(replaceExistingVoiceForMethod("import_replace"))
        assertFalse(replaceExistingVoiceForMethod("unexpected"))
    }
}
