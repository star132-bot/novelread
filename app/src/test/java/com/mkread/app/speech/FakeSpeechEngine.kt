package com.mkread.app.speech

import java.io.ByteArrayOutputStream
import java.io.FileOutputStream

class FakeSpeechEngine : SpeechEngine {
    val requests = mutableListOf<SpeechRequest>()
    var isInitialized = false
        private set
    var isClosed = false
        private set

    override suspend fun initialize(): Result<Unit> = runCatching {
        check(!isClosed) { "Speech engine is closed" }
        isInitialized = true
    }

    override suspend fun generate(request: SpeechRequest): Result<SpeechResult> = runCatching {
        check(isInitialized) { "Speech engine is not initialized" }
        check(!isClosed) { "Speech engine is closed" }
        requests += request

        request.outputFile.parentFile?.mkdirs()
        FileOutputStream(request.outputFile).use { output ->
            output.write(silenceWave())
        }
        val sampleCount = WaveValidator.requirePlayable(request.outputFile)
        SpeechResult(
            file = request.outputFile,
            sampleRate = SAMPLE_RATE,
            sampleCount = sampleCount,
            generationMillis = 0L,
        )
    }

    override fun close() {
        isClosed = true
    }

    private fun silenceWave(): ByteArray {
        val dataSize = SAMPLE_COUNT * BYTES_PER_SAMPLE
        return ByteArrayOutputStream().apply {
            writeAscii("RIFF")
            writeLittleEndian32(36 + dataSize)
            writeAscii("WAVE")
            writeAscii("fmt ")
            writeLittleEndian32(16)
            writeLittleEndian16(1)
            writeLittleEndian16(1)
            writeLittleEndian32(SAMPLE_RATE)
            writeLittleEndian32(SAMPLE_RATE * BYTES_PER_SAMPLE)
            writeLittleEndian16(BYTES_PER_SAMPLE)
            writeLittleEndian16(16)
            writeAscii("data")
            writeLittleEndian32(dataSize)
            write(ByteArray(dataSize))
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

    private companion object {
        const val SAMPLE_RATE = 24_000
        const val SAMPLE_COUNT = 2_400
        const val BYTES_PER_SAMPLE = 2
    }
}
