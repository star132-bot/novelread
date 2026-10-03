package com.mkread.app.speech

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun listsEveryVoiceIdTheImporterAccepts() = runTest {
        val root = temporaryFolder.root
        for (id in listOf("com.example.my-voice", "cn.mkread.voice-")) {
            root.resolve("voices/$id").apply { mkdirs() }.resolve("manifest.json").writeText(
                """{"id":"$id","displayName":"$id","languages":["zh-CN"],"styles":[{"emotion":"neutral"}]}""",
            )
        }

        assertEquals(
            listOf("cn.mkread.voice-", "com.example.my-voice"),
            InstalledVoiceProvider(root).installedVoices().map { it.id },
        )
    }

    @Test
    fun legacySelectionIsCarriedOverOnceAndOnlyForInstalledVoices() {
        val root = temporaryFolder.root
        root.resolve("voices/com.example.reader").mkdirs()
        InstalledVoiceProvider.select(root, "com.example.reader")

        assertEquals("com.example.reader", InstalledVoiceProvider.takeLegacySelection(root))
        assertNull("the legacy file is consumed", InstalledVoiceProvider.takeLegacySelection(root))

        InstalledVoiceProvider.select(root, InstalledVoiceProvider.BUILT_IN_VOICE_ID)
        assertNull("the bundled sample voice is not carried over", InstalledVoiceProvider.takeLegacySelection(root))

        root.resolve("voices/${InstalledVoiceProvider.SELECTION_FILE_NAME}").writeText("com.example.removed")
        assertNull("a voice that is no longer installed is not carried over", InstalledVoiceProvider.takeLegacySelection(root))
    }
}
