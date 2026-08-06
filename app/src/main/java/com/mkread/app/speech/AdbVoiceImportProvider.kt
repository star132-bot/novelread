package com.mkread.app.speech

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

class AdbVoiceImportProvider : ContentProvider() {
    private val lock = Any()

    override fun onCreate(): Boolean {
        stagingRoot().listFiles().orEmpty()
            .filter { file -> !STAGED_FILE.matches(file.name) }
            .forEach(File::delete)
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode !in WRITE_MODES) throw FileNotFoundException("Voice staging is write-only")
        val hash = uri.requireStagingHash()
        val root = stagingRoot()
        if (!root.isDirectory && !root.mkdirs()) {
            throw FileNotFoundException("Voice staging directory is unavailable")
        }
        return ParcelFileDescriptor.open(
            File(root, "$hash.mkvoice.partial"),
            ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE or
                ParcelFileDescriptor.MODE_WRITE_ONLY,
        )
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method !in IMPORT_METHODS) return errorBundle("unsupported_method")
        val hash = arg?.takeIf(SHA_256::matches) ?: return errorBundle("invalid_hash")
        return synchronized(lock) {
            importStaged(
                hash = hash,
                replaceExisting = replaceExistingVoiceForMethod(method),
            )
        }
    }

    private fun importStaged(hash: String, replaceExisting: Boolean): Bundle {
        val staged = File(stagingRoot(), "$hash.mkvoice.partial")
        return try {
            if (!staged.isFile || staged.length() !in 1..MAX_PACKAGE_BYTES) {
                return errorBundle("invalid_size")
            }
            if (staged.sha256() != hash) return errorBundle("hash_mismatch")
            val appContext = requireNotNull(context)
            val imported = MkVoiceImporter(File(appContext.filesDir, VOICES_DIRECTORY))
                .importPackage(staged, replace = replaceExisting)
            InstalledVoiceProvider.select(appContext.filesDir, imported.id)
            Bundle().apply {
                putString(STATUS_KEY, STATUS_OK)
                putString(VOICE_ID_KEY, imported.id)
            }
        } catch (failure: MkVoiceImportException) {
            errorBundle(failure.code)
        } catch (_: Exception) {
            errorBundle("import_failed")
        } finally {
            staged.delete()
        }
    }

    private fun stagingRoot(): File = File(requireNotNull(context).cacheDir, STAGING_DIRECTORY)

    private fun Uri.requireStagingHash(): String {
        if (pathSegments.size != 2 || pathSegments[0] != STAGING_PATH) {
            throw FileNotFoundException("Invalid voice staging URI")
        }
        return pathSegments[1].takeIf(SHA_256::matches)
            ?: throw FileNotFoundException("Invalid voice staging hash")
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun errorBundle(code: String) = Bundle().apply {
        putString(STATUS_KEY, STATUS_ERROR)
        putString(ERROR_CODE_KEY, code)
    }

    override fun getType(uri: Uri): String = MIME_TYPE

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val STAGING_DIRECTORY = "voice-import-adb"
        const val STAGING_PATH = "staging"
        const val STATUS_KEY = "status"
        const val STATUS_OK = "ok"
        const val STATUS_ERROR = "error"
        const val ERROR_CODE_KEY = "errorCode"
        const val VOICE_ID_KEY = "voiceId"
        const val VOICES_DIRECTORY = "voices"
        const val MIME_TYPE = "application/vnd.mkread.voice"
        const val MAX_PACKAGE_BYTES = 250L * 1024L * 1024L
        val SHA_256 = Regex("[0-9a-f]{64}")
        val STAGED_FILE = Regex("[0-9a-f]{64}\\.mkvoice\\.partial")
        val WRITE_MODES = setOf("w", "wt", "wa", "rw", "rwt")
        val IMPORT_METHODS = setOf(ADB_IMPORT_METHOD, ADB_IMPORT_REPLACE_METHOD)
    }
}

internal const val ADB_IMPORT_METHOD = "import"
internal const val ADB_IMPORT_REPLACE_METHOD = "import_replace"

internal fun replaceExistingVoiceForMethod(method: String): Boolean =
    method == ADB_IMPORT_REPLACE_METHOD
