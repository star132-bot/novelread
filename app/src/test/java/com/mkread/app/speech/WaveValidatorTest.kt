package com.mkread.app.speech

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WaveValidatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun missingFile_isRejected() {
        assertInvalid(File(temporaryFolder.root, "missing.wav"))
    }

    @Test
    fun headerShorterThan44Bytes_isRejected() {
        val file = temporaryFolder.newFile("short.wav").apply {
            writeBytes(ByteArray(43))
        }

        assertInvalid(file)
    }

    @Test
    fun nonRiffData_isRejected() {
        val bytes = waveBytes(sampleData = byteArrayOf(0, 0))
        bytes[0] = 'N'.code.toByte()
        val file = temporaryFolder.newFile("not-riff.wav").apply { writeBytes(bytes) }

        assertInvalid(file)
    }

    @Test
    fun zeroSampleData_isRejected() {
        val file = temporaryFolder.newFile("empty.wav").apply {
            writeBytes(waveBytes(sampleData = byteArrayOf()))
        }

        assertInvalid(file)
    }

    @Test
    fun wrongSampleRate_isRejected() {
        val file = temporaryFolder.newFile("wrong-rate.wav").apply {
            writeBytes(waveBytes(sampleRate = 16_000, sampleData = byteArrayOf(0, 0)))
        }

        assertInvalid(file)
    }

    @Test
    fun mono24KhzPcm16_withOddSizedMetadataChunk_returnsSampleCount() {
        val samples = shortArrayOf(0, 1, -1, Short.MAX_VALUE)
        val sampleData = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            sampleData[index * 2] = sample.toInt().toByte()
            sampleData[index * 2 + 1] = (sample.toInt() ushr 8).toByte()
        }
        val file = temporaryFolder.newFile("valid.wav").apply {
            writeBytes(waveBytes(sampleData = sampleData, includeOddJunkChunk = true))
        }

        assertEquals(samples.size, WaveValidator.requirePlayable(file))
    }

    private fun assertInvalid(file: File) {
        try {
            WaveValidator.requirePlayable(file)
            fail("Expected ${file.name} to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }

    private fun waveBytes(
        sampleRate: Int = 24_000,
        sampleData: ByteArray,
        includeOddJunkChunk: Boolean = false,
    ): ByteArray {
        val junkSize = if (includeOddJunkChunk) 10 else 0
        val riffSize = 4 + junkSize + 24 + 8 + sampleData.size
        return ByteArrayOutputStream().apply {
            writeAscii("RIFF")
            writeLittleEndian32(riffSize)
            writeAscii("WAVE")
            if (includeOddJunkChunk) {
                writeAscii("JUNK")
                writeLittleEndian32(1)
                write(0x7f)
                write(0)
            }
            writeAscii("fmt ")
            writeLittleEndian32(16)
            writeLittleEndian16(1)
            writeLittleEndian16(1)
            writeLittleEndian32(sampleRate)
            writeLittleEndian32(sampleRate * 2)
            writeLittleEndian16(2)
            writeLittleEndian16(16)
            writeAscii("data")
            writeLittleEndian32(sampleData.size)
            write(sampleData)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
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
