package com.mkread.app.cloud

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable

@Serializable
data class CloudSession(
    val accessToken: String,
    val refreshToken: String?,
    val accessTokenExpiresAt: Long,
    val subject: String,
    val displayName: String?,
    val isAdmin: Boolean = false,
)

/** Keeps MKauth tokens on disk encrypted with a non-exportable Android Keystore key. */
class CloudTokenStore(filesDir: File) {
    private val file = File(filesDir, "cloud/session.bin")
    private val lock = Any()

    fun read(): CloudSession? = synchronized(lock) {
        if (!file.isFile) return null
        runCatching {
            val bytes = file.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            val plain = cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
            CloudHttp.json.decodeFromString(CloudSession.serializer(), plain.toString(Charsets.UTF_8))
        }.getOrElse {
            file.delete()
            null
        }
    }

    fun write(session: CloudSession) = synchronized(lock) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(
            CloudHttp.json.encodeToString(CloudSession.serializer(), session).toByteArray(Charsets.UTF_8),
        )
        file.parentFile?.mkdirs()
        val partial = File(file.parentFile, "${file.name}.partial")
        partial.writeBytes(cipher.iv + encrypted)
        check(partial.renameTo(file)) { "Unable to store cloud session" }
    }

    fun clear() = synchronized(lock) { file.delete() }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "mkread-cloud-session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
