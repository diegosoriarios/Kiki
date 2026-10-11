package com.diego.kiki.download

import com.diego.kiki.data.DownloadRecord
import com.diego.kiki.data.DownloadStatus
import com.diego.kiki.hls.HlsDownloadJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/** HLS → MP4 pipeline job lifecycle (in-process, not WorkManager). */
class HlsJobManager(
    private val core: CoordinatorCore,
    private val client: OkHttpClient,
    private val onCompleted: suspend (String) -> Unit
) {
    private val hlsJobs = ConcurrentHashMap<String, HlsDownloadJob>()
    private val lastHlsNotifyAt = ConcurrentHashMap<String, Long>()

    fun enqueueHls(
        url: String,
        headers: Map<String, String>,
        fileName: String,
        thumbnailUrl: String? = null,
        qualityLabel: String? = null,
        sourceHost: String? = null
    ): String {
        val id = java.util.UUID.randomUUID().toString()
        val record = DownloadRecord(
            id = id,
            url = url,
            fileName = fileName,
            filePath = "",
            mimeType = "video/mp4",
            totalBytes = -1,
            downloadedBytes = 0,
            status = DownloadStatus.QUEUED,
            downloadType = DownloadCoordinator.TYPE_HLS,
            thumbnailUrl = thumbnailUrl,
            qualityLabel = qualityLabel,
            sourceHost = sourceHost
        )
        core.scope.launch {
            core.dao.insertDownload(record)
        }
        core.requestCache[id] = DownloadRequest(url, headers, fileName, "video/mp4")
        core.putQueued(id)
        startHlsJob(id, url, headers, fileName)
        return id
    }

    private fun startHlsJob(id: String, url: String, headers: Map<String, String>, fileName: String) {
        val job = HlsDownloadJob(
            appContext = core.appContext,
            id = id,
            playlistUrl = url,
            baseHeaders = headers,
            client = client,
            scope = core.scope,
            onProgress = { done, total ->
                core.publishProgress(id, done.toLong(), total.toLong())
                val now = System.currentTimeMillis()
                val last = lastHlsNotifyAt[id] ?: 0L
                if (now - last >= HLS_NOTIFY_INTERVAL_MS) {
                    lastHlsNotifyAt[id] = now
                    HlsNotifier.progress(core.appContext, id, fileName, done, total)
                }
            },
            onFinished = { outcome -> handleHlsFinished(id, outcome) }
        )
        hlsJobs[id] = job
        core.updateStatus(id, DownloadStatus.DOWNLOADING)
        job.start()
    }

    private fun handleHlsFinished(id: String, outcome: HlsDownloadJob.Outcome) {
        hlsJobs.remove(id)
        lastHlsNotifyAt.remove(id)
        core.scope.launch {
            val record = core.dao.getDownloadById(id)
            val fileName = record?.fileName ?: ""
            when (outcome.kind) {
                HlsDownloadJob.Kind.COMPLETED_MP4, HlsDownloadJob.Kind.COMPLETED_TS_FALLBACK -> {
                    val file = outcome.finalFile
                    if (record != null && file != null) {
                        core.dao.insertDownload(
                            record.copy(
                                status = DownloadStatus.COMPLETED,
                                filePath = file.absolutePath,
                                downloadedBytes = file.length(),
                                totalBytes = file.length(),
                                errorMessage = outcome.message
                            )
                        )
                        onCompleted(id)
                    }
                    HlsNotifier.finished(core.appContext, id, fileName, success = true, message = outcome.message)
                }

                HlsDownloadJob.Kind.FAILED -> {
                    if (record != null) {
                        core.dao.insertDownload(
                            record.copy(
                                status = DownloadStatus.FAILED,
                                errorMessage = outcome.message
                            )
                        )
                    }
                    HlsNotifier.finished(core.appContext, id, fileName, success = false, message = outcome.message)
                }

                HlsDownloadJob.Kind.PAUSED -> {
                    core.updateStatus(id, DownloadStatus.PAUSED)
                }

                HlsDownloadJob.Kind.CANCELLED -> {
                    core.updateStatus(id, DownloadStatus.CANCELLED)
                }
            }
            if (outcome.kind != HlsDownloadJob.Kind.PAUSED) {
                core.removeProgress(id)
            }
        }
    }

    /** True when an HLS job owns this id (then it has been paused). */
    fun pauseIfManaging(id: String): Boolean {
        val job = hlsJobs[id] ?: return false
        job.pause()
        core.updateStatus(id, DownloadStatus.PAUSED)
        return true
    }

    /** Resume path from [DownloadCoordinator.resume]; prologue already ran. */
    fun resumeHls(record: DownloadRecord) {
        startHlsJob(record.id, record.url, emptyMap(), record.fileName)
    }

    fun cancel(id: String) {
        hlsJobs.remove(id)?.cancel()
    }

    fun cancelAll() {
        hlsJobs.values.toList().forEach { it.cancel() }
    }

    companion object {
        private const val HLS_NOTIFY_INTERVAL_MS = 1500L
    }
}
