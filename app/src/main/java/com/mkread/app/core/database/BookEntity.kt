package com.mkread.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mkread.app.core.model.SourceType

@Entity(
    tableName = "books",
    indices = [
        Index(value = ["source_sha256"], unique = true),
    ],
)
data class BookEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val author: String?,
    @ColumnInfo(name = "source_type")
    val sourceType: SourceType,
    @ColumnInfo(name = "source_sha256")
    val sourceSha256: String,
    @ColumnInfo(name = "cover_relative_path")
    val coverRelativePath: String?,
    @ColumnInfo(name = "imported_at")
    val importedAt: Long,
    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long,
    @ColumnInfo(name = "last_opened_at")
    val lastOpenedAt: Long?,
    @ColumnInfo(name = "folder_id", index = true)
    val folderId: String? = null,
)
