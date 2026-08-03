package com.mkread.app.speech

import org.json.JSONObject

data class SpeechAssetFile(
    val relativePath: String,
    val byteSize: Long,
    val sha256: String,
)

data class SpeechAssetManifest(
    val schemaVersion: Int,
    val files: List<SpeechAssetFile>,
) {
    init {
        require(schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "Unsupported speech asset schema: $schemaVersion"
        }
        require(files.isNotEmpty()) { "Speech asset manifest must not be empty" }
        require(files.map { it.relativePath }.toSet().size == files.size) {
            "Speech asset manifest contains duplicate paths"
        }
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        private const val MAX_ASSET_BYTES = 2L * 1024L * 1024L * 1024L
        private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")

        fun parse(json: String): SpeechAssetManifest {
            val root = JSONObject(json)
            val records = root.getJSONArray("files")
            val files = buildList(records.length()) {
                for (index in 0 until records.length()) {
                    val record = records.getJSONObject(index)
                    val path = record.getString("path")
                    val size = record.getLong("size")
                    val sha256 = record.getString("sha256")
                    requireSafeRelativePath(path)
                    require(size in 0..MAX_ASSET_BYTES) { "Invalid asset size for $path" }
                    require(SHA_256_PATTERN.matches(sha256)) { "Invalid SHA-256 for $path" }
                    add(SpeechAssetFile(path, size, sha256))
                }
            }
            return SpeechAssetManifest(
                schemaVersion = root.getInt("schemaVersion"),
                files = files,
            )
        }

        private fun requireSafeRelativePath(path: String) {
            require(path.startsWith("models/") || path.startsWith("voices/")) {
                "Speech asset path must be under models/ or voices/"
            }
            require('\\' !in path && ':' !in path) { "Invalid speech asset path: $path" }
            require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
                "Invalid speech asset path: $path"
            }
        }
    }
}
