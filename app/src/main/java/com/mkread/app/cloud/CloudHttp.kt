package com.mkread.app.cloud

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class CloudHttpException(val status: Int, message: String) : IOException(message)

/** Minimal JSON-over-HTTPS helper; the app has no other networking dependency. */
internal object CloudHttp {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_JSON_BYTES = 8 * 1024 * 1024
    val json = Json { ignoreUnknownKeys = true }

    suspend fun getJson(url: String, bearer: String? = null): JsonObject = withContext(Dispatchers.IO) {
        open(url, "GET", bearer).useResponse { connection ->
            json.parseToJsonElement(connection.readBody()).jsonObject
        }
    }

    suspend fun postForm(url: String, form: Map<String, String>): JsonObject = withContext(Dispatchers.IO) {
        val body = form.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }.toByteArray(Charsets.UTF_8)
        val connection = open(url, "POST", null).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setFixedLengthStreamingMode(body.size)
        }
        connection.outputStream.use { it.write(body) }
        connection.useResponse { json.parseToJsonElement(it.readBody()).jsonObject }
    }

    suspend fun postJson(url: String, body: Map<String, String>, bearer: String? = null): JsonObject =
        withContext(Dispatchers.IO) {
            val bytes = json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                JsonObject(body.mapValues { JsonPrimitive(it.value) }),
            ).toByteArray(Charsets.UTF_8)
            val connection = open(url, "POST", bearer).apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setFixedLengthStreamingMode(bytes.size)
            }
            connection.outputStream.use { it.write(bytes) }
            connection.useResponse { response ->
                val text = if (response.responseCode == 204) "" else response.readBody()
                if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text).jsonObject
            }
        }

    /** Streams [url] into [target], verifying size and SHA-256 before the file is kept. */
    suspend fun download(
        url: String,
        bearer: String?,
        target: File,
        expectedSize: Long,
        expectedSha256: String,
        onProgress: (Float) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val partial = File(target.parentFile, "${target.name}.partial")
        try {
            open(url, "GET", bearer).useResponse { connection ->
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > expectedSize) throw IOException("Download is larger than announced")
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            onProgress(total.toFloat() / expectedSize.coerceAtLeast(1L))
                        }
                    }
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                if (total != expectedSize || sha != expectedSha256) throw IOException("Download checksum mismatch")
            }
            if (!partial.renameTo(target)) throw IOException("Unable to store download")
        } finally {
            partial.delete()
        }
    }

    private fun open(url: String, method: String, bearer: String?): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        return connection
    }

    private inline fun <T> HttpURLConnection.useResponse(block: (HttpURLConnection) -> T): T = try {
        val status = responseCode
        if (status !in 200..299) {
            val detail = runCatching {
                val error = json.parseToJsonElement(readErrorBody()).jsonObject
                (error["detail"] as? JsonPrimitive)?.content ?: (error["error_description"] as? JsonPrimitive)?.content
            }.getOrNull()
            throw CloudHttpException(status, detail ?: "HTTP $status")
        }
        block(this)
    } finally {
        disconnect()
    }

    private fun HttpURLConnection.readBody(): String = inputStream.use { input ->
        val bytes = input.readNBytesCompat(MAX_JSON_BYTES + 1)
        if (bytes.size > MAX_JSON_BYTES) throw IOException("Response is too large")
        bytes.toString(Charsets.UTF_8)
    }

    private fun HttpURLConnection.readErrorBody(): String =
        errorStream?.use { it.readNBytesCompat(64 * 1024).toString(Charsets.UTF_8) }.orEmpty()

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (output.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}

internal fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.content?.toLongOrNull()
