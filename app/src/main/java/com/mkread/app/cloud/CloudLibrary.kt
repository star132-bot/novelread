package com.mkread.app.cloud

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mkread.app.BuildConfig
import com.mkread.app.feature.library.BookImportRepository
import com.mkread.app.feature.library.ImportBookUseCase
import com.mkread.app.feature.library.ImportRequest
import com.mkread.app.feature.library.ImportResult
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean

data class CloudSettings(
    val serverUrl: String,
)

data class CloudSyncStatus(
    val running: Boolean = false,
    val lastSyncedAt: Long? = null,
    val message: String? = null,
)

/**
 * Keeps the local shelf in step with the cloud library: polls the catalog with a change cursor,
 * downloads every new or updated .mkbook and imports it through the normal (validated) import path.
 * Unpublished books stay on the device; they simply stop receiving updates.
 */
class CloudLibrary(
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val importer: ImportBookUseCase,
    private val repository: BookImportRepository,
    private val folderForCloudBooks: suspend () -> String?,
) {
    private val syncMutex = Mutex()
    private val mutableStatus = MutableStateFlow(CloudSyncStatus())
    val status: StateFlow<CloudSyncStatus> = mutableStatus.asStateFlow()

    val settings: Flow<CloudSettings> = dataStore.data.map { preferences ->
        CloudSettings(serverUrl = preferences[SERVER_URL] ?: BuildConfig.CLOUD_SERVER_URL)
    }

    @Volatile
    private var cachedSettings = CloudSettings(BuildConfig.CLOUD_SERVER_URL)

    val auth = CloudAuthClient(context, CloudTokenStore(context.filesDir), serverUrl = { cachedSettings.serverUrl })

    suspend fun refreshSettings(): CloudSettings = settings.first().also { cachedSettings = it }

    suspend fun saveSettings(serverUrl: String) {
        val normalized = serverUrl.trim().trimEnd('/')
        if (normalized != refreshSettings().serverUrl) {
            auth.forgetSession()
            dataStore.edit { preferences ->
                preferences[SERVER_URL] = normalized
                preferences.remove(CURSOR)
            }
        }
        refreshSettings()
    }

    suspend fun lastSyncedAt(): Long? = dataStore.data.first()[LAST_SYNC]

    fun schedulePeriodicSync() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CloudSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(networkConstraint())
                .build(),
        )
    }

    fun requestSyncNow() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CloudSyncWorker>().setConstraints(networkConstraint()).build(),
        )
    }

    /** Runs one sync pass. Returns the number of books added or updated. */
    suspend fun sync(): Int = syncMutex.withLock {
        val config = refreshSettings()
        if (config.serverUrl.isBlank()) {
            mutableStatus.value = mutableStatus.value.copy(message = "尚未配置云端书库地址")
            return 0
        }
        mutableStatus.value = mutableStatus.value.copy(running = true, message = "正在同步…")
        var imported = 0
        try {
            var cursor = dataStore.data.first()[CURSOR] ?: 0L
            while (true) {
                val page = CloudHttp.getJson("${config.serverUrl}/api/v1/catalog?since=$cursor", auth.accessToken())
                val books = page["books"] as? JsonArray ?: JsonArray(emptyList())
                for (element in books) {
                    val book = element as? JsonObject ?: continue
                    if (!(book["deleted"] as JsonPrimitive).boolean && syncBook(book)) imported += 1
                    // Advance only past books that were fully handled, so failures are retried next time.
                    cursor = book.long("seq") ?: cursor
                    dataStore.edit { it[CURSOR] = cursor }
                }
                if ((page["hasMore"] as? JsonPrimitive)?.boolean != true) break
            }
            val now = System.currentTimeMillis()
            dataStore.edit { it[LAST_SYNC] = now }
            mutableStatus.value = CloudSyncStatus(
                lastSyncedAt = now,
                message = if (imported > 0) "已同步 $imported 本新书或更新" else "已是最新",
            )
            return imported
        } catch (failure: Exception) {
            if ((failure as? CloudHttpException)?.status == 401) auth.forgetSession()
            mutableStatus.value = mutableStatus.value.copy(
                running = false,
                message = when ((failure as? CloudHttpException)?.status) {
                    401 -> "请先使用 MKauth 账号登录"
                    403 -> "当前账号没有云端书库权限"
                    else -> "同步失败：${failure.message ?: failure.javaClass.simpleName}"
                },
            )
            throw failure
        }
    }

    private suspend fun syncBook(book: JsonObject): Boolean {
        val id = book.str("id") ?: return false
        val revision = book.long("revision")?.toInt() ?: return false
        val local = repository.findCatalogBook(id)
        if (local != null && (local.catalogRevision ?: 0) >= revision) return false
        val downloads = File(context.cacheDir, "cloud-downloads").apply { mkdirs() }
        val file = File(downloads, "$id-r$revision.mkbook")
        try {
            CloudHttp.download(
                url = book.str("packageUrl") ?: return false,
                bearer = auth.accessToken(),
                target = file,
                expectedSize = book.long("packageSize") ?: return false,
                expectedSha256 = book.str("packageSha256") ?: return false,
            )
            val result = importer(
                ImportRequest(
                    displayName = file.name,
                    mimeType = "application/vnd.mkread.book+zip",
                    openStream = { file.inputStream() },
                    folderId = if (local == null) folderForCloudBooks() else null,
                ),
            )
            return when (result) {
                is ImportResult.Success, is ImportResult.Updated -> true
                is ImportResult.Duplicate -> false
                is ImportResult.Failure -> throw java.io.IOException("《${book.str("title")}》导入失败：${result.userMessage}")
            }
        } finally {
            file.delete()
        }
    }

    private fun networkConstraint() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    companion object {
        const val CLOUD_FOLDER_NAME = "云书库"
        private const val PERIODIC_WORK = "cloud-sync-periodic"
        private const val ONE_TIME_WORK = "cloud-sync-now"
        private val SERVER_URL = stringPreferencesKey("cloud_server_url")
        private val CURSOR = longPreferencesKey("cloud_catalog_cursor")
        private val LAST_SYNC = longPreferencesKey("cloud_last_sync")
    }
}

class CloudSyncWorker(
    appContext: Context,
    parameters: WorkerParameters,
    private val library: CloudLibrary,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = try {
        library.sync()
        Result.success()
    } catch (failure: CloudHttpException) {
        if (failure.status == 401 || failure.status == 403) Result.failure() else Result.retry()
    } catch (failure: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}

class CloudSyncWorkerFactory(private val library: () -> CloudLibrary) : androidx.work.WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
        if (workerClassName == CloudSyncWorker::class.java.name) {
            CloudSyncWorker(appContext, workerParameters, library())
        } else {
            null
        }
}
