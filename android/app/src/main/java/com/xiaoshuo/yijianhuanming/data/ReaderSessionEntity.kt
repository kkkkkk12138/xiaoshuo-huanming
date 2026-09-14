package com.xiaoshuo.yijianhuanming.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reader_sessions")
data class ReaderSessionEntity(
    @PrimaryKey val sourceId: String,
    val type: String,
    val title: String,
    val uri: String,
    val chapterId: String?,
    val scrollRatio: Double,
    val lastOpenedAt: Long,
    val textOffset: Long? = null,
    val textTotalAtSave: Long? = null,
)
