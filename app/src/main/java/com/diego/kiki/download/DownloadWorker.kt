package com.diego.kiki.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.diego.kiki.R
import com.diego.kiki.data.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.io.IOException

class DownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val coordinator = DownloadCoordinator.getInstance(applicationContext)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: return Result.failure()
        val mimeType = inputData.getString(KEY_MIME_TYPE)
        val headersJson = inputData.getString(KEY_HEADERS) ?: "{}"
        val headers = try {
            json.decodeFromString<Map<String, String>>(headersJson)
        } catch (_: Exception) {
            emptyMap()
        }

        try {
            setForeground(createForegroundInfo(fileName))
        } catch (_: Exception) {
            // Foreground start not allowed (app in background); continue without it
        }

        coordinator.markRunning(id)

        val request = DownloadRequest(url, headers, fileName, mimeType)
        val job = DirectDownloadJob(
            client = coordinator.client,
            request = request,
            partFile = DownloadStorage.partFile(applicationContext, id),
            onProgress = { downloaded, total ->
                coordinator.publishProgress(id, downloaded, total)
            }
        )

        return try {
            when (val outcome = job.execute()) {
                DirectDownloadJob.Outcome.Completed -> {
                    coordinator.finalizeDownload(id, DownloadStatus.COMPLETED)
                    Result.success()
                }
                DirectDownloadJob.Outcome.Stopped -> Result.success()
                is DirectDownloadJob.Outcome.Failed -> {
                    coordinator.finalizeDownload(id, DownloadStatus.FAILED, outcome.message)
                    Result.failure()
                }
            }
        } catch (e: CancellationException) {
            // Paused or cancelled: coordinator already recorded the state, keep .part
            Result.success()
        } catch (e: IOException) {
            if (runAttemptCount < MAX_RETRIES && coordinator.canRetry(id)) {
                Result.retry()
            } else {
                coordinator.finalizeDownload(id, DownloadStatus.FAILED, e.message)
                Result.failure()
            }
        }
    }

    private fun createForegroundInfo(fileName: String): ForegroundInfo {
        val manager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.notification_channel_downloads),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.notification_downloading_title))
            .setContentText(fileName)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_URL = "url"
        const val KEY_FILE_NAME = "fileName"
        const val KEY_MIME_TYPE = "mimeType"
        const val KEY_HEADERS = "headers"
        const val MAX_RETRIES = 3
        const val CHANNEL_ID = "kiki_downloads"
        const val NOTIFICATION_ID = 2001
    }
}
