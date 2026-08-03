package com.mkread.app.speech

import com.k2fsa.sherpa.onnx.WaveData
import java.io.File
import java.io.RandomAccessFile

object SherpaWaveReader {
    private const val PCM16_SCALE = 32_768f

    fun read(file: File): WaveData {
        val playable = WaveValidator.inspectPlayable(file)
        val samples = FloatArray(playable.sampleCount)
        RandomAccessFile(file, "r").use { wave ->
            wave.seek(playable.dataOffset)
            for (index in samples.indices) {
                val low = wave.readUnsignedByte()
                val high = wave.readUnsignedByte()
                val pcm16 = (low or (high shl 8)).toShort()
                samples[index] = pcm16 / PCM16_SCALE
            }
        }
        return WaveData(samples, playable.sampleRate)
    }
}
