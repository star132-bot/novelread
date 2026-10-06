package com.mkread.app.cloud

import com.mkread.app.speech.RemoteVoicePack
import com.mkread.app.speech.VoicePackSource
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Voice packs are open models, so listing and downloading them works without signing in. */
class CloudVoicePackSource(private val library: CloudLibrary) : VoicePackSource {
    override suspend fun available(): List<RemoteVoicePack> {
        val server = library.refreshSettings().serverUrl
        if (server.isBlank()) return emptyList()
        val response = CloudHttp.getJson("$server/api/v1/voices")
        return (response["voices"] as? JsonArray).orEmpty().mapNotNull { element ->
            val pack = element as? JsonObject ?: return@mapNotNull null
            RemoteVoicePack(
                id = pack.str("id") ?: return@mapNotNull null,
                revision = pack.long("revision")?.toInt() ?: return@mapNotNull null,
                url = pack.str("packageUrl") ?: return@mapNotNull null,
                sizeBytes = pack.long("packageSize") ?: return@mapNotNull null,
                sha256 = pack.str("packageSha256") ?: return@mapNotNull null,
            )
        }
    }

    override suspend fun download(pack: RemoteVoicePack, target: File, onProgress: (Float) -> Unit) {
        CloudHttp.download(
            url = pack.url,
            bearer = null,
            target = target,
            expectedSize = pack.sizeBytes,
            expectedSha256 = pack.sha256,
            onProgress = onProgress,
        )
    }
}
