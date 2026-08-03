package com.mkread.app.speech

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

object WaveValidator {
    private const val MINIMUM_WAVE_BYTES = 44L
    private const val EXPECTED_SAMPLE_RATE = 24_000L
    private const val EXPECTED_CHANNELS = 1
    private const val EXPECTED_BITS_PER_SAMPLE = 16
    private const val PCM_FORMAT = 1

    internal data class PlayableWave(
        val dataOffset: Long,
        val sampleRate: Int,
        val sampleCount: Int,
    )

    fun requirePlayable(file: File): Int = inspectPlayable(file).sampleCount

    internal fun inspectPlayable(file: File): PlayableWave {
        require(file.isFile) { "WAV file does not exist: ${file.path}" }
        require(file.length() >= MINIMUM_WAVE_BYTES) { "WAV header is shorter than 44 bytes" }

        return RandomAccessFile(file, "r").use { wave ->
            require(wave.readFourCc() == "RIFF") { "WAV must start with RIFF" }
            val riffSize = wave.readLittleEndianUnsignedInt()
            require(wave.readFourCc() == "WAVE") { "RIFF payload must be WAVE" }

            val riffEnd = 8L + riffSize
            require(riffEnd <= wave.length()) { "RIFF chunk extends past the file" }

            var formatFound = false
            var dataSize: Long? = null
            var dataOffset: Long? = null
            while (wave.filePointer + 8L <= riffEnd) {
                val chunkId = wave.readFourCc()
                val chunkSize = wave.readLittleEndianUnsignedInt()
                val chunkStart = wave.filePointer
                val chunkEnd = chunkStart + chunkSize
                require(chunkEnd >= chunkStart && chunkEnd <= riffEnd) {
                    "$chunkId chunk extends past the RIFF payload"
                }

                when (chunkId) {
                    "fmt " -> {
                        require(chunkSize >= 16L) { "fmt chunk is shorter than PCM metadata" }
                        val audioFormat = wave.readLittleEndianUnsignedShort()
                        val channels = wave.readLittleEndianUnsignedShort()
                        val sampleRate = wave.readLittleEndianUnsignedInt()
                        wave.readLittleEndianUnsignedInt() // Byte rate.
                        val blockAlign = wave.readLittleEndianUnsignedShort()
                        val bitsPerSample = wave.readLittleEndianUnsignedShort()

                        require(audioFormat == PCM_FORMAT) { "WAV must use PCM format 1" }
                        require(channels == EXPECTED_CHANNELS) { "WAV must be mono" }
                        require(sampleRate == EXPECTED_SAMPLE_RATE) { "WAV sample rate must be 24000 Hz" }
                        require(bitsPerSample == EXPECTED_BITS_PER_SAMPLE) { "WAV must use 16-bit samples" }
                        require(blockAlign == 2) { "WAV block alignment must be 2 bytes" }
                        formatFound = true
                    }

                    "data" -> {
                        require(dataSize == null) { "WAV must contain only one data chunk" }
                        dataSize = chunkSize
                        dataOffset = chunkStart
                    }
                }

                val paddedChunkEnd = chunkEnd + (chunkSize and 1L)
                require(paddedChunkEnd <= riffEnd) { "$chunkId padding extends past the RIFF payload" }
                wave.seek(paddedChunkEnd)
            }

            require(formatFound) { "WAV is missing a fmt chunk" }
            val playableBytes = requireNotNull(dataSize) { "WAV is missing a data chunk" }
            require(playableBytes > 0L) { "WAV data chunk is empty" }
            require(playableBytes % 2L == 0L) { "WAV data is not aligned to PCM16 samples" }
            val sampleCount = playableBytes / 2L
            require(sampleCount <= Int.MAX_VALUE) { "WAV contains too many samples" }
            PlayableWave(
                dataOffset = requireNotNull(dataOffset),
                sampleRate = EXPECTED_SAMPLE_RATE.toInt(),
                sampleCount = sampleCount.toInt(),
            )
        }
    }

    private fun RandomAccessFile.readFourCc(): String {
        val bytes = ByteArray(4)
        readFully(bytes)
        return String(bytes, StandardCharsets.US_ASCII)
    }

    private fun RandomAccessFile.readLittleEndianUnsignedShort(): Int =
        readUnsignedByte() or (readUnsignedByte() shl 8)

    private fun RandomAccessFile.readLittleEndianUnsignedInt(): Long =
        readUnsignedByte().toLong() or
            (readUnsignedByte().toLong() shl 8) or
            (readUnsignedByte().toLong() shl 16) or
            (readUnsignedByte().toLong() shl 24)
}
