package com.mkread.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["book_id"]),
        Index(value = ["book_id", "ordinal"], unique = true),
    ],
)
data class ChapterEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "book_id")
    val bookId: String,
    val ordinal: Int,
    val title: String,
    @ColumnInfo(name = "relative_path")
    val relativePath: String,
    @ColumnInfo(name = "character_count")
    val characterCount: Int,
    @ColumnInfo(name = "content_sha256")
    val contentSha256: String,
)
