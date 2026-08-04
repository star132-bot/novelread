package com.mkread.app.speech

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MkVoiceImporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun validDesktopPackage_isInstalledAsVoiceReference() {
        val source = packageFile("voice.mkvoice")
        val voicesRoot = temporaryFolder.newFolder("voices")

        val imported = MkVoiceImporter(voicesRoot).importPackage(source)

        assertEquals("com.example.storyteller", imported.id)
        assertEquals("故事讲述者", imported.displayName)
        assertEquals(listOf("zh-CN"), imported.languages)
        assertEquals("这是一段用于测试的参考声音。", imported.reference.transcript)
        assertEquals(24_000, imported.reference.sampleRate)
        assertTrue(imported.reference.audioFile.isFile)
        assertTrue(imported.manifestFile.isFile)
        assertEquals(2, WaveValidator.requirePlayable(imported.reference.audioFile))
    }

    @Test
    fun checksumMismatch_isRejectedWithoutInstallingPartialVoice() {
        val source = packageFile(
            name = "tampered.mkvoice",
            transcript = "内容已经被篡改。",
            declaredTranscript = "声明时的内容。",
        )
        val voicesRoot = temporaryFolder.newFolder("voices")

        assertImportFailure("checksum_mismatch") {
            MkVoiceImporter(voicesRoot).importPackage(source)
        }

        assertFalse(File(voicesRoot, "com.example.storyteller").exists())
    }

    @Test
    fun existingVoice_requiresExplicitReplace() {
        val source = packageFile("voice.mkvoice")
        val voicesRoot = temporaryFolder.newFolder("voices")
        val importer = MkVoiceImporter(voicesRoot)
        importer.importPackage(source)

        assertImportFailure("destination_exists") {
            importer.importPackage(source)
        }

        val replaced = importer.importPackage(source, replace = true)
        assertEquals("com.example.storyteller", replaced.id)
    }

    @Test
    fun allV1Styles_areValidatedAndInstalledWhileNeutralBuildsReference() {
        val source = packageFile("styles.mkvoice", includeJoy = true)

        val imported = MkVoiceImporter(temporaryFolder.newFolder("voices")).importPackage(source)

        assertEquals(listOf("neutral", "joy"), imported.styles.map { it.emotion })
        assertTrue(imported.styles.all { it.audioFile.isFile && it.transcript.isNotBlank() })
        assertEquals("这是一段用于测试的参考声音。", imported.reference.transcript)
    }

    @Test
    fun orphanedBackup_isRecoveredWhenImporterStarts() {
        val source = packageFile("voice.mkvoice")
        val voicesRoot = temporaryFolder.newFolder("voices")
        MkVoiceImporter(voicesRoot).importPackage(source)
        val destination = File(voicesRoot, "com.example.storyteller")
        val backup = File(voicesRoot, ".com.example.storyteller.backup")
        Files.move(destination.toPath(), backup.toPath())
        File(voicesRoot, ".com.example.storyteller.staging").mkdirs()

        MkVoiceImporter(voicesRoot)

        assertTrue(File(destination, "prompts/neutral.wav").isFile)
        assertFalse(backup.exists())
        assertFalse(File(voicesRoot, ".com.example.storyteller.staging").exists())
    }

    @Test
    fun concurrentImports_withoutReplace_publishExactlyOnce() {
        val source = packageFile("voice.mkvoice")
        val voicesRoot = temporaryFolder.newFolder("voices")
        val start = CountDownLatch(1)
        val outcomes = Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = List(2) {
                executor.submit {
                    start.await()
                    try {
                        MkVoiceImporter(voicesRoot).importPackage(source)
                        outcomes += "success"
                    } catch (failure: MkVoiceImportException) {
                        outcomes += failure.code
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, outcomes.count { it == "success" })
        assertEquals(1, outcomes.count { it == "destination_exists" })
    }

    @Test
    fun unexpectedArchiveEntry_isRejected() {
        val source = packageFile("unexpected.mkvoice", extraEntry = "../outside.txt")

        assertImportFailure("unsafe_entry") {
            MkVoiceImporter(temporaryFolder.newFolder("voices")).importPackage(source)
        }
    }

    @Test
    fun duplicateManifestField_isRejected() {
        val source = packageFile(
            name = "duplicate-field.mkvoice",
            transformManifest = { manifest ->
                manifest.replace(
                    "\"engine\":\"zipvoice-distill-int8-zh-en\"",
                    "\"engine\":\"unsupported\",\"engine\":\"zipvoice-distill-int8-zh-en\"",
                )
            },
        )

        assertImportFailure("duplicate_field") {
            MkVoiceImporter(temporaryFolder.newFolder("voices")).importPackage(source)
        }
    }

    @Test
    fun promptLongerThan60Seconds_isRejectedWithoutPartialInstall() {
        val source = packageFile(
            name = "too-long.mkvoice",
            audio = waveBytes(sampleCount = 60 * 24_000 + 1),
        )
        val voicesRoot = temporaryFolder.newFolder("voices")

        assertImportFailure("invalid_prompt_audio") {
            MkVoiceImporter(voicesRoot).importPackage(source)
        }

        assertFalse(File(voicesRoot, "com.example.storyteller").exists())
    }

    @Test
    fun packageProducedByDesktopAcceptanceRun_isImportableWhenProvided() {
        val packagePath = System.getenv("MKVOICE_ACCEPTANCE_PACKAGE")
        org.junit.Assume.assumeTrue("MKVOICE_ACCEPTANCE_PACKAGE is required for this acceptance test", packagePath != null)
        val source = File(requireNotNull(packagePath))
        assertTrue("Acceptance package does not exist: $source", source.isFile)

        val imported = MkVoiceImporter(temporaryFolder.newFolder("voices")).importPackage(source)

        assertEquals(24_000, imported.reference.sampleRate)
        assertTrue(imported.reference.audioFile.isFile)
        assertTrue(imported.reference.transcript.isNotBlank())
    }

    private fun packageFile(
        name: String,
        transcript: String = "这是一段用于测试的参考声音。",
        declaredTranscript: String = transcript,
        extraEntry: String? = null,
        audio: ByteArray = waveBytes(),
        transformManifest: (String) -> String = { it },
        includeJoy: Boolean = false,
    ): File {
        val transcriptBytes = "$transcript\n".toByteArray(Charsets.UTF_8)
        val declaredTranscriptBytes = "$declaredTranscript\n".toByteArray(Charsets.UTF_8)
        val payloads = linkedMapOf(
            "prompts/neutral.wav" to audio,
            "prompts/neutral.txt" to transcriptBytes,
        )
        if (includeJoy) {
            payloads["prompts/joy.wav"] = audio
            payloads["prompts/joy.txt"] = "这是喜悦风格的参考文本。\n".toByteArray(Charsets.UTF_8)
        }
        val checksums = payloads.toSortedMap().entries.joinToString(",", prefix = "{", postfix = "}\n") { (path, bytes) ->
            val content = if (path == "prompts/neutral.txt") declaredTranscriptBytes else bytes
            "\"$path\":\"${content.sha256()}\""
        }.toByteArray(Charsets.UTF_8)
        val joyStyle = if (includeJoy) {
            ",{" +
                "\"audio\":\"prompts/joy.wav\",\"emotion\":\"joy\",\"sampleRate\":24000," +
                "\"transcript\":\"prompts/joy.txt\"}"
        } else {
            ""
        }
        val manifest = transformManifest("""
            {"checksums":"checksums.json","consent":{"declared":true,"statement":"我确认已获得该声音的合法授权并同意在本机使用。"},"creator":"MKread test","displayName":"故事讲述者","engine":"zipvoice-distill-int8-zh-en","id":"com.example.storyteller","languages":["zh-CN"],"schemaVersion":1,"styles":[{"audio":"prompts/neutral.wav","emotion":"neutral","sampleRate":24000,"transcript":"prompts/neutral.txt"}$joyStyle]}
        """.trimIndent()).plus("\n").toByteArray(Charsets.UTF_8)
        val file = temporaryFolder.newFile(name)
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            (listOf("manifest.json" to manifest, "checksums.json" to checksums) + payloads.toList()).forEach { (path, bytes) ->
                val entry = ZipEntry(path)
                if (path == "prompts/neutral.wav") {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            if (extraEntry != null) {
                zip.putNextEntry(ZipEntry(extraEntry))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
        }
        return file
    }

    private fun assertImportFailure(code: String, action: () -> Unit) {
        try {
            action()
            fail("Expected MKvoice import to fail with $code")
        } catch (failure: MkVoiceImportException) {
            assertEquals(code, failure.code)
        }
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun waveBytes(sampleCount: Int = 2): ByteArray {
        val samples = ByteArray(sampleCount * 2)
        if (samples.size >= 4) samples[2] = 1
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            writeLittleEndian32(36 + samples.size)
            write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            writeLittleEndian32(16)
            writeLittleEndian16(1)
            writeLittleEndian16(1)
            writeLittleEndian32(24_000)
            writeLittleEndian32(48_000)
            writeLittleEndian16(2)
            writeLittleEndian16(16)
            write("data".toByteArray(Charsets.US_ASCII))
            writeLittleEndian32(samples.size)
            write(samples)
        }.toByteArray()
    }

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
}
