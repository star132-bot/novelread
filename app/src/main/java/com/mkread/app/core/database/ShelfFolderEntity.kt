package com.mkread.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "shelf_folders",
    indices = [Index(value = ["name"], unique = true)],
)
data class ShelfFolderEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE)
    val name: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
