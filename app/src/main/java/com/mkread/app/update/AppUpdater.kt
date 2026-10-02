package com.mkread.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.mkread.app.BuildConfig
import com.mkread.app.cloud.CloudHttp
import com.mkread.app.cloud.long
import com.mkread.app.cloud.str
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val apkSha256: String,
    val mandatory: Boolean,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState

    /** Android needs the user to allow MKread to install apps before the update can proceed. */
    data class NeedsInstallPermission(val release: AppRelease) : UpdateState
    data class Installing(val release: AppRelease) : UpdateState
    data class Failed(val release: AppRelease?, val message: String) : UpdateState
}

/**
 * In-app updates for sideloaded installs: asks the cloud library for the newest release,
 * downloads the APK (size + SHA-256 checked), and hands it to [PackageInstaller]. Android shows
 * its own confirmation; data, books and voice packs survive because every release is signed
 * with the same key.
 */
class AppUpdater(
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val serverUrl: suspend () -> String,
    private val scope: CoroutineScope,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private var job: Job? = null
    private val apkFile = File(context.cacheDir, "updates/mkread-update.apk")

    /** Called on app start: checks at most every [AUTO_CHECK_INTERVAL_MILLIS] and skips versions the user postponed. */
    fun checkAutomatically() {
        scope.launch {
            val last = dataStore.data.first()[LAST_CHECK] ?: 0L
            if (clock() - last < AUTO_CHECK_INTERVAL_MILLIS) return@launch
            check(manual = false)
        }
    }

    fun checkNow() {
        scope.launch { check(manual = true) }
    }

    private suspend fun check(manual: Boolean) {
        if (mutableState.value is UpdateState.Downloading || mutableState.value is UpdateState.Installing) return
        val server = serverUrl().trimEnd('/')
        if (server.isBlank()) return
        if (manual) mutableState.value = UpdateState.Checking
        val release = runCatching { fetchLatest(server) }.getOrElse { failure ->
            if (manual) mutableState.value = UpdateState.Failed(null, "检查更新失败：${failure.message ?: "网络错误"}")
            return
        }
        dataStore.edit { it[LAST_CHECK] = clock() }
        val postponed = dataStore.data.first()[POSTPONED_VERSION] ?: 0
        mutableState.value = when {
            release == null -> if (manual) UpdateState.UpToDate else UpdateState.Idle
            !manual && !release.mandatory && release.versionCode <= postponed -> UpdateState.Idle
            else -> UpdateState.Available(release)
        }
    }

    private suspend fun fetchLatest(server: String): AppRelease? {
        val json = CloudHttp.getJson("$server/api/v1/app/latest?current=$currentVersionCode")
        if ((json["available"] as? JsonPrimitive)?.booleanOrNull != true) return null
        val code = json.long("versionCode")?.toInt() ?: return null
        if (code <= currentVersionCode) return null
        return AppRelease(
            versionCode = code,
            versionName = json.str("versionName") ?: code.toString(),
            notes = json.str("notes").orEmpty(),
            apkUrl = json.str("apkUrl") ?: return null,
            apkSize = json.long("apkSize") ?: return null,
            apkSha256 = json.str("apkSha256") ?: return null,
            mandatory = (json["mandatory"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    /** "稍后": hide this version until a newer one appears (mandatory updates cannot be postponed). */
    fun postpone() {
        val release = (mutableState.value as? UpdateState.Available)?.release
        if (release?.mandatory == true) return
        scope.launch {
            release?.let { r -> dataStore.edit { it[POSTPONED_VERSION] = r.versionCode } }
            mutableState.value = UpdateState.Idle
        }
    }

    fun dismissMessage() {
        val current = mutableState.value
        if (current is UpdateState.UpToDate || current is UpdateState.Failed || current is UpdateState.NeedsInstallPermission) {
            mutableState.value = UpdateState.Idle
        }
    }

    fun startUpdate() {
        val release = when (val current = mutableState.value) {
            is UpdateState.Available -> current.release
            is UpdateState.Failed -> current.release
            is UpdateState.NeedsInstallPermission -> current.release
            else -> null
        } ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                if (!apkFile.isFile || sha256(apkFile) != release.apkSha256) {
                    apkFile.parentFile?.mkdirs()
                    apkFile.delete()
                    mutableState.value = UpdateState.Downloading(release, 0f)
                    CloudHttp.download(
                        url = release.apkUrl,
                        bearer = null,
                        target = apkFile,
                        expectedSize = release.apkSize,
                        expectedSha256 = release.apkSha256,
                    ) { progress -> mutableState.value = UpdateState.Downloading(release, progress) }
                }
                install(release)
            } catch (failure: Exception) {
                mutableState.value = UpdateState.Failed(release, "更新失败：${failure.message ?: "网络错误"}")
            }
        }
    }

    /** Re-checks after the user returns from the "install unknown apps" settings screen. */
    fun onResume() {
        val waiting = mutableState.value as? UpdateState.NeedsInstallPermission ?: return
        if (context.packageManager.canRequestPackageInstalls()) {
            mutableState.value = UpdateState.Available(waiting.release)
            startUpdate()
        }
    }

    fun openInstallPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Result from [UpdateInstallReceiver] when the system rejects or the user cancels the install. */
    fun onInstallFailed(message: String) {
        val release = (mutableState.value as? UpdateState.Installing)?.release
        mutableState.value = UpdateState.Failed(release, message)
    }

    private suspend fun install(release: AppRelease) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            mutableState.value = UpdateState.NeedsInstallPermission(release)
            return
        }
        mutableState.value = UpdateState.Installing(release)
        withContext(Dispatchers.IO) {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apkFile.length())
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("mkread.apk", 0, apkFile.length()).use { output ->
                    apkFile.inputStream().use { it.copyTo(output, 64 * 1024) }
                    session.fsync(output)
                }
                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
        }
    }

    private suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val AUTO_CHECK_INTERVAL_MILLIS = 12L * 60 * 60 * 1_000
        val LAST_CHECK = longPreferencesKey("update_last_check")
        val POSTPONED_VERSION = intPreferencesKey("update_postponed_version")
    }
}
