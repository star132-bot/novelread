package com.mkread.app.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mkread.app.feature.library.ImportTestContentProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceImportActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val voicesRoot = File(context.filesDir, "voices")
    private val installedVoice = File(voicesRoot, VOICE_ID)
    private val selectedVoice = File(voicesRoot, "selected-voice.id")
    private val stagingRoot = File(context.cacheDir, "voice-import")

    @Before
    fun setUp() {
        installedVoice.deleteRecursively()
        selectedVoice.delete()
        stagingRoot.deleteRecursively()
        ImportTestContentProvider.clear()
    }

    @After
    fun tearDown() {
        installedVoice.deleteRecursively()
        selectedVoice.delete()
        stagingRoot.deleteRecursively()
        ImportTestContentProvider.clear()
    }

    @Test
    fun manifestExportsOnlyActionViewContentUrisWithMkVoiceMimeAndNoBroadPermissions() {
        val packageManager = context.packageManager
        val exactMatches = packageManager.queryIntentActivities(viewIntent(MIME_TYPE), 0)
            .filter { match -> match.activityInfo.packageName == context.packageName }

        assertEquals(1, exactMatches.size)
        assertEquals(VoiceImportActivity::class.java.name, exactMatches.single().activityInfo.name)
        assertTrue(exactMatches.single().activityInfo.exported)
        assertTrue(
            packageManager.queryIntentActivities(viewIntent("application/zip"), 0)
                .none { match -> match.activityInfo.packageName == context.packageName },
        )
        assertTrue(
            packageManager.queryIntentActivities(viewIntent("application/octet-stream"), 0)
                .none { match -> match.activityInfo.packageName == context.packageName },
        )

        val requested = packageManager.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty().toSet()
        assertTrue(FORBIDDEN_PERMISSIONS.none(requested::contains))
    }

    @Test
    fun actionViewShowsValidatedDetailsAndOnlyImportsAfterConfirmation() {
        val uri = ImportTestContentProvider.register("new-voice", packageBytes("New Voice"))
        val intent = Intent(context, VoiceImportActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val scenario = ActivityScenario.launch<VoiceImportActivity>(intent)
        awaitView("\u5bfc\u5165\u6b64\u97f3\u8272\uff1f")

        onView(
            withText(
                "\u540d\u79f0\uff1aNew Voice\n" +
                    "ID\uff1acom.example.activityimport\n" +
                    "\u8bed\u8a00\uff1aen\n" +
                    "\u60c5\u7eea\uff1aneutral",
            ),
        )
            .check(matches(isDisplayed()))
        assertFalse(installedVoice.exists())
        assertFalse(selectedVoice.exists())

        onView(withText("\u5bfc\u5165\u5e76\u4f7f\u7528")).perform(click())
        await {
            File(installedVoice, "manifest.json").run {
                isFile && readText(Charsets.UTF_8).contains("New Voice")
            } && scenario.state == Lifecycle.State.DESTROYED
        }

        assertEquals(VOICE_ID, selectedVoice.readText(Charsets.UTF_8))
        assertTrue(stagingRoot.listFiles().isNullOrEmpty())
        scenario.close()
    }

    @Test
    fun cancellingActionViewConfirmationLeavesVoiceLibraryAndSelectionUnchanged() {
        val uri = ImportTestContentProvider.register("cancelled-voice", packageBytes("Cancelled Voice"))
        val intent = Intent(context, VoiceImportActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val scenario = ActivityScenario.launch<VoiceImportActivity>(intent)
        awaitView("\u5bfc\u5165\u6b64\u97f3\u8272\uff1f")
        assertFalse(installedVoice.exists())
        assertFalse(selectedVoice.exists())

        onView(withText("\u53d6\u6d88")).perform(click())
        await { scenario.state == Lifecycle.State.DESTROYED }

        assertFalse(installedVoice.exists())
        assertFalse(selectedVoice.exists())
        assertTrue(stagingRoot.listFiles().isNullOrEmpty())
        scenario.close()
    }

    @Test
    fun actionViewDoesNotSilentlyReplaceAnInstalledVoiceWithTheSameId() {
        val original = File(context.cacheDir, "voice-import-original.mkvoice").apply {
            writeBytes(packageBytes("Original Voice"))
        }
        try {
            MkVoiceImporter(voicesRoot).importPackage(original, replace = true)
        } finally {
            original.delete()
        }
        val uri = ImportTestContentProvider.register("replacement-voice", packageBytes("Replacement Voice"))
        val intent = Intent(context, VoiceImportActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val scenario = ActivityScenario.launch<VoiceImportActivity>(intent)
        awaitView("\u5bfc\u5165\u6b64\u97f3\u8272\uff1f")
        onView(withText("\u5bfc\u5165\u5e76\u4f7f\u7528")).perform(click())
        await { scenario.state == Lifecycle.State.DESTROYED }

        assertTrue(
            File(installedVoice, "manifest.json")
                .readText(Charsets.UTF_8)
                .contains("Original Voice"),
        )
        assertTrue(stagingRoot.listFiles().isNullOrEmpty())
        scenario.close()
    }

    @Test
    fun explicitIntentsOutsideTheActionSchemeOrMimeContractAreRejected() {
        val uri = ImportTestContentProvider.register("rejected-voice", packageBytes("Rejected Voice"))
        val invalid = listOf(
            Intent(context, VoiceImportActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setDataAndType(uri, MIME_TYPE),
            Intent(context, VoiceImportActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/zip"),
            Intent(context, VoiceImportActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse("https://example.invalid/voice.mkvoice"), MIME_TYPE),
        )

        invalid.forEach { intent ->
            val scenario = ActivityScenario.launch<VoiceImportActivity>(intent)
            await { scenario.state == Lifecycle.State.DESTROYED }
            scenario.close()
            assertFalse(installedVoice.exists())
        }
        assertTrue(stagingRoot.listFiles().isNullOrEmpty())
    }

    @Test
    fun boundedCopyAcceptsTheLimitAndDeletesPartialOutputWhenExceeded() {
        val accepted = File(context.cacheDir, "voice-copy-accepted.partial")
        val rejected = File(context.cacheDir, "voice-copy-rejected.partial")
        try {
            val payload = byteArrayOf(1, 2, 3, 4)

            assertEquals(
                payload.size.toLong(),
                copyVoicePackageBounded(ByteArrayInputStream(payload), accepted, payload.size.toLong()),
            )
            assertArrayEquals(payload, accepted.readBytes())
            assertThrows(VoicePackageTooLargeException::class.java) {
                copyVoicePackageBounded(ByteArrayInputStream(payload), rejected, payload.size.toLong() - 1L)
            }
            assertFalse(rejected.exists())
        } finally {
            accepted.delete()
            rejected.delete()
        }
    }

    private fun viewIntent(mimeType: String) = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse("content://com.example.voices/sample.mkvoice"), mimeType)

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (!condition() && SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(25L)
        }
        assertTrue("Timed out waiting for voice import activity", condition())
    }

    private fun awaitView(text: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        var lastFailure: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                onView(withText(text)).check(matches(isDisplayed()))
                return
            } catch (failure: Throwable) {
                lastFailure = failure
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                Thread.sleep(25L)
            }
        }
        throw AssertionError("Timed out waiting for view text: $text", lastFailure)
    }

    private fun packageBytes(displayName: String): ByteArray {
        val payloads = linkedMapOf(
            "prompts/neutral.wav" to playableWaveBytes(),
            "prompts/neutral.txt" to "Authorized local voice prompt.\n".toByteArray(Charsets.UTF_8),
        )
        val checksums = payloads.toSortedMap().entries.joinToString(
            separator = ",",
            prefix = "{",
            postfix = "}\n",
        ) { (path, bytes) -> "\"$path\":\"${bytes.sha256()}\"" }.toByteArray(Charsets.UTF_8)
        val manifest = """
            {"checksums":"checksums.json","consent":{"declared":true,"statement":"Authorized for local Android import testing."},"creator":"MKread test","displayName":"$displayName","engine":"zipvoice-distill-int8-zh-en","id":"$VOICE_ID","languages":["en"],"schemaVersion":1,"styles":[{"audio":"prompts/neutral.wav","emotion":"neutral","sampleRate":24000,"transcript":"prompts/neutral.txt"}]}
        """.trimIndent().plus("\n").toByteArray(Charsets.UTF_8)

        return ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                (listOf("manifest.json" to manifest, "checksums.json" to checksums) + payloads.toList())
                    .forEach { (path, content) ->
                        val entry = ZipEntry(path)
                        if (path.endsWith(".wav")) {
                            entry.method = ZipEntry.STORED
                            entry.size = content.size.toLong()
                            entry.compressedSize = content.size.toLong()
                            entry.crc = CRC32().apply { update(content) }.value
                        }
                        zip.putNextEntry(entry)
                        zip.write(content)
                        zip.closeEntry()
                    }
            }
            bytes.toByteArray()
        }
    }

    private fun playableWaveBytes(): ByteArray = ByteArrayOutputStream().apply {
        write("RIFF".toByteArray(Charsets.US_ASCII))
        writeLittleEndian32(38)
        write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        writeLittleEndian32(16)
        writeLittleEndian16(1)
        writeLittleEndian16(1)
        writeLittleEndian32(24_000)
        writeLittleEndian32(48_000)
        writeLittleEndian16(2)
        writeLittleEndian16(16)
        write("data".toByteArray(Charsets.US_ASCII))
        writeLittleEndian32(2)
        writeLittleEndian16(0)
    }.toByteArray()

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun ByteArrayOutputStream.writeLittleEndian16(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
    }

    private fun ByteArrayOutputStream.writeLittleEndian32(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
        write(value ushr 16 and 0xff)
        write(value ushr 24 and 0xff)
    }

    private companion object {
        const val MIME_TYPE = "application/vnd.mkread.voice"
        const val VOICE_ID = "com.example.activityimport"
        val FORBIDDEN_PERMISSIONS = setOf(
            Manifest.permission.INTERNET,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.MANAGE_EXTERNAL_STORAGE,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
    }
}
