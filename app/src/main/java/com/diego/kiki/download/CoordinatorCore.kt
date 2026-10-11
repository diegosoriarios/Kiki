package com.diego.kiki.download

import android.content.Context
import com.diego.kiki.data.DownloadDao
import com.diego.kiki.data.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared download plumbing: the IO scope, progress publishing (with Room
 * write throttling), status updates and the user-stopped registry. Owned by
 * [DownloadCoordinator] and shared by the per-type job managers.
 */
class CoordinatorCore(
    val appContext: Context,
    val dao: DownloadDao
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val json = Json { ignoreUnknownKeys = true }

    private val _activeProgress =
        MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val activeProgress: StateFlow<Map<String, DownloadProgress>> =
        _activeProgress.asStateFlow()

    /** Ids paused/cancelled by the user while the worker may still emit callbacks. */
    val stoppedIds = ConcurrentHashMap.newKeySet<String>()

    /** Active download requests, for resume and handle lookup. */
    val requestCache = ConcurrentHashMap<String, DownloadRequest>()

    private val lastRoomWriteAt = ConcurrentHashMap<String, Long>()

    fun putQueued(id: String) {
        _activeProgress.update {
            it + (id to DownloadProgress(0, -1, DownloadStatus.QUEUED))
        }
    }

    fun putProgress(id: String, progress: DownloadProgress) {
        _activeProgress.update { it + (id to progress) }
    }

    fun removeProgress(id: String) {
        _activeProgress.update { it - id }
    }

    fun updateStatus(id: String, status: DownloadStatus) {
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

    fun clearProgress() {
        _activeProgress.update { emptyMap() }
    }

    companion object {
        private const val ROOM_WRITE_INTERVAL_MS = 1000L
    }
}
