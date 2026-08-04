package com.mkread.app.feature.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.FileNotFoundException
import java.io.InputStream

interface DocumentAccess {
    fun openInputStream(uri: Uri): InputStream

    fun takePersistableReadPermission(uri: Uri): Boolean

    fun releasePersistableReadPermission(uri: Uri)
}

class AndroidDocumentAccess(context: Context) : DocumentAccess {
    private val contentResolver = context.applicationContext.contentResolver

    override fun openInputStream(uri: Uri): InputStream =
        contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Document provider returned no stream for $uri")

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
