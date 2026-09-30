package com.diego.kiki.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Entity(tableName = "downloads")
data class DownloadRecord(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val url: String,
    val fileName: String,
    val filePath: String,
    val mimeType: String? = null,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val downloadType: String = "DIRECT",
    val createdAt: Long = System.currentTimeMillis(),
    val errorMessage: String? = null
)
