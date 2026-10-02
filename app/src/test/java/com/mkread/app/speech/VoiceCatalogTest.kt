package com.mkread.app.speech

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class VoiceCatalogTest {
    @Test
    fun presetIdsAreUniqueAndCoverEveryKokoroSpeaker() {
        val ids = VoiceCatalog.presets.map(VoiceOption::id)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(100, VoiceCatalog.presets.count { it.model == VoiceModel.KOKORO })
    }

    @Test
    fun unknownIdsFallBackToTheDefaultVoice() {
        assertEquals(VoiceCatalog.matcha, VoiceCatalog.find(null))
        assertEquals(VoiceCatalog.matcha, VoiceCatalog.find("no-such-voice"))
    }

    @Test
    fun cloneIdsResolveToZipVoice() {
        val option = VoiceCatalog.find("clone:studio.anna")
        assertEquals(VoiceModel.ZIPVOICE, option.model)
        assertEquals("studio.anna", option.cloneVoiceId)
    }

    @Test
    fun presetVoicesNeedNoReferenceAndHaveDistinctCacheIdentities() = runTest {
        val provider = CatalogVoiceProvider(InstalledVoiceProvider(File("build/no-voices")))

        val female = provider.resolve("kokoro:3", "joy").getOrThrow()
        val male = provider.resolve("kokoro:58", "joy").getOrThrow()

        assertNull(female.reference)
        assertEquals(VoiceModel.KOKORO, female.model)
        assertEquals(3, female.speakerId)
        assertEquals("neutral", female.styleId)
        assertNotEquals(female.packageSha256, male.packageSha256)
        assertEquals(VoiceModel.MATCHA, provider.builtInNeutral().getOrThrow().model)
    }
}
