package com.mkread.app.feature.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.InputStream

data class DocumentInfo(
    val displayName: String,
    val mimeType: String?,
)

interface DocumentAccess {
    fun openInputStream(uri: Uri): InputStream

    fun describe(uri: Uri): DocumentInfo = DocumentInfo(
        displayName = fallbackDocumentName(uri),
        mimeType = null,
    )

    fun takePersistableReadPermission(uri: Uri): Boolean

    fun releasePersistableReadPermission(uri: Uri)
}

class AndroidDocumentAccess(context: Context) : DocumentAccess {
    private val contentResolver = context.applicationContext.contentResolver

    override fun openInputStream(uri: Uri): InputStream =
        contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Document provider returned no stream for $uri")

    override fun describe(uri: Uri): DocumentInfo {
        val displayName = runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: fallbackDocumentName(uri)
        val mimeType = runCatching { contentResolver.getType(uri) }.getOrNull()
        return DocumentInfo(displayName, mimeType)
    }

    override fun takePersistableReadPermission(uri: Uri): Boolean = try {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        true
    } catch (_: SecurityException) {
        false
    }

    override fun releasePersistableReadPermission(uri: Uri) {
        try {
            contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Providers may revoke access before terminal work cleanup.
        }
    }
}

private fun fallbackDocumentName(uri: Uri): String = uri.lastPathSegment
    ?.substringAfterLast('/')
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?: "novel"
