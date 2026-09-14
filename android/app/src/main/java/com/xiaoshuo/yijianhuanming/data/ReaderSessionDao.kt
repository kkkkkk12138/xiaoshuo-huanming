package com.xiaoshuo.yijianhuanming.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReaderSessionDao {
    @Upsert
    suspend fun upsert(session: ReaderSessionEntity)

    @Query("SELECT * FROM reader_sessions ORDER BY lastOpenedAt DESC")
    fun observeRecent(): Flow<List<ReaderSessionEntity>>

    @Query("SELECT * FROM reader_sessions WHERE sourceId = :sourceId LIMIT 1")
    suspend fun findBySourceId(sourceId: String): ReaderSessionEntity?

    @Transaction
    suspend fun upsertMetadataPreservingProgress(
        sourceId: String,
        type: String,
        title: String,
        uri: String,
        lastOpenedAt: Long,
    ) {
        if (updateMetadata(sourceId, type, title, uri, lastOpenedAt) == 0) {
            insertIfAbsent(
                ReaderSessionEntity(
                    sourceId = sourceId,
                    type = type,
                    title = title,
                    uri = uri,
                    chapterId = null,
                    scrollRatio = 0.0,
                    lastOpenedAt = lastOpenedAt,
                ),
            )
            updateMetadata(sourceId, type, title, uri, lastOpenedAt)
        }
    }

    @Query(
        """UPDATE reader_sessions
           SET chapterId = :chapterId,
               scrollRatio = :scrollRatio,
               textOffset = :textOffset,
               textTotalAtSave = :textTotalAtSave,
               lastOpenedAt = :lastOpenedAt
           WHERE sourceId = :sourceId""",
    )
    suspend fun saveProgress(
        sourceId: String,
        chapterId: String?,
        scrollRatio: Double,
        textOffset: Long?,
        textTotalAtSave: Long?,
        lastOpenedAt: Long,
    )

    @Query(
        """UPDATE reader_sessions
           SET type = :type, title = :title, uri = :uri, lastOpenedAt = :lastOpenedAt
           WHERE sourceId = :sourceId""",
    )
    suspend fun updateMetadata(
        sourceId: String,
        type: String,
        title: String,
        uri: String,
        lastOpenedAt: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(session: ReaderSessionEntity): Long

    @Query("DELETE FROM reader_sessions")
    suspend fun clear()
}
