package com.mkread.app.speech

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SherpaWaveReaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun pcm16Samples_areNormalizedAfterOddSizedMetadataChunk() {
        val samples = shortArrayOf(Short.MIN_VALUE, -16_384, 0, 16_384, Short.MAX_VALUE)
        val file = temporaryFolder.newFile("reference.wav").apply {
            writeBytes(waveBytes(samples, sampleRate = 24_000))
        }

        val wave = SherpaWaveReader.read(file)

        assertEquals(24_000, wave.sampleRate)
        assertEquals(samples.size, wave.samples.size)
        assertEquals(-1f, wave.samples[0], 0f)
        assertEquals(-0.5f, wave.samples[1], 0f)
        assertEquals(0f, wave.samples[2], 0f)
        assertEquals(0.5f, wave.samples[3], 0f)
        assertEquals(Short.MAX_VALUE / 32_768f, wave.samples[4], 0f)
    }

    @Test
    fun non24KhzReference_isRejectedByWaveValidator() {
        val file = temporaryFolder.newFile("wrong-rate.wav").apply {
            writeBytes(waveBytes(shortArrayOf(0), sampleRate = 16_000))
        }

        try {
            SherpaWaveReader.read(file)
            fail("Expected wrong-rate reference to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }

    private fun waveBytes(samples: ShortArray, sampleRate: Int): ByteArray {
        val dataSize = samples.size * 2
        val oddJunkChunkSizeWithPadding = 10
        val riffSize = 4 + oddJunkChunkSizeWithPadding + 24 + 8 + dataSize
        return ByteArrayOutputStream().apply {
            writeAscii("RIFF")
            writeLittleEndian32(riffSize)
            writeAscii("WAVE")
            writeAscii("JUNK")
            writeLittleEndian32(1)
            write(0x42)
            write(0)
            writeAscii("fmt ")
            writeLittleEndian32(16)
            writeLittleEndian16(1)
            writeLittleEndian16(1)
            writeLittleEndian32(sampleRate)
            writeLittleEndian32(sampleRate * 2)
            writeLittleEndian16(2)
            writeLittleEndian16(16)
            writeAscii("data")
            writeLittleEndian32(dataSize)
            samples.forEach { sample ->
                writeLittleEndian16(sample.toInt())
            }
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
