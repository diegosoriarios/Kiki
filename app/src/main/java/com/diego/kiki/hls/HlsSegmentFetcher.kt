package com.diego.kiki.hls

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fetches HLS media playlist segments concurrently (4 parallel), writing numbered,
 * atomically-renamed part files so interrupted runs can resume by skipping complete
 * parts. Decrypts AES-128 segments via javax.crypto. Retries per segment with
 * exponential backoff.
 */
class HlsSegmentFetcher(
    private val client: OkHttpClient,
    private val baseHeaders: Map<String, String>,
    private val workDir: File,
    private val parallelism: Int = PARALLEL_SEGMENTS
) {

    private val keyCache = ConcurrentHashMap<String, ByteArray>()

    fun partFile(index: Int, isInit: Boolean = false): File {
        val name = if (isInit) "init.part" else String.format("seg_%06d.part", index)
        return File(workDir, name)
    }

    /**
     * Fetches the init segment (#EXT-X-MAP) if present. Returns the file or null.
     */
    suspend fun fetchInitSegment(playlist: HlsMediaPlaylist): File? {
        val url = playlist.initSegmentUrl ?: return null
        val target = partFile(-1, isInit = true)
        if (!target.exists()) {
            fetchToTempAndRename(url, playlist.initSegmentByterange, null, playlist.mediaSequence, target)
        }
        return target
    }

    /**
     * Fetches [segments] with limited parallelism. Complete part files are skipped
     * (resume). Throws on the first failure after per-segment retries are exhausted.
     */
    suspend fun fetchSegments(
        segments: List<HlsSegment>,
        onSegmentDone: (completedCount: Int) -> Unit
    ) = coroutineScope {
        val dispatcher = Dispatchers.IO.limitedParallelism(parallelism)
        val completed = AtomicInteger(0)

        segments.mapIndexed { index, segment ->
            async(dispatcher) {
                val target = partFile(index)
                if (!target.exists()) {
                    fetchToTempAndRename(
                        segment.url, segment.byterange, segment.key, segment.sequence, target
                    )
                }
                onSegmentDone(completed.incrementAndGet())
            }
        }.awaitAll()
    }

    /** Throws if any expected part file is missing (e.g. cancelled mid-run). */
    fun requireAllParts(segments: List<HlsSegment>) {
        val missing = segments.indices.filter { !partFile(it).exists() }
        if (missing.isNotEmpty()) {
            throw IOException(
                "Missing ${missing.size} segment(s), first missing index: ${missing.first()}"
            )
        }
    }

    private suspend fun fetchToTempAndRename(
        url: String,
        byterange: HlsByterange?,
        key: HlsKey?,
        sequence: Long,
        target: File
    ) {
        var lastError: Exception? = null
        repeat(MAX_SEGMENT_RETRIES) { attempt ->
            try {
                currentCoroutineContext().ensureActive()
                val bytes = fetchBytes(url, byterange)
                val plain = when {
                    key == null || key.isNone -> bytes
                    key.isAes128 -> {
                        val keyBytes = fetchKey(key.uri)
                        val iv = key.ivHex?.let { AesDecryptor.parseHexIv(it) }
                            ?: AesDecryptor.sequenceIv(sequence)
                        AesDecryptor.decrypt(bytes, keyBytes, iv)
                    }
                    else -> throw IOException("Unsupported encryption: ${key.method}")
                }

                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.writeBytes(plain)
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                delay(BACKOFF_BASE_MS shl attempt)
            }
        }
        throw IOException(
            "Segment fetch failed after $MAX_SEGMENT_RETRIES attempts: $url",
            lastError
        )
    }

    private suspend fun fetchBytes(url: String, byterange: HlsByterange?): ByteArray =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .apply {
                    baseHeaders.forEach { (name, value) -> header(name, value) }
                    if (byterange != null) {
                        val from = byterange.offset ?: 0L
                        val to = from + byterange.length - 1
                        header("Range", "bytes=$from-$to")
                    }
                }
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code != 200 && response.code != 206) {
                    throw IOException("HTTP ${response.code} for $url")
                }
                response.body?.bytes() ?: throw IOException("Empty body for $url")
            }
        }

    private suspend fun fetchKey(keyUrl: String?): ByteArray {
        if (keyUrl == null) throw IOException("AES-128 key URI missing")
        keyCache[keyUrl]?.let { return it }
        val bytes = fetchBytes(keyUrl, null)
        require(bytes.size == 16) { "AES-128 key must be 16 bytes, got ${bytes.size}" }
        keyCache[keyUrl] = bytes
        return bytes
    }

    companion object {
        const val PARALLEL_SEGMENTS = 4
        const val MAX_SEGMENT_RETRIES = 3
        const val BACKOFF_BASE_MS = 1000L
    }
}
