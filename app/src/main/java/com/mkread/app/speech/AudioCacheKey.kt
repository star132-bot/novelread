package com.mkread.app.speech

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

data class AudioCacheKeyInput(
    val bookContentSha256: String,
    val chapterId: String,
    val sentenceStart: Int,
    val sentenceEnd: Int,
    val normalizedTextSha256: String,
    val voicePackageSha256: String,
    val styleId: String,
    val qualityId: String,
    val generationConfigurationVersion: Int = 1,
)

object AudioCacheKey {
    fun calculate(input: AudioCacheKeyInput): String {
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeLengthPrefixed(input.bookContentSha256)
                output.writeLengthPrefixed(input.chapterId)
                output.writeLengthPrefixed(input.sentenceStart.toString())
                output.writeLengthPrefixed(input.sentenceEnd.toString())
                output.writeLengthPrefixed(input.normalizedTextSha256)
                output.writeLengthPrefixed(input.voicePackageSha256)
                output.writeLengthPrefixed(input.styleId)
                output.writeLengthPrefixed(input.qualityId)
                output.writeLengthPrefixed(input.generationConfigurationVersion.toString())
            }
            bytes.toByteArray()
        }
        return MessageDigest.getInstance(SHA_256)
            .digest(payload)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun DataOutputStream.writeLengthPrefixed(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private const val SHA_256 = "SHA-256"
}
