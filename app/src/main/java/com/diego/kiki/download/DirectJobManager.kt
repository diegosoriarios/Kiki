package com.diego.kiki.download

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.diego.kiki.data.DownloadRecord
import com.diego.kiki.data.DownloadStatus
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.concurrent.TimeUnit

/** Direct (non-streaming) downloads backed by [DownloadWorker] + WorkManager. */
class DirectJobManager(
    private val core: CoordinatorCore,
    private val workManager: WorkManager,
    private val onCompleted: suspend (String) -> Unit
) {
    fun enqueue(request: DownloadRequest): String {
        val id = java.util.UUID.randomUUID().toString()
        val record = DownloadRecord(
            id = id,
            url = request.url,
            fileName = request.fileName,
            filePath = "",
            mimeType = request.mimeType,
            totalBytes = -1,
            downloadedBytes = 0,
            status = DownloadStatus.QUEUED,
            downloadType = DownloadCoordinator.TYPE_DIRECT,
            thumbnailUrl = request.thumbnailUrl,
            qualityLabel = request.qualityLabel,
            sourceHost = request.sourceHost
        )
        core.scope.launch {
            core.dao.insertDownload(record)
        }
        core.requestCache[id] = request
        core.putQueued(id)
        enqueueWork(id, request)
        return id
    }

    /** Resume path for a direct download: replace any previous worker. */
    fun resumeDirect(id: String, request: DownloadRequest) {
        enqueueWork(id, request, replace = true)
    }

    /** Fallback pause: no live in-process job, just stop the worker. */
    fun pause(id: String) {
        core.stoppedIds.add(id)
        workManager.cancelUniqueWork(uniqueWorkName(id))
        core.updateStatus(id, DownloadStatus.PAUSED)
    }

    // Called from DownloadWorker -------------------------------------------------

    fun markRunning(id: String) {
        core.stoppedIds.remove(id)
        core.updateStatus(id, DownloadStatus.DOWNLOADING)
    }

    fun canRetry(id: String): Boolean = !core.stoppedIds.contains(id)

    fun finalizeDownload(id: String, status: DownloadStatus, errorMessage: String? = null) {
        core.scope.launch {
            val record = core.dao.getDownloadById(id) ?: return@launch

            if (status == DownloadStatus.FAILED && core.stoppedIds.contains(id)) {
                // User already paused/cancelled; do not override their choice
                return@launch
            }

            if (status == DownloadStatus.COMPLETED) {
                val finalFile = moveToDownloads(id, record.fileName)
                if (finalFile == null) {
                    core.dao.insertDownload(
                        record.copy(status = DownloadStatus.FAILED, errorMessage = "Could not finalize file")
                    )
                    core.removeProgress(id)
                    return@launch
                }
                core.dao.insertDownload(
                    record.copy(
                        status = DownloadStatus.COMPLETED,
                        filePath = finalFile.absolutePath,
                        downloadedBytes = finalFile.length(),
                        totalBytes = finalFile.length()
                    )
                )
                onCompleted(id)
            } else if (status == DownloadStatus.FAILED) {
                core.dao.insertDownload(
                    record.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = errorMessage ?: "Download failed"
                    )
                )
            } else {
                core.dao.updateProgress(id, status, record.downloadedBytes, record.totalBytes)
            }
            core.removeProgress(id)
            core.stoppedIds.remove(id)
        }
    }

    // Internals ------------------------------------------------------------------

    fun enqueueWork(id: String, request: DownloadRequest, replace: Boolean = false) {
        val data = workDataOf(
            DownloadWorker.KEY_ID to id,
            DownloadWorker.KEY_URL to request.url,
            DownloadWorker.KEY_FILE_NAME to request.fileName,
            DownloadWorker.KEY_MIME_TYPE to request.mimeType,
            DownloadWorker.KEY_HEADERS to core.json.encodeToString(request.headers)
        )
        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(data)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(
            uniqueWorkName(id),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun moveToDownloads(id: String, fileName: String): File? {
        return try {
            val dir = DownloadStorage.downloadsDir(core.appContext)
            val partFile = DownloadStorage.partFile(core.appContext, id)
            if (!partFile.exists()) return null
            val target = DownloadStorage.resolveUniqueTarget(dir, fileName)
            if (!partFile.renameTo(target)) {
                partFile.copyTo(target, overwrite = false)
                partFile.delete()
            }
            target
        } catch (_: Exception) {
            null
        }
    }

    fun uniqueWorkName(id: String) = "kiki_dl_$id"
}
