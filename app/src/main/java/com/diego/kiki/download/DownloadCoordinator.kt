package com.diego.kiki.download

import android.content.Context
import androidx.work.WorkManager
import com.diego.kiki.data.AppDatabase
import com.diego.kiki.data.DownloadDao
import com.diego.kiki.data.DownloadRecord
import com.diego.kiki.data.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Download facade. Owns the shared [CoordinatorCore] and delegates to the
 * per-type managers: [DirectJobManager] (WorkManager), [HlsJobManager]
 * (in-process HLS → MP4) and [BlobJobManager] (in-page blob transfers).
 */
class DownloadCoordinator private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val core = CoordinatorCore(
        appContext,
        AppDatabase.getDatabase(appContext).downloadDao()
    )
    private val dao: DownloadDao = core.dao

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val activeProgress = core.activeProgress

    val allDownloads: Flow<List<DownloadRecord>> = dao.getAllDownloads()

    private val workManager = WorkManager.getInstance(appContext)

    private val directManager = DirectJobManager(core, workManager) { id ->
        attachThumbnail(id)
    }
    private val hlsManager = HlsJobManager(core, client) { id ->
        attachThumbnail(id)
    }
    private val blobManager = BlobJobManager(core) { id ->
        attachThumbnail(id)
    }

    /** Ids already tried for local thumbnail extraction (one attempt per run). */
    private val thumbnailAttempted = ConcurrentHashMap.newKeySet<String>()

    init {
        HlsNotifier.ensureChannel(appContext)
        // Downloads interrupted by process death; workers re-run and flip these back if they resume.
        core.scope.launch {
            dao.resetInterrupted()
        }
    }

    fun enqueue(request: DownloadRequest): String = directManager.enqueue(request)

    /**
     * Starts an HLS → MP4 pipeline job for the given playlist URL.
     * fileName should already end in .mp4.
     */
    fun enqueueHls(
        url: String,
        headers: Map<String, String>,
        fileName: String,
        thumbnailUrl: String? = null,
        qualityLabel: String? = null,
        sourceHost: String? = null
    ): String = hlsManager.enqueueHls(url, headers, fileName, thumbnailUrl, qualityLabel, sourceHost)

    /**
     * Registers a page-side blob transfer. Returns the download id, which is
     * also the JS-side jobKey used for BlobEvents.
     */
    fun enqueueBlob(
        blobUrl: String,
        tabId: String,
        headers: Map<String, String>,
        fileName: String,
        thumbnailUrl: String? = null,
        qualityLabel: String? = null,
        sourceHost: String? = null
    ): String = blobManager.enqueueBlob(blobUrl, tabId, headers, fileName, thumbnailUrl, qualityLabel, sourceHost)

    /** Routes bridge events from the page to the matching blob job. */
    fun handleBlobEvent(tabId: String, event: BlobEvent) {
        blobManager.handleBlobEvent(tabId, event)
    }

    /** Tab that owns the blob job, for re-injecting the fetch script on resume. */
    fun blobJobTabId(id: String): String? = blobManager.blobJobTabId(id)

    fun pause(id: String) {
        if (blobManager.pauseIfManaging(id)) return
        if (hlsManager.pauseIfManaging(id)) return
        directManager.pause(id)
    }

    fun resume(id: String) {
        core.stoppedIds.remove(id)
        core.scope.launch {
            val record = dao.getDownloadById(id) ?: return@launch
            val request = core.requestCache[id] ?: DownloadRequest(
                url = record.url,
                headers = emptyMap(),
                fileName = record.fileName,
                mimeType = record.mimeType
            )
            core.requestCache[id] = request
            core.updateStatus(id, DownloadStatus.QUEUED)
            core.putProgress(
                id,
                DownloadProgress(record.downloadedBytes, record.totalBytes, DownloadStatus.QUEUED)
            )
            when (record.downloadType) {
                TYPE_HLS -> hlsManager.resumeHls(record)
                TYPE_BLOB -> blobManager.resumeBlob(record)
                else -> directManager.resumeDirect(id, request)
            }
        }
    }

    fun cancel(id: String) {
        core.stoppedIds.add(id)
        core.scope.launch {
            val record = dao.getDownloadById(id)
            if (record?.downloadType == TYPE_BLOB) {
                blobManager.cancel(id)
            } else if (record?.downloadType == TYPE_HLS) {
                hlsManager.cancel(id)
            } else {
                workManager.cancelUniqueWork(directManager.uniqueWorkName(id))
                DownloadStorage.partFile(appContext, id).delete()
            }
            core.updateStatus(id, DownloadStatus.CANCELLED)
            core.removeProgress(id)
        }
    }

    fun delete(record: DownloadRecord) {
        core.stoppedIds.add(record.id)
        workManager.cancelUniqueWork(directManager.uniqueWorkName(record.id))
        core.scope.launch {
            val file = record.filePath.takeIf { it.isNotBlank() }?.let { File(it) }
            file?.delete()
            record.thumbnailPath?.let { File(it).delete() }
            DownloadStorage.partFile(appContext, record.id).delete()
            dao.deleteDownloadById(record.id)
            core.removeProgress(record.id)
        }
    }

    /**
     * Cancels every active job, deletes all app-private download files and
     * part files, and clears the list. Exported MediaStore copies are kept.
     */
    fun clearAll() {
        core.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val records = dao.getAllOnce()
            records.forEach { record ->
                core.stoppedIds.add(record.id)
                workManager.cancelUniqueWork(directManager.uniqueWorkName(record.id))
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
                record.thumbnailPath?.let { path ->
                    try {
                        File(path).delete()
                    } catch (_: Exception) {
                    }
                }
            }
            blobManager.cancelAll()
            hlsManager.cancelAll()
            core.requestCache.clear()
            core.clearProgress()
            dao.deleteAll()
        }
    }

    fun getJob(id: String): MediaDownloadJob? {
        val current = core.activeProgress.value[id] ?: return null
        return JobHandle(id, core.requestCache[id]?.url ?: "", current.status)
    }

    /**
     * Extracts a local thumbnail from the finished file and stores its path on
     * the record. Idempotent; no-op for non-media files or failed extraction.
     * Called on every completion path (direct/HLS/blob) and from the page
     * recorder after it inserts its record.
     */
    suspend fun attachThumbnail(id: String) {
        val record = dao.getDownloadById(id) ?: return
        if (record.thumbnailPath != null) return
        if (!thumbnailAttempted.add(id)) return
        val file = record.filePath.takeIf { it.isNotBlank() }?.let { File(it) } ?: return
        if (!file.exists()) return
        val outFile = DownloadStorage.thumbnailFile(appContext, id)
        if (ThumbnailExtractor.extract(file, outFile, record.mimeType)) {
            dao.updateThumbnailPath(id, outFile.absolutePath)
        }
    }

    // Called from DownloadWorker -------------------------------------------------

    fun markRunning(id: String) = directManager.markRunning(id)

    fun canRetry(id: String): Boolean = directManager.canRetry(id)

    fun publishProgress(id: String, downloaded: Long, total: Long) {
        core.publishProgress(id, downloaded, total)
    }

    fun finalizeDownload(id: String, status: DownloadStatus, errorMessage: String? = null) {
        directManager.finalizeDownload(id, status, errorMessage)
    }

    private inner class JobHandle(
        override val id: String,
        override val url: String,
        private val fallbackStatus: DownloadStatus
    ) : MediaDownloadJob {
        override val progress: Flow<DownloadProgress> =
            core.activeProgress.map { map -> map[id] ?: DownloadProgress(status = fallbackStatus) }
        override fun pause() = this@DownloadCoordinator.pause(id)
        override fun resume() = this@DownloadCoordinator.resume(id)
        override fun cancel() = this@DownloadCoordinator.cancel(id)
    }

    companion object {
        const val TYPE_DIRECT = "DIRECT"
        const val TYPE_HLS = "HLS"
        const val TYPE_BLOB = "BLOB"
        const val TYPE_SCREEN = "SCREEN"

        @Volatile
        private var INSTANCE: DownloadCoordinator? = null

        fun getInstance(context: Context): DownloadCoordinator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadCoordinator(context).also { INSTANCE = it }
            }
        }
    }
}
