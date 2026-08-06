package com.mkread.app.speech

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.mkread.app.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceImportActivity : ComponentActivity() {
    @Volatile
    private var temporaryFile: File? = null
    private var confirmationDialog: AlertDialog? = null
    private var progressDialog: AlertDialog? = null
    @Volatile
    private var importInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sourceUri = intent.validVoiceSource()
        if (sourceUri == null) {
            showToast(R.string.voice_import_invalid_source)
            finish()
            return
        }
        lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                runCatching { prepareImport(sourceUri) }
            }
            prepared.onSuccess(::showConfirmation).onFailure {
                showToast(R.string.voice_import_failed)
                cleanupAndFinish()
            }
        }
    }

    override fun onDestroy() {
        confirmationDialog?.dismiss()
        progressDialog?.dismiss()
        if (!importInProgress) cleanupTemporaryFile()
        super.onDestroy()
    }

    private fun Intent.validVoiceSource(): Uri? = data?.takeIf { source ->
        action == Intent.ACTION_VIEW &&
            source.scheme == CONTENT_SCHEME &&
            type == MIME_TYPE
    }

    private fun prepareImport(sourceUri: Uri): PreparedVoiceImport {
        val stagingRoot = File(cacheDir, STAGING_DIRECTORY)
        check(stagingRoot.isDirectory || stagingRoot.mkdirs()) {
            "Unable to create voice staging directory"
        }
        val temporary = File(stagingRoot, "${UUID.randomUUID()}.mkvoice.partial")
        try {
            contentResolver.openInputStream(sourceUri)?.use { input ->
                copyVoicePackageBounded(input, temporary, MAX_PACKAGE_BYTES)
            } ?: throw IOException("Unable to read the selected voice package")
            temporaryFile = temporary
            val preview = MkVoiceImporter(File(filesDir, VOICES_DIRECTORY))
                .inspectPackage(temporary)
            return PreparedVoiceImport(temporary, preview)
        } catch (failure: Exception) {
            temporary.delete()
            if (stagingRoot.listFiles().isNullOrEmpty()) stagingRoot.delete()
            throw failure
        }
    }

    private fun showConfirmation(prepared: PreparedVoiceImport) {
        if (isFinishing || isDestroyed) return
        val preview = prepared.preview
        val details = getString(
            R.string.voice_import_confirm_details,
            preview.displayName,
            preview.id,
            preview.languages.joinToString(", "),
            preview.emotions.joinToString(", "),
        )
        confirmationDialog = AlertDialog.Builder(this)
            .setTitle(R.string.voice_import_confirm_title)
            .setMessage(details)
            .setNegativeButton(R.string.voice_import_cancel) { _, _ -> cleanupAndFinish() }
            .setPositiveButton(R.string.voice_import_confirm_action) { _, _ ->
                importPrepared(prepared)
            }
            .setOnCancelListener { cleanupAndFinish() }
            .show()
    }

    private fun importPrepared(prepared: PreparedVoiceImport) {
        confirmationDialog = null
        importInProgress = true
        progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.voice_import_progress_title)
            .setView(ProgressBar(this))
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO + NonCancellable) {
                try {
                    runCatching {
                        val imported = MkVoiceImporter(File(filesDir, VOICES_DIRECTORY))
                            .importPackage(prepared.file, replace = false)
                        InstalledVoiceProvider.select(filesDir, imported.id)
                        imported
                    }
                } finally {
                    prepared.file.delete()
                    prepared.file.parentFile
                        ?.takeIf { it.listFiles().isNullOrEmpty() }
                        ?.delete()
                    temporaryFile = null
                }
            }
            importInProgress = false
            progressDialog?.dismiss()
            progressDialog = null
            result.onSuccess { voice ->
                Toast.makeText(
                    this@VoiceImportActivity,
                    getString(R.string.voice_import_success, voice.displayName),
                    Toast.LENGTH_LONG,
                ).show()
            }.onFailure {
                showToast(R.string.voice_import_failed)
            }
            cleanupAndFinish()
        }
    }

    private fun cleanupAndFinish() {
        cleanupTemporaryFile()
        if (!isFinishing) finish()
    }

    private fun cleanupTemporaryFile() {
        val temporary = temporaryFile
        temporaryFile = null
        temporary?.delete()
        temporary?.parentFile
            ?.takeIf { it.listFiles().isNullOrEmpty() }
            ?.delete()
    }

    private fun showToast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private data class PreparedVoiceImport(
        val file: File,
        val preview: VoicePackagePreview,
    )

    private companion object {
        const val VOICES_DIRECTORY = "voices"
        const val STAGING_DIRECTORY = "voice-import"
        const val CONTENT_SCHEME = "content"
        const val MIME_TYPE = "application/vnd.mkread.voice"
        const val MAX_PACKAGE_BYTES = 250L * 1024L * 1024L
    }
}

internal class VoicePackageTooLargeException : IOException("MKvoice package is too large")

internal fun copyVoicePackageBounded(
    input: InputStream,
    destination: File,
    maximumBytes: Long,
): Long {
    require(maximumBytes >= 0L) { "Voice package limit must be non-negative" }
    return try {
        FileOutputStream(destination).use { output ->
            val copied = input.copyBoundedTo(output, maximumBytes)
            output.fd.sync()
            copied
        }
    } catch (failure: Exception) {
        destination.delete()
        throw failure
    }
}

private fun InputStream.copyBoundedTo(output: OutputStream, maximumBytes: Long): Long {
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) return copied
        if (count == 0) continue
        if (copied > maximumBytes - count) throw VoicePackageTooLargeException()
        output.write(buffer, 0, count)
        copied += count
    }
}
