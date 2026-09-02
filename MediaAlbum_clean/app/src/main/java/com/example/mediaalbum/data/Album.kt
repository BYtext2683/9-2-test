package com.example.mediaalbum.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 分类（相册）。 */
@Entity(tableName = "albums")
data class Album(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)
