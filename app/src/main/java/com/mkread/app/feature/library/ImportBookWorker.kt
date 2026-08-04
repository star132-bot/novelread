package com.mkread.app.feature.library

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.CancellationException

class ImportBookWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
    private val importer: ImportBookUseCase,
    private val documentAccess: DocumentAccess,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val permissionPersisted = inputData.getBoolean(KEY_PERMISSION_PERSISTED, false)
        val uri = inputData.getString(KEY_URI)
            ?.let(Uri::parse)
        if (uri == null || uri.scheme != "content") {
            if (permissionPersisted && uri != null) releasePermissionSafely(uri)
            return terminalFailure(
                ImportFailureCode.SOURCE_UNAVAILABLE,
                "无法读取所选文件，请重新选择",
            )
        }
        val displayName = inputData.getString(KEY_DISPLAY_NAME)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        if (displayName == null) {
            if (permissionPersisted) releasePermissionSafely(uri)
            return terminalFailure(
                ImportFailureCode.SOURCE_UNAVAILABLE,
                "所选文件缺少名称，请重新选择",
            )
        }
        var releasePermission = permissionPersisted
        try {
            val result = importer(
                ImportRequest(
                    displayName = displayName,
                    mimeType = inputData.getString(KEY_MIME_TYPE),
                    openStream = { documentAccess.openInputStream(uri) },
                ),
            )
            return when (result) {
                is ImportResult.Success -> Result.success(
                    workDataOf(
                        KEY_STATUS to STATUS_SUCCESS,
                        KEY_BOOK_ID to result.bookId,
                    ),
                )
                is ImportResult.Duplicate -> Result.success(
                    workDataOf(
                        KEY_STATUS to STATUS_DUPLICATE,
                        KEY_BOOK_ID to result.existingBookId,
                    ),
                )
                is ImportResult.Failure -> {
                    if (result.code.isRetryable() && runAttemptCount < MAX_ATTEMPTS - 1) {
                        releasePermission = false
                        Result.retry()
                    } else {
                        terminalFailure(result.code, result.userMessage)
                    }
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } finally {
            if (releasePermission) {
                releasePermissionSafely(uri)
            }
        }
    }

    private fun releasePermissionSafely(uri: Uri) {
        runCatching { documentAccess.releasePersistableReadPermission(uri) }
    }

    private fun ImportFailureCode.isRetryable(): Boolean =
        this == ImportFailureCode.SOURCE_UNAVAILABLE || this == ImportFailureCode.STORAGE_FULL

    private fun terminalFailure(
        code: ImportFailureCode,
        message: String,
    ): Result = Result.failure(
        workDataOf(
            KEY_FAILURE_CODE to code.name,
            KEY_USER_MESSAGE to message,
        ),
    )

    companion object {
        const val KEY_URI = "document_uri"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_PERMISSION_PERSISTED = "permission_persisted"
        const val KEY_STATUS = "status"
        const val KEY_BOOK_ID = "book_id"
        const val KEY_FAILURE_CODE = "failure_code"
        const val KEY_USER_MESSAGE = "user_message"
        const val STATUS_SUCCESS = "success"
        const val STATUS_DUPLICATE = "duplicate"
        const val MAX_ATTEMPTS = 3

        fun inputData(
            uri: Uri,
            displayName: String,
            mimeType: String?,
            permissionPersisted: Boolean,
        ): Data = workDataOf(
            KEY_URI to uri.toString(),
            KEY_DISPLAY_NAME to displayName,
            KEY_MIME_TYPE to mimeType,
            KEY_PERMISSION_PERSISTED to permissionPersisted,
        )
    }
}

class ImportBookWorkerFactory(
    private val importer: ImportBookUseCase,
    private val documentAccess: DocumentAccess,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ) = if (workerClassName == ImportBookWorker::class.java.name) {
        ImportBookWorker(
            appContext = appContext,
            workerParameters = workerParameters,
            importer = importer,
            documentAccess = documentAccess,
        )
    } else {
        null
    }
}
