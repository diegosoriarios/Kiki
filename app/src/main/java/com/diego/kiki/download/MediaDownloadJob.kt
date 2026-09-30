package com.diego.kiki.download

import com.diego.kiki.data.DownloadStatus
import kotlinx.coroutines.flow.Flow

data class DownloadProgress(
    val downloadedBytes: Long = 0,
    val totalBytes: Long = -1,
    val status: DownloadStatus = DownloadStatus.QUEUED
)

/**
 * UI-facing handle for a download regardless of pipeline type (direct, HLS, ...).
 * New pipeline types implement this so downloads UI never changes.
 */
interface MediaDownloadJob {
    val id: String
    val url: String
    val progress: Flow<DownloadProgress>
    fun pause()
    fun resume()
    fun cancel()
}
