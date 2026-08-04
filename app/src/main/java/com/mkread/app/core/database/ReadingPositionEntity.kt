package com.mkread.app.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reading_positions",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["chapterId"])],
)
data class ReadingPositionEntity(
    @PrimaryKey
    val bookId: String,
    val chapterId: String,
    val characterOffset: Int,
    val pageIndex: Int,
    val sentenceIndex: Int,
    val updatedAt: Long,
)
