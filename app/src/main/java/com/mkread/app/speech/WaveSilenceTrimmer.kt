package com.mkread.app.speech

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.max
import kotlin.math.sqrt

object WaveSilenceTrimmer {
    private const val WINDOW_MILLIS = 20
    private const val LEADING_MARGIN_MILLIS = 40
    private const val TRAILING_MARGIN_MILLIS = 80
    private const val MINIMUM_RMS = 400.0
    private const val PEAK_RMS_FRACTION = 0.04

    internal fun findSpeechBounds(samples: ShortArray, sampleRate: Int): IntRange {
        require(sampleRate > 0) { "Sample rate must be positive" }
        if (samples.isEmpty()) return IntRange.EMPTY
        val windowSize = max(1, sampleRate * WINDOW_MILLIS / 1_000)
        val windowCount = (samples.size + windowSize - 1) / windowSize
        val levels = DoubleArray(windowCount) { window ->
            val start = window * windowSize
            val end = minOf(samples.size, start + windowSize)
            var sumSquares = 0.0
            for (index in start until end) {
                val sample = samples[index].toDouble()
                sumSquares += sample * sample
            }
            sqrt(sumSquares / (end - start))
        }
        val threshold = max(MINIMUM_RMS, levels.maxOrNull().orEmpty() * PEAK_RMS_FRACTION)
        val firstWindow = levels.indexOfFirst { it >= threshold }
        if (firstWindow < 0) return samples.indices
        val lastWindow = levels.indexOfLast { it >= threshold }
        val leadingMargin = sampleRate * LEADING_MARGIN_MILLIS / 1_000
        val trailingMargin = sampleRate * TRAILING_MARGIN_MILLIS / 1_000
        val start = (firstWindow * windowSize - leadingMargin).coerceAtLeast(0)
        val endExclusive = ((lastWindow + 1) * windowSize + trailingMargin).coerceAtMost(samples.size)
        return start until endExclusive
    }

    fun trimInPlace(file: File): Boolean {
        val playable = WaveValidator.inspectPlayable(file)
        val samples = ShortArray(playable.sampleCount)
        RandomAccessFile(file, "r").use { wave ->
            wave.seek(playable.dataOffset)
            for (index in samples.indices) {
                val low = wave.readUnsignedByte()
                val high = wave.readUnsignedByte()
                samples[index] = (low or (high shl 8)).toShort()
            }
        }
        val bounds = findSpeechBounds(samples, playable.sampleRate)
        if (bounds.isEmpty() || bounds.first == 0 && bounds.last == samples.lastIndex) return false

        val trimmed = File(file.parentFile, "${file.name}.trimmed")
        try {
            BufferedOutputStream(FileOutputStream(trimmed)).use { output ->
                val sampleCount = bounds.last - bounds.first + 1
                output.writeAscii("RIFF")
                output.writeLittleEndian32(36 + sampleCount * 2)
                output.writeAscii("WAVE")
                output.writeAscii("fmt ")
                output.writeLittleEndian32(16)
                output.writeLittleEndian16(1)
                output.writeLittleEndian16(1)
                output.writeLittleEndian32(playable.sampleRate)
                output.writeLittleEndian32(playable.sampleRate * 2)
                output.writeLittleEndian16(2)
                output.writeLittleEndian16(16)
                output.writeAscii("data")
                output.writeLittleEndian32(sampleCount * 2)
                for (index in bounds) output.writeLittleEndian16(samples[index].toInt())
            }
            WaveValidator.requirePlayable(trimmed)
            Files.move(
                trimmed.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            return true
        } finally {
            trimmed.delete()
        }
    }

    private fun Double?.orEmpty(): Double = this ?: 0.0

    private fun OutputStream.writeAscii(value: String) = write(value.toByteArray(Charsets.US_ASCII))

    private fun OutputStream.writeLittleEndian16(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
    }

    private fun OutputStream.writeLittleEndian32(value: Int) {
        writeLittleEndian16(value)
        writeLittleEndian16(value ushr 16)
    }
}
