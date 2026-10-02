package com.mkread.app.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.mkread.app.MainActivity
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Signs in to the cloud library with an MKauth account.
 *
 * The library server is the MKauth client (it keeps the client secret); the app only opens the
 * server's /auth/start page in a browser tab, receives a one-time code on a loopback port
 * (RFC 8252 section 7.3) and redeems it with its PKCE verifier for a long-lived device token.
 */
class CloudAuthClient(
    private val context: Context,
    private val tokenStore: CloudTokenStore,
    private val serverUrl: () -> String,
) {
    val session: CloudSession?
        get() = tokenStore.read()

    suspend fun signIn(): CloudSession {
        val server = serverUrl().trimEnd('/')
        require(server.isNotBlank()) { "尚未配置云端书库地址" }
        val verifier = randomUrlSafe(48)
        val state = randomUrlSafe(24)
        val code = withContext(Dispatchers.IO) {
            ServerSocket(0, 8, InetAddress.getByName(LOOPBACK)).use { listener ->
                listener.soTimeout = LOGIN_TIMEOUT_MS
                val start = Uri.parse("$server/api/v1/auth/start").buildUpon()
                    .appendQueryParameter("port", listener.localPort.toString())
                    .appendQueryParameter("state", state)
                    .appendQueryParameter("code_challenge", s256(verifier))
                    .build()
                withContext(Dispatchers.Main) {
                    CustomTabsIntent.Builder().build().apply {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }.launchUrl(context, start)
                }
                val callback = awaitCallback(listener)
                bringAppToFront()
                if (callback.getQueryParameter("state") != state) throw IOException("登录状态校验失败，请重试")
                callback.getQueryParameter("error")?.let { throw IOException("登录被取消或拒绝（$it）") }
                callback.getQueryParameter("code") ?: throw IOException("登录未返回授权码")
            }
        }
        val response = CloudHttp.postJson(
            "$server/api/v1/auth/token",
            mapOf("code" to code, "code_verifier" to verifier),
        )
        val session = CloudSession(
            accessToken = response.str("token") ?: throw IOException("服务器未返回登录令牌"),
            refreshToken = null,
            accessTokenExpiresAt = response.str("expiresAt")
                ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: Long.MAX_VALUE,
            subject = response.str("subject") ?: "unknown",
            displayName = response.str("name"),
            isAdmin = (response["isAdmin"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
        tokenStore.write(session)
        return session
    }

    /** The device token for API calls, or null when signed out. Expiry slides on the server. */
    fun accessToken(): String? = tokenStore.read()?.accessToken

    suspend fun signOut() {
        val token = accessToken()
        tokenStore.clear()
        if (token != null) {
            runCatching { CloudHttp.postJson("${serverUrl().trimEnd('/')}/api/v1/auth/logout", emptyMap(), token) }
        }
    }

    /** Called when the server rejects the token so the UI shows the signed-out state. */
    fun forgetSession() = tokenStore.clear()

    /**
     * Accepts loopback connections until the browser delivers the redirect. Browsers open idle
     * speculative connections, so a connection that sends nothing is dropped and we keep waiting.
     */
    private fun awaitCallback(listener: ServerSocket): Uri {
        while (true) {
            val socket = try {
                listener.accept()
            } catch (timeout: SocketTimeoutException) {
                throw IOException("登录超时，请重试")
            }
            val callback = runCatching { answer(socket) }.getOrNull()
            if (callback != null) return callback
        }
    }

    private fun answer(socket: java.net.Socket): Uri? {
        socket.use { client ->
                client.soTimeout = IDLE_CONNECTION_TIMEOUT_MS
                val requestLine = client.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                    ?: return null
                val target = requestLine.split(' ').getOrNull(1).orEmpty()
                val isCallback = target.startsWith(CALLBACK_PATH)
                val body = (if (isCallback) SUCCESS_PAGE else "Not found").toByteArray(Charsets.UTF_8)
                client.getOutputStream().apply {
                    write(
                        ("HTTP/1.1 ${if (isCallback) "200 OK" else "404 Not Found"}\r\n" +
                            "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
                    )
                    write(body)
                    flush()
                }
                return if (isCallback) Uri.parse("http://$LOOPBACK$target") else null
        }
    }

    private fun bringAppToFront() {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val CALLBACK_PATH = "/callback"
        const val LOGIN_TIMEOUT_MS = 10 * 60 * 1_000
        const val IDLE_CONNECTION_TIMEOUT_MS = 2_000
        /** Browsers block apps from re-opening themselves, so the page links back into the app. */
        const val RETURN_URL = "intent://cloud-login#Intent;scheme=mkread;package=com.mkread.app;end"
        const val SUCCESS_PAGE = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>" +
            "<body style='font-family:sans-serif;text-align:center;padding-top:28vh'>" +
            "<h2>登录成功</h2>" +
            "<p><a href='$RETURN_URL' style='display:inline-block;padding:12px 28px;border-radius:24px;" +
            "background:#24604c;color:#fff;text-decoration:none'>返回 MKread</a></p>" +
            "<script>location.replace('$RETURN_URL')</script></body>"

        private val random = SecureRandom()

        fun randomUrlSafe(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).urlSafe()

        fun s256(verifier: String): String =
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)).urlSafe()

        private fun ByteArray.urlSafe(): String =
            Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
