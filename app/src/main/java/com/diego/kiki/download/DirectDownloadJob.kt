package com.diego.kiki.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Streams a single URL to a .part file with HTTP Range resume support.
 * Kept as a plain engine so the HLS pipeline (Phase 4) can reuse the same patterns.
 */
class DirectDownloadJob(
    private val client: OkHttpClient,
    private val request: DownloadRequest,
    private val partFile: File,
    private val onProgress: (downloaded: Long, total: Long) -> Unit
) {

    sealed interface Outcome {
        data object Completed : Outcome
        data object Stopped : Outcome
        data class Failed(val message: String) : Outcome
    }

    suspend fun execute(): Outcome = withContext(Dispatchers.IO) {
        val existingBytes = if (partFile.exists()) partFile.length() else 0L

        val httpRequest = Request.Builder()
            .url(request.url)
            .apply {
                request.headers.forEach { (name, value) -> header(name, value) }
                if (existingBytes > 0) header("Range", "bytes=$existingBytes-")
            }
            .build()

        val response = try {
            client.newCall(httpRequest).execute()
        } catch (e: IOException) {
            return@withContext Outcome.Failed(e.message ?: "Network error")
        }

        response.use { resp ->
            var append = false
            var total = -1L

            when {
                resp.code == 206 -> {
                    append = true
                    total = resp.header("Content-Range")
                        ?.substringAfter('/')?.toLongOrNull() ?: -1L
                }
                resp.code == 200 -> {
                    append = false
                    total = resp.body?.contentLength() ?: -1L
                }
                else -> return@withContext Outcome.Failed("HTTP ${resp.code}")
            }

            if (!append && existingBytes > 0) {
                partFile.delete()
            }

            val body = resp.body ?: return@withContext Outcome.Failed("Empty response body")

            try {
                var downloaded = if (append) existingBytes else 0L
                var lastPublishAt = 0L
                var lastPublishBytes = downloaded

                body.byteStream().use { input ->
                    FileOutputStream(partFile, append).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            if (now - lastPublishAt > 250 || downloaded - lastPublishBytes > 512 * 1024) {
                                lastPublishAt = now
                                lastPublishBytes = downloaded
                                onProgress(downloaded, total)
                            }
                        }
                        output.fd.sync()
                    }
                }
                onProgress(downloaded, if (total > 0) total else downloaded)
                Outcome.Completed
            } catch (e: IOException) {
                Outcome.Failed(e.message ?: "I/O error")
            }
        }
    }
}
