package com.mkread.app.speech

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstalledVoiceLibraryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun listsInstalledVoicesAndPersistsSelection() = runTest {
        val root = temporaryFolder.root
        val voice = root.resolve("voices/com.example.reader").apply { mkdirs() }
        voice.resolve("manifest.json").writeText(
            """
            {
              "id":"com.example.reader",
              "displayName":"云岚",
              "languages":["zh-CN"],
              "styles":[{"emotion":"neutral"},{"emotion":"joy"}]
            }
            """.trimIndent(),
        )
        val provider = InstalledVoiceProvider(root)

        assertEquals(
            listOf(InstalledVoiceSummary("com.example.reader", "云岚", listOf("zh-CN"), listOf("neutral", "joy"))),
            provider.installedVoices(),
        )
        InstalledVoiceProvider.select(root, "com.example.reader")
        assertEquals("com.example.reader", provider.selectedVoiceId())
    }
}
