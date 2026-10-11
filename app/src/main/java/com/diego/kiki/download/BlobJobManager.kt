package com.diego.kiki.download

import com.diego.kiki.data.DownloadRecord
import com.diego.kiki.data.DownloadStatus
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * In-page blob transfer lifecycle. Bytes stream out of the page via the JS
 * bridge ([DownloadCoordinator.handleBlobEvent] routes events here), so the
 * source tab must stay open until the transfer finishes.
 */
class BlobJobManager(
    private val core: CoordinatorCore,
    private val onCompleted: suspend (String) -> Unit
) {
    private val blobJobs = ConcurrentHashMap<String, BlobDownloadJob>()

    fun enqueueBlob(
        blobUrl: String,
        tabId: String,
        headers: Map<String, String>,
        fileName: String,
        thumbnailUrl: String? = null,
        qualityLabel: String? = null,
        sourceHost: String? = null
    ): String {
        val id = java.util.UUID.randomUUID().toString()
        val record = DownloadRecord(
            id = id,
            url = blobUrl,
            fileName = fileName,
            filePath = "",
            mimeType = "video/mp4",
            totalBytes = -1,
            downloadedBytes = 0,
            status = DownloadStatus.QUEUED,
            downloadType = DownloadCoordinator.TYPE_BLOB,
            thumbnailUrl = thumbnailUrl,
            qualityLabel = qualityLabel,
            sourceHost = sourceHost
        )
        core.scope.launch {
            core.dao.insertDownload(record)
        }
        core.requestCache[id] = DownloadRequest(blobUrl, headers, fileName, "video/mp4")
        core.putQueued(id)
        startBlobJob(id, blobUrl, tabId, fileName)
        return id
    }

    private fun startBlobJob(id: String, blobUrl: String, tabId: String, fileName: String) {
        val job = BlobDownloadJob(
            appContext = core.appContext,
            id = id,
            tabId = tabId,
            blobUrl = blobUrl,
            fileName = fileName,
            scope = core.scope,
            onProgress = { bytes, total -> core.publishProgress(id, bytes, total) },
            onFinished = { kind, file, message -> handleBlobFinished(id, kind, file, message) }
        )
        blobJobs[id] = job
        core.updateStatus(id, DownloadStatus.DOWNLOADING)
        job.start()
    }

    /** Routes bridge events from the page to the matching blob job. */
    fun handleBlobEvent(tabId: String, event: BlobEvent) {
        when (event) {
            is BlobEvent.Start -> blobJobs[event.jobKey]?.onTotal(event.totalBytes)
            is BlobEvent.Chunk -> blobJobs[event.jobKey]?.onChunk(event.base64)
            is BlobEvent.Done -> blobJobs[event.jobKey]?.onDone()
            is BlobEvent.Error -> blobJobs[event.jobKey]?.onError(event.message)
            is BlobEvent.Found -> Unit // candidate discovery, not a transfer
        }
    }

    private fun handleBlobFinished(
        id: String,
        kind: BlobDownloadJob.Kind,
        file: File?,
        message: String?
    ) {
        blobJobs.remove(id)
        core.scope.launch {
            val record = core.dao.getDownloadById(id) ?: return@launch
            when (kind) {
                BlobDownloadJob.Kind.COMPLETED -> {
                    if (file != null) {
                        core.dao.insertDownload(
                            record.copy(
                                status = DownloadStatus.COMPLETED,
                                filePath = file.absolutePath,
                                downloadedBytes = file.length(),
                                totalBytes = file.length(),
                                errorMessage = null
                            )
                        )
                        onCompleted(id)
                    }
                    HlsNotifier.finished(core.appContext, id, record.fileName, success = true, message = null)
                }

                BlobDownloadJob.Kind.FAILED -> {
                    if (!core.stoppedIds.contains(id)) {
                        core.dao.insertDownload(
                            record.copy(
                                status = DownloadStatus.FAILED,
                                errorMessage = message ?: "Blob download failed"
                            )
                        )
                        HlsNotifier.finished(core.appContext, id, record.fileName, success = false, message = message)
                    }
                }

                BlobDownloadJob.Kind.CANCELLED -> Unit // status already set by cancel()
            }
            core.removeProgress(id)
        }
    }

    /** Tab that owns the blob job, for re-injecting the fetch script on resume. */
    fun blobJobTabId(id: String): String? = blobJobs[id]?.tabId

    /** True when a blob job owns this id (then it has been paused). */
    fun pauseIfManaging(id: String): Boolean {
        val job = blobJobs[id] ?: return false
        job.pause()
        core.updateStatus(id, DownloadStatus.PAUSED)
        return true
    }

    /** Resume path from [DownloadCoordinator.resume]; prologue already ran. */
    fun resumeBlob(record: DownloadRecord) {
        val id = record.id
        if (blobJobs.containsKey(id)) {
            blobJobs[id]?.resume()
            core.updateStatus(id, DownloadStatus.DOWNLOADING)
        } else {
            // Process died since pause; recreate into the active tab
            startBlobJob(id, record.url, "", record.fileName)
        }
    }

    fun cancel(id: String) {
        blobJobs.remove(id)?.cancel()
    }

    fun cancelAll() {
        blobJobs.values.toList().forEach { it.cancel() }
    }
}
