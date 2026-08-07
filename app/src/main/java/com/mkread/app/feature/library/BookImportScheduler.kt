package com.mkread.app.feature.library

import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform

internal enum class ImportWorkStatus {
    ENQUEUED,
    RUNNING,
    SUCCESS,
    DUPLICATE,
    FAILED,
    CANCELLED,
}

internal data class ImportWorkRecord(
    val id: UUID,
    val status: ImportWorkStatus,
    val bookId: String? = null,
    val message: String? = null,
)

class BookImportScheduler internal constructor(
    private val workEnqueuer: WorkEnqueuer,
    private val documentAccess: DocumentAccess,
    workRecords: Flow<List<ImportWorkRecord>> = emptyFlow(),
) : BookImportManager {
    private val observationLock = Any()
    private val knownWorkIds = mutableSetOf<UUID>()
    private var observedInitialSnapshot = false

    constructor(
        workManager: WorkManager,
        documentAccess: DocumentAccess,
    ) : this(
        workEnqueuer = WorkEnqueuer { name, policy, request ->
            workManager.enqueueUniqueWork(name, policy, request)
        },
        documentAccess = documentAccess,
        workRecords = workManager.getWorkInfosByTagFlow(IMPORT_TAG)
            .map { workInfos -> workInfos.map(WorkInfo::toImportWorkRecord) },
    )

    val documentContract = ActivityResultContracts.OpenDocument()

    override val updates: Flow<ImportWorkUpdate> = workRecords
        .transform { records ->
            val emissions = synchronized(observationLock) {
                if (!observedInitialSnapshot) {
                    knownWorkIds += records
                        .filter { record -> record.status.isActive() }
                        .map(ImportWorkRecord::id)
                    observedInitialSnapshot = true
                }
                val relevant = records.filter { record -> record.id in knownWorkIds }
                val terminal = relevant.filterNot { record -> record.status.isActive() }
                terminal.forEach { record -> knownWorkIds.remove(record.id) }
                buildList {
                    terminal
                        .sortedBy { record -> record.id.toString() }
                        .forEach { record -> add(record.toUpdate()) }
                    val active = relevant
                        .filter { record -> record.status.isActive() }
                        .minByOrNull { record -> record.id.toString() }
                    if (active != null) {
                        add(ImportWorkUpdate.Running(active.id))
                    } else if (terminal.isEmpty()) {
                        add(ImportWorkUpdate.Idle)
                    }
                }
            }
            emissions.forEach { update -> emit(update) }
        }
        .distinctUntilChanged()

    override fun enqueue(uri: Uri): UUID {
        val document = documentAccess.describe(uri)
        return enqueue(uri, document.displayName, document.mimeType, folderId = null)
    }

    override fun enqueueToFolder(uri: Uri, folderId: String): UUID {
        val document = documentAccess.describe(uri)
        return enqueue(uri, document.displayName, document.mimeType, folderId)
    }

    fun enqueue(
        uri: Uri,
        displayName: String,
        mimeType: String?,
        folderId: String? = null,
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
                    folderId = folderId,
                ),
            )
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                MIN_BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .addTag(IMPORT_TAG)
            .build()
        synchronized(observationLock) {
            knownWorkIds += request.id
        }
        try {
            workEnqueuer.enqueue(
                uniqueWorkName(uri, normalizedName, folderId),
                ExistingWorkPolicy.KEEP,
                request,
            )
        } catch (failure: RuntimeException) {
            synchronized(observationLock) {
                knownWorkIds.remove(request.id)
            }
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

        fun uniqueWorkName(uri: Uri, displayName: String, folderId: String? = null): String {
            val identity = "$uri\u0000$displayName\u0000${folderId.orEmpty()}".toByteArray(Charsets.UTF_8)
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

private fun ImportWorkStatus.isActive(): Boolean =
    this == ImportWorkStatus.ENQUEUED || this == ImportWorkStatus.RUNNING

private fun ImportWorkRecord.toUpdate(): ImportWorkUpdate = when (status) {
    ImportWorkStatus.SUCCESS -> bookId?.let { ImportWorkUpdate.Success(id, it) }
        ?: ImportWorkUpdate.Failure(id, "导入结果不完整，请重新导入")
    ImportWorkStatus.DUPLICATE -> ImportWorkUpdate.Duplicate(id, bookId)
    ImportWorkStatus.FAILED -> ImportWorkUpdate.Failure(id, message ?: "导入失败")
    ImportWorkStatus.CANCELLED -> ImportWorkUpdate.Failure(id, "导入已取消")
    ImportWorkStatus.ENQUEUED,
    ImportWorkStatus.RUNNING,
    -> ImportWorkUpdate.Running(id)
}

private fun WorkInfo.toImportWorkRecord(): ImportWorkRecord {
    val status = when (state) {
        WorkInfo.State.ENQUEUED,
        WorkInfo.State.BLOCKED,
        -> ImportWorkStatus.ENQUEUED
        WorkInfo.State.RUNNING -> ImportWorkStatus.RUNNING
        WorkInfo.State.SUCCEEDED -> when (outputData.getString(ImportBookWorker.KEY_STATUS)) {
            ImportBookWorker.STATUS_DUPLICATE -> ImportWorkStatus.DUPLICATE
            else -> ImportWorkStatus.SUCCESS
        }
        WorkInfo.State.FAILED -> ImportWorkStatus.FAILED
        WorkInfo.State.CANCELLED -> ImportWorkStatus.CANCELLED
    }
    return ImportWorkRecord(
        id = id,
        status = status,
        bookId = outputData.getString(ImportBookWorker.KEY_BOOK_ID),
        message = outputData.getString(ImportBookWorker.KEY_USER_MESSAGE),
    )
}
