package com.mkread.app.feature.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BookDirectoryScan(
    val name: String,
    val documents: List<Uri>,
)

class AndroidBookDirectoryScanner(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun scan(treeUri: Uri): BookDirectoryScan = withContext(Dispatchers.IO) {
        require(treeUri.scheme == "content") { "Only document trees are supported" }
        runCatching {
            resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val rootName = queryName(rootUri).ifBlank { "导入文件夹" }
        val pending = ArrayDeque<String>().apply { add(rootId) }
        val documents = mutableListOf<Uri>()
        var visited = 0
        while (pending.isNotEmpty() && visited < MAX_DOCUMENTS) {
            val parentId = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            resolver.query(children, PROJECTION, null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val typeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext() && visited++ < MAX_DOCUMENTS) {
                    val id = cursor.getString(idColumn)
                    val name = cursor.getString(nameColumn).orEmpty()
                    val mimeType = cursor.getString(typeColumn)
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending.addLast(id)
                    } else if (isSupported(name, mimeType)) {
                        documents += DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    }
                }
            }
        }
        BookDirectoryScan(rootName, documents)
    }

    private fun queryName(uri: Uri): String = resolver.query(
        uri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty().trim() else "" }.orEmpty()

    private fun isSupported(name: String, mimeType: String?): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension == "txt" || extension == "epub" ||
            mimeType == "text/plain" || mimeType == "application/epub+zip"
    }

    private companion object {
        const val MAX_DOCUMENTS = 2_000
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
    }
}
