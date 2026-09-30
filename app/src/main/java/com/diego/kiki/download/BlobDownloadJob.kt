package com.diego.kiki.download

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Reassembles a page-side blob: URL streamed in base64 chunks through the JS
 * bridge. Requires the source page to stay open for the duration of the
 * transfer; a watchdog fails the job if chunks stop arriving.
 */
class BlobDownloadJob(
    private val appContext: Context,
    val id: String,
    val tabId: String,
    val blobUrl: String,
    private val fileName: String,
    private val scope: CoroutineScope,
    private val onProgress: (bytes: Long, total: Long) -> Unit,
    private val onFinished: (Kind, File?, String?) -> Unit
) {

    enum class Kind { COMPLETED, FAILED, CANCELLED }

    private val dataFile: File
    private val received = AtomicLong(0)

    @Volatile
    private var totalBytes = -1L

    @Volatile
    private var paused = false

    @Volatile
    private var finished = false

    @Volatile
    private var lastChunkAt = System.currentTimeMillis()

    private var out: FileOutputStream? = null
    private var watchdog: Job? = null

    init {
        val dir = File(appContext.cacheDir, "blobs")
        if (!dir.exists()) dir.mkdirs()
        dataFile = File(dir, "$id.bin")
        dataFile.delete()
    }

    fun start() {
        watchdog = scope.launch {
            while (!finished) {
                delay(WATCHDOG_INTERVAL_MS)
                if (System.currentTimeMillis() - lastChunkAt > WATCHDOG_TIMEOUT_MS) {
                    fail("Transfer interrupted — keep the source page open while downloading")
                    return@launch
                }
            }
        }
    }

    fun onTotal(total: Long) {
        if (finished) return
        totalBytes = total
        onProgress(received.get(), total)
    }

    /** Returns false when the job is no longer accepting chunks. */
    fun onChunk(base64: String): Boolean {
        if (finished || paused) return false
        val bytes = try {
            MediaSniffer.decodeBase64(base64)
        } catch (_: Exception) {
            return true // skip corrupt chunk; size check on Done will catch gaps
        }
        return try {
            val stream = synchronized(this) {
                out ?: FileOutputStream(dataFile, true).also { out = it }
            }
            synchronized(this) {
                stream.write(bytes)
            }
            received.addAndGet(bytes.size.toLong()).also {
                lastChunkAt = System.currentTimeMillis()
                onProgress(it, totalBytes)
            }
            true
        } catch (e: IOException) {
            fail(e.message ?: "Write failed")
            false
        }
    }

    fun onDone() {
        if (finished || paused) return
        finished = true
        watchdog?.cancel()
        scope.launch {
            var moveError: String? = null
            val finalFile = withContext(Dispatchers.IO) {
                try {
                    synchronized(this@BlobDownloadJob) { out?.flush() }
                    out?.close()
                    out = null
                    val sizeOk = totalBytes <= 0 || dataFile.length() == totalBytes
                    if (!sizeOk) {
                        dataFile.delete()
                        return@withContext null
                    }
                    val dir = DownloadStorage.downloadsDir(appContext)
                    val target = DownloadStorage.resolveUniqueTarget(dir, fileName)
                    when {
                        dataFile.renameTo(target) -> target
                        !target.exists() -> {
                            dataFile.copyTo(target)
                            dataFile.delete()
                            target
                        }
                        else -> {
                            moveError = "Could not save file (name conflict)"
                            null
                        }
                    }
                } catch (e: Exception) {
                    moveError = e.message ?: "File save failed"
                    null
                }
            }
            if (finalFile != null) {
                onFinished(Kind.COMPLETED, finalFile, null)
            } else {
                onFinished(
                    Kind.FAILED,
                    null,
                    moveError ?: "Blob transfer incomplete (page closed too early?)"
                )
            }
        }
    }

    fun onError(message: String) {
        if (finished) return
        fail(message)
    }

    fun pause() {
        paused = true
    }

    /** Restart the transfer from scratch; caller must re-inject the fetch script. */
    fun resume() {
        paused = false
        received.set(0)
        scope.launch(Dispatchers.IO) {
            synchronized(this@BlobDownloadJob) {
                out?.close()
                out = null
            }
            dataFile.delete()
            lastChunkAt = System.currentTimeMillis()
        }
    }

    fun cancel() {
        finished = true
        watchdog?.cancel()
        scope.launch(Dispatchers.IO) {
            synchronized(this@BlobDownloadJob) {
                out?.close()
                out = null
            }
            dataFile.delete()
        }
        onFinished(Kind.CANCELLED, null, null)
    }

    private fun fail(message: String) {
        if (finished) return
        finished = true
        watchdog?.cancel()
        scope.launch(Dispatchers.IO) {
            synchronized(this@BlobDownloadJob) {
                out?.close()
                out = null
            }
            dataFile.delete()
        }
        onFinished(Kind.FAILED, null, message)
    }

    companion object {
        private const val WATCHDOG_INTERVAL_MS = 5_000L
        private const val WATCHDOG_TIMEOUT_MS = 30_000L
    }
}
