package com.diego.kiki.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.diego.kiki.data.AppDatabase
import com.diego.kiki.data.DownloadDao
import com.diego.kiki.data.DownloadRecord
import com.diego.kiki.data.DownloadStatus
import com.diego.kiki.hls.HlsDownloadJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloadCoordinator private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val dao: DownloadDao = AppDatabase.getDatabase(appContext).downloadDao()
    private val workManager = WorkManager.getInstance(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json { ignoreUnknownKeys = true }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _activeProgress =
        MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val activeProgress: StateFlow<Map<String, DownloadProgress>> =
        _activeProgress.asStateFlow()

    val allDownloads: Flow<List<DownloadRecord>> = dao.getAllDownloads()

    /** Ids paused/cancelled by the user while the worker may still emit callbacks. */
    private val stoppedIds = ConcurrentHashMap.newKeySet<String>()
    private val requestCache = ConcurrentHashMap<String, DownloadRequest>()
    private val lastRoomWriteAt = ConcurrentHashMap<String, Long>()
    private val hlsJobs = ConcurrentHashMap<String, HlsDownloadJob>()
    private val lastHlsNotifyAt = ConcurrentHashMap<String, Long>()
    private val blobJobs = ConcurrentHashMap<String, BlobDownloadJob>()

    init {
        HlsNotifier.ensureChannel(appContext)
        // Downloads interrupted by process death; workers re-run and flip these back if they resume.
        scope.launch {
            dao.resetInterrupted()
        }
    }

    fun enqueue(request: DownloadRequest): String {
        val id = UUID.randomUUID().toString()
        val record = DownloadRecord(
            id = id,
            url = request.url,
            fileName = request.fileName,
            filePath = "",
            mimeType = request.mimeType,
            totalBytes = -1,
            downloadedBytes = 0,
            status = DownloadStatus.QUEUED,
            downloadType = TYPE_DIRECT
        )
        scope.launch {
            dao.insertDownload(record)
        }
        requestCache[id] = request
        _activeProgress.update {
            it + (id to DownloadProgress(0, -1, DownloadStatus.QUEUED))
        }
        enqueueWork(id, request)
        return id
    }

    /**
     * Starts an HLS → MP4 pipeline job for the given playlist URL.
     * fileName should already end in .mp4.
     */
    fun enqueueHls(url: String, headers: Map<String, String>, fileName: String): String {
        val id = UUID.randomUUID().toString()
        val record = DownloadRecord(
            id = id,
            url = url,
            fileName = fileName,
            filePath = "",
            mimeType = "video/mp4",
            totalBytes = -1,
            downloadedBytes = 0,
            status = DownloadStatus.QUEUED,
            downloadType = TYPE_HLS
        )
        scope.launch {
            dao.insertDownload(record)
        }
        requestCache[id] = DownloadRequest(url, headers, fileName, "video/mp4")
        _activeProgress.update {
            it + (id to DownloadProgress(0, -1, DownloadStatus.QUEUED))
        }
        startHlsJob(id, url, headers, fileName)
        return id
    }

    private fun startHlsJob(id: String, url: String, headers: Map<String, String>, fileName: String) {
        val job = HlsDownloadJob(
            appContext = appContext,
            id = id,
            playlistUrl = url,
            baseHeaders = headers,
            client = client,
            scope = scope,
            onProgress = { done, total ->
                publishProgress(id, done.toLong(), total.toLong())
                val now = System.currentTimeMillis()
                val last = lastHlsNotifyAt[id] ?: 0L
                if (now - last >= HLS_NOTIFY_INTERVAL_MS) {
                    lastHlsNotifyAt[id] = now
                    HlsNotifier.progress(appContext, id, fileName, done, total)
                }
            },
            onFinished = { outcome -> handleHlsFinished(id, outcome) }
        )
        hlsJobs[id] = job
        updateStatus(id, DownloadStatus.DOWNLOADING)
        job.start()
    }

    /**
     * Registers a page-side blob transfer. Returns the download id, which is
     * also the JS-side jobKey used for BlobEvents.
     */
    fun enqueueBlob(
        blobUrl: String,
        tabId: String,
        headers: Map<String, String>,
        fileName: String
    ): String {
        val id = UUID.randomUUID().toString()
        val record = DownloadRecord(
            id = id,
            url = blobUrl,
            fileName = fileName,
            filePath = "",
            mimeType = "video/mp4",
            totalBytes = -1,
            downloadedBytes = 0,
            status = DownloadStatus.QUEUED,
            downloadType = TYPE_BLOB
        )
        scope.launch {
            dao.insertDownload(record)
        }
        requestCache[id] = DownloadRequest(blobUrl, headers, fileName, "video/mp4")
        _activeProgress.update {
            it + (id to DownloadProgress(0, -1, DownloadStatus.QUEUED))
        }
        startBlobJob(id, blobUrl, tabId, fileName)
        return id
    }

    private fun startBlobJob(id: String, blobUrl: String, tabId: String, fileName: String) {
        val job = BlobDownloadJob(
            appContext = appContext,
            id = id,
            tabId = tabId,
            blobUrl = blobUrl,
            fileName = fileName,
            scope = scope,
            onProgress = { bytes, total -> publishProgress(id, bytes, total) },
            onFinished = { kind, file, message -> handleBlobFinished(id, kind, file, message) }
        )
        blobJobs[id] = job
        updateStatus(id, DownloadStatus.DOWNLOADING)
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
        scope.launch {
            val record = dao.getDownloadById(id) ?: return@launch
            when (kind) {
                BlobDownloadJob.Kind.COMPLETED -> {
                    if (file != null) {
                        dao.insertDownload(
                            record.copy(
                                status = DownloadStatus.COMPLETED,
                                filePath = file.absolutePath,
                                downloadedBytes = file.length(),
                                totalBytes = file.length(),
                                errorMessage = null
                            )
                        )
                    }
                    HlsNotifier.finished(appContext, id, record.fileName, success = true, message = null)
                }

                BlobDownloadJob.Kind.FAILED -> {
                    if (!stoppedIds.contains(id)) {
                        dao.insertDownload(
                            record.copy(
                                status = DownloadStatus.FAILED,
                                errorMessage = message ?: "Blob download failed"
                            )
                        )
                        HlsNotifier.finished(appContext, id, record.fileName, success = false, message = message)
                    }
                }

                BlobDownloadJob.Kind.CANCELLED -> Unit // status already set by cancel()
            }
            _activeProgress.update { it - id }
            lastRoomWriteAt.remove(id)
        }
    }

    /** Tab that owns the blob job, for re-injecting the fetch script on resume. */
    fun blobJobTabId(id: String): String? = blobJobs[id]?.tabId

    private fun handleHlsFinished(id: String, outcome: HlsDownloadJob.Outcome) {        hlsJobs.remove(id)
        lastHlsNotifyAt.remove(id)
        scope.launch {
            val record = dao.getDownloadById(id)
            val fileName = record?.fileName ?: ""
            when (outcome.kind) {
                HlsDownloadJob.Kind.COMPLETED_MP4, HlsDownloadJob.Kind.COMPLETED_TS_FALLBACK -> {
                    val file = outcome.finalFile
                    if (record != null && file != null) {
                        dao.insertDownload(
                            record.copy(
                                status = DownloadStatus.COMPLETED,
                                filePath = file.absolutePath,
                                downloadedBytes = file.length(),
                                totalBytes = file.length(),
                                errorMessage = outcome.message
                            )
                        )
                    }
                    HlsNotifier.finished(appContext, id, fileName, success = true, message = outcome.message)
                }

                HlsDownloadJob.Kind.FAILED -> {
                    if (record != null) {
                        dao.insertDownload(
                            record.copy(
                                status = DownloadStatus.FAILED,
                                errorMessage = outcome.message
                            )
                        )
                    }
                    HlsNotifier.finished(appContext, id, fileName, success = false, message = outcome.message)
                }

                HlsDownloadJob.Kind.PAUSED -> {
                    updateStatus(id, DownloadStatus.PAUSED)
                }

                HlsDownloadJob.Kind.CANCELLED -> {
                    updateStatus(id, DownloadStatus.CANCELLED)
                }
            }
            if (outcome.kind != HlsDownloadJob.Kind.PAUSED) {
                _activeProgress.update { it - id }
            }
        }
    }

    fun pause(id: String) {
        val blobJob = blobJobs[id]
        if (blobJob != null) {
            blobJob.pause()
            updateStatus(id, DownloadStatus.PAUSED)
            return
        }
        val hlsJob = hlsJobs[id]
        if (hlsJob != null) {
            hlsJob.pause()
            updateStatus(id, DownloadStatus.PAUSED)
            return
        }
        stoppedIds.add(id)
        workManager.cancelUniqueWork(uniqueWorkName(id))
        updateStatus(id, DownloadStatus.PAUSED)
    }

    fun resume(id: String) {
        stoppedIds.remove(id)
        scope.launch {
            val record = dao.getDownloadById(id) ?: return@launch
            val request = requestCache[id] ?: DownloadRequest(
                url = record.url,
                headers = emptyMap(),
                fileName = record.fileName,
                mimeType = record.mimeType
            )
            requestCache[id] = request
            updateStatus(id, DownloadStatus.QUEUED)
            _activeProgress.update {
                it + (id to DownloadProgress(record.downloadedBytes, record.totalBytes, DownloadStatus.QUEUED))
            }
            if (record.downloadType == TYPE_HLS) {
                startHlsJob(id, record.url, emptyMap(), record.fileName)
            } else if (record.downloadType == TYPE_BLOB) {
                if (blobJobs.containsKey(id)) {
                    blobJobs[id]?.resume()
                    updateStatus(id, DownloadStatus.DOWNLOADING)
                } else {
                    // Process died since pause; recreate into the active tab
                    startBlobJob(id, record.url, "", record.fileName)
                }
            } else {
                enqueueWork(id, request, replace = true)
            }
        }
    }

    fun cancel(id: String) {
        stoppedIds.add(id)
        scope.launch {
            val record = dao.getDownloadById(id)
            if (record?.downloadType == TYPE_BLOB) {
                blobJobs.remove(id)?.cancel()
            } else if (record?.downloadType == TYPE_HLS) {
                hlsJobs.remove(id)?.cancel()
            } else {
                workManager.cancelUniqueWork(uniqueWorkName(id))
                DownloadStorage.partFile(appContext, id).delete()
            }
            updateStatus(id, DownloadStatus.CANCELLED)
            _activeProgress.update { it - id }
        }
    }

    fun delete(record: DownloadRecord) {
        stoppedIds.add(record.id)
        workManager.cancelUniqueWork(uniqueWorkName(record.id))
        scope.launch {
            val file = record.filePath.takeIf { it.isNotBlank() }?.let { File(it) }
            file?.delete()
            DownloadStorage.partFile(appContext, record.id).delete()
            dao.deleteDownloadById(record.id)
            _activeProgress.update { it - record.id }
        }
    }

    /**
     * Cancels every active job, deletes all app-private download files and
     * part files, and clears the list. Exported MediaStore copies are kept.
     */
    fun clearAll() {
        scope.launch(Dispatchers.IO) {
            val records = dao.getAllOnce()
            records.forEach { record ->
                stoppedIds.add(record.id)
                workManager.cancelUniqueWork(uniqueWorkName(record.id))
                record.filePath.takeIf { it.isNotBlank() }?.let { path ->
                    try {
                        File(path).delete()
                    } catch (_: Exception) {
                    }
                }
                try {
                    DownloadStorage.partFile(appContext, record.id).delete()
                } catch (_: Exception) {
                }
            }
            blobJobs.values.toList().forEach { it.cancel() }
            hlsJobs.values.toList().forEach { it.cancel() }
            requestCache.clear()
            _activeProgress.update { emptyMap() }
            dao.deleteAll()
        }
    }

    fun getJob(id: String): MediaDownloadJob? {
        val current = _activeProgress.value[id] ?: return null
        return JobHandle(id, requestCache[id]?.url ?: "", current.status)
    }

    // Called from DownloadWorker -------------------------------------------------

    fun markRunning(id: String) {
        stoppedIds.remove(id)
        updateStatus(id, DownloadStatus.DOWNLOADING)
    }

    fun canRetry(id: String): Boolean = !stoppedIds.contains(id)

    fun publishProgress(id: String, downloaded: Long, total: Long) {
        _activeProgress.update { current ->
            val existing = current[id] ?: DownloadProgress()
            current + (id to existing.copy(
                downloadedBytes = downloaded,
                totalBytes = if (total > 0) total else existing.totalBytes,
                status = DownloadStatus.DOWNLOADING
            ))
        }
        val now = System.currentTimeMillis()
        val last = lastRoomWriteAt[id] ?: 0L
        if (now - last >= ROOM_WRITE_INTERVAL_MS) {
            lastRoomWriteAt[id] = now
            scope.launch {
                dao.getDownloadById(id)?.let { record ->
                    dao.updateProgress(
                        id,
                        DownloadStatus.DOWNLOADING,
                        downloaded,
                        if (total > 0) total else record.totalBytes
                    )
                }
            }
        }
    }

    fun finalizeDownload(id: String, status: DownloadStatus, errorMessage: String? = null) {
        scope.launch {
            val record = dao.getDownloadById(id) ?: return@launch

            if (status == DownloadStatus.FAILED && stoppedIds.contains(id)) {
                // User already paused/cancelled; do not override their choice
                return@launch
            }

            if (status == DownloadStatus.COMPLETED) {
                val finalFile = moveToDownloads(id, record.fileName)
                if (finalFile == null) {
                    dao.insertDownload(
                        record.copy(status = DownloadStatus.FAILED, errorMessage = "Could not finalize file")
                    )
                    _activeProgress.update { it - id }
                    return@launch
                }
                dao.insertDownload(
                    record.copy(
                        status = DownloadStatus.COMPLETED,
                        filePath = finalFile.absolutePath,
                        downloadedBytes = finalFile.length(),
                        totalBytes = finalFile.length()
                    )
                )
            } else if (status == DownloadStatus.FAILED) {
                dao.insertDownload(
                    record.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = errorMessage ?: "Download failed"
                    )
                )
            } else {
                dao.updateProgress(id, status, record.downloadedBytes, record.totalBytes)
            }
            _activeProgress.update { it - id }
            stoppedIds.remove(id)
            lastRoomWriteAt.remove(id)
        }
    }

    // Internals ------------------------------------------------------------------

    private fun enqueueWork(id: String, request: DownloadRequest, replace: Boolean = false) {
        val data = workDataOf(
            DownloadWorker.KEY_ID to id,
            DownloadWorker.KEY_URL to request.url,
            DownloadWorker.KEY_FILE_NAME to request.fileName,
            DownloadWorker.KEY_MIME_TYPE to request.mimeType,
            DownloadWorker.KEY_HEADERS to json.encodeToString(request.headers)
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

    private fun updateStatus(id: String, status: DownloadStatus) {
        _activeProgress.update { current ->
            val existing = current[id]
            if (existing != null) {
                current + (id to existing.copy(status = status))
            } else {
                current
            }
        }
        scope.launch {
            dao.updateStatus(id, status)
        }
    }

    private fun moveToDownloads(id: String, fileName: String): File? {
        return try {
            val dir = DownloadStorage.downloadsDir(appContext)
            val partFile = DownloadStorage.partFile(appContext, id)
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

    private fun uniqueWorkName(id: String) = "kiki_dl_$id"

    private inner class JobHandle(
        override val id: String,
        override val url: String,
        private val fallbackStatus: DownloadStatus
    ) : MediaDownloadJob {
        override val progress: Flow<DownloadProgress> =
            _activeProgress.map { map -> map[id] ?: DownloadProgress(status = fallbackStatus) }
        override fun pause() = this@DownloadCoordinator.pause(id)
        override fun resume() = this@DownloadCoordinator.resume(id)
        override fun cancel() = this@DownloadCoordinator.cancel(id)
    }

    companion object {
        const val TYPE_DIRECT = "DIRECT"
        const val TYPE_HLS = "HLS"
        const val TYPE_BLOB = "BLOB"
        const val TYPE_SCREEN = "SCREEN"

        private const val ROOM_WRITE_INTERVAL_MS = 1000L
        private const val HLS_NOTIFY_INTERVAL_MS = 1500L

        @Volatile
        private var INSTANCE: DownloadCoordinator? = null

        fun getInstance(context: Context): DownloadCoordinator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadCoordinator(context).also { INSTANCE = it }
            }
        }
    }
}
