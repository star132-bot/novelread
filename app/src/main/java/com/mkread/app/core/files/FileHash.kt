package com.mkread.app.core.files

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

object FileHash {
    fun sha256(file: File): String = file.inputStream().buffered().use { input ->
        sha256(input)
    }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(ImportLimits.COPY_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    internal fun copyBounded(
        input: InputStream,
        output: OutputStream,
        byteLimit: Long,
    ): HashedByteCount {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(ImportLimits.COPY_BUFFER_BYTES)
        var byteCount = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (byteCount > byteLimit - read) {
                throw ByteLimitExceededException(byteLimit)
            }
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            byteCount += read
        }
        return HashedByteCount(
            byteCount = byteCount,
            sha256 = digest.digest().toHex(),
        )
    }
}

internal data class HashedByteCount(
    val byteCount: Long,
    val sha256: String,
)

internal class ByteLimitExceededException(
    val limit: Long,
) : Exception("Input exceeds the $limit byte limit")

internal fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
