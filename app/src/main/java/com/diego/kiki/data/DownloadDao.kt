package com.diego.kiki.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAllDownloads(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    suspend fun getAllOnce(): List<DownloadRecord>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getDownloadById(id: String): DownloadRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(download: DownloadRecord)

    @Update
    suspend fun updateDownload(download: DownloadRecord)

    @Query("UPDATE downloads SET status = :status, downloadedBytes = :downloadedBytes, totalBytes = :totalBytes WHERE id = :id")
    suspend fun updateProgress(id: String, status: DownloadStatus, downloadedBytes: Long, totalBytes: Long)

    @Query("UPDATE downloads SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: DownloadStatus)

    @Query("UPDATE downloads SET status = 'FAILED' WHERE status IN ('QUEUED', 'DOWNLOADING')")
    suspend fun resetInterrupted()

    @Delete
    suspend fun deleteDownload(download: DownloadRecord)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteDownloadById(id: String)

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()
}
