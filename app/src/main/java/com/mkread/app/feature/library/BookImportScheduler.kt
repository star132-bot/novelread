package com.mkread.app.feature.library

import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class BookImportScheduler internal constructor(
    private val workEnqueuer: WorkEnqueuer,
    private val documentAccess: DocumentAccess,
) {
    constructor(
        workManager: WorkManager,
        documentAccess: DocumentAccess,
    ) : this(
        workEnqueuer = WorkEnqueuer { name, policy, request ->
            workManager.enqueueUniqueWork(name, policy, request)
        },
        documentAccess = documentAccess,
    )

    val documentContract = ActivityResultContracts.OpenDocument()

    fun enqueue(
        uri: Uri,
        displayName: String,
        mimeType: String?,
    ): UUID {
        require(uri.scheme == "content") { "Only content documents can be imported" }
        val normalizedName = displayName.trim()
        require(normalizedName.isNotEmpty()) { "Document display name is required" }
        val permissionPersisted = documentAccess.takePersistableReadPermission(uri)
        val request = OneTimeWorkRequestBuilder<ImportBookWorker>()
            .setInputData(
                ImportBookWorker.inputData(
                    uri = uri,
                    displayName = normalizedName,
                    mimeType = mimeType,
                    permissionPersisted = permissionPersisted,
                ),
            )
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                MIN_BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .addTag(IMPORT_TAG)
            .build()
        try {
            workEnqueuer.enqueue(
                uniqueWorkName(uri, normalizedName),
                ExistingWorkPolicy.KEEP,
                request,
            )
        } catch (failure: RuntimeException) {
            if (permissionPersisted) {
                runCatching { documentAccess.releasePersistableReadPermission(uri) }
            }
            throw failure
        }
        return request.id
    }

    companion object {
        val SUPPORTED_MIME_TYPES = listOf(
            "text/plain",
            "application/epub+zip",
            "application/octet-stream",
        )
        const val IMPORT_TAG = "book-import"
        private const val MIN_BACKOFF_SECONDS = 10L

        fun uniqueWorkName(uri: Uri, displayName: String): String {
            val identity = "$uri\u0000$displayName".toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(identity)
            return "import-" + digest.joinToString("") { byte -> "%02x".format(byte) }
        }
    }

    internal fun interface WorkEnqueuer {
        fun enqueue(
            name: String,
            policy: ExistingWorkPolicy,
            request: OneTimeWorkRequest,
        )
    }
}
