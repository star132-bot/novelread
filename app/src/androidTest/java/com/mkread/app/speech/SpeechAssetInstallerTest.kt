package com.mkread.app.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechAssetInstallerTest {
    private val filesDir: File
        get() = InstrumentationRegistry.getInstrumentation().targetContext.filesDir

    @Before
    fun clearPreviousInstall() {
        listOf("models", "voices", "speech-staging").forEach { name ->
            File(filesDir, name).deleteRecursively()
        }
        File(filesDir, "speech-assets.complete.json").delete()
    }

    @Test
    fun install_isIdempotent_repairsOneFile_andCancellationPreservesCompleteInstall() = runBlocking {
        val original = mapOf(
            MODEL_PATH to "encoder-v1".toByteArray(),
            PROMPT_PATH to "prompt-v1".toByteArray(),
        )
        val source = MapAssetSource(original.withManifest())
        val installer = SpeechAssetInstaller(filesDir, source)

        val first = installer.install()

        assertEquals(setOf(MODEL_PATH, PROMPT_PATH), first.replacedPaths.toSet())
        assertArrayEquals(original.getValue(MODEL_PATH), installed(MODEL_PATH).readBytes())
        assertArrayEquals(original.getValue(PROMPT_PATH), installed(PROMPT_PATH).readBytes())

        val second = installer.install()
        assertTrue(second.replacedPaths.isEmpty())

        installed(PROMPT_PATH).writeText("corrupt")
        val repaired = installer.install()
        assertEquals(listOf(PROMPT_PATH), repaired.replacedPaths)
        assertArrayEquals(original.getValue(MODEL_PATH), installed(MODEL_PATH).readBytes())
        assertArrayEquals(original.getValue(PROMPT_PATH), installed(PROMPT_PATH).readBytes())

        val completionBeforeCancellation = completionFile().readBytes()
        val replacement = original + (MODEL_PATH to ByteArray(70_000) { 7 })
        val cancellingSource = MapAssetSource(
            files = replacement.withManifest(),
            cancelDuringPath = MODEL_PATH,
        )

        expectCancellation {
            SpeechAssetInstaller(filesDir, cancellingSource).install()
        }

        assertArrayEquals(original.getValue(MODEL_PATH), installed(MODEL_PATH).readBytes())
        assertArrayEquals(original.getValue(PROMPT_PATH), installed(PROMPT_PATH).readBytes())
        assertArrayEquals(completionBeforeCancellation, completionFile().readBytes())
        assertTrue(File(filesDir, "speech-staging").listFiles().isNullOrEmpty())
    }

    @Test
    fun missingManifest_isRejected() = runBlocking {
        val source = MapAssetSource(mapOf(MODEL_PATH to "model".toByteArray()))

        expectFailure {
            SpeechAssetInstaller(filesDir, source).install()
        }

        assertTrue(installed(MODEL_PATH).notExists())
    }

    @Test
    fun assetThatDoesNotMatchManifest_isRejectedWithoutCommit() = runBlocking {
        val declared = mapOf(MODEL_PATH to "expected".toByteArray())
        val files = declared.withManifest() + (MODEL_PATH to "different".toByteArray())

        expectFailure {
            SpeechAssetInstaller(filesDir, MapAssetSource(files)).install()
        }

        assertTrue(installed(MODEL_PATH).notExists())
        assertTrue(completionFile().notExists())
    }

    private fun installed(relativePath: String) = File(filesDir, relativePath)

    private fun completionFile() = File(filesDir, "speech-assets.complete.json")

    private fun File.notExists() = !exists()

    private fun Map<String, ByteArray>.withManifest(): Map<String, ByteArray> {
        val records = entries.sortedBy { it.key }.joinToString(",") { (path, bytes) ->
            """{"path":"$path","size":${bytes.size},"sha256":"${bytes.sha256()}"}"""
        }
        val manifest = """{"schemaVersion":1,"files":[$records]}""".toByteArray()
        return this + ("speech-assets.json" to manifest)
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private suspend fun expectCancellation(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected installation cancellation")
        } catch (_: CancellationException) {
            // Expected cancellation.
        }
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected installation failure")
        } catch (_: CancellationException) {
            throw AssertionError("Expected validation failure, not cancellation")
        } catch (_: Exception) {
            // Expected validation failure.
        }
    }

    private class MapAssetSource(
        private val files: Map<String, ByteArray>,
        private val cancelDuringPath: String? = null,
    ) : SpeechAssetSource {
        override fun open(relativePath: String): InputStream {
            val bytes = files[relativePath] ?: throw FileNotFoundException(relativePath)
            return if (relativePath == cancelDuringPath) {
                CancellingInputStream(bytes)
            } else {
                ByteArrayInputStream(bytes)
            }
        }
    }

    private class CancellingInputStream(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        private var reads = 0

        override fun read(): Int = error("Bulk reads are required")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (reads++ > 0) {
                throw CancellationException("test cancellation")
            }
            return delegate.read(buffer, offset, length)
        }
    }

    private companion object {
        const val MODEL_PATH = "models/zipvoice/encoder.int8.onnx"
        const val PROMPT_PATH = "voices/builtin-dev/prompts/neutral.wav"
    }
}
