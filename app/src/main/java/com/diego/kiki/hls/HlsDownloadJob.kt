package com.diego.kiki.hls

import android.content.Context
import com.diego.kiki.download.DownloadStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Orchestrates the full HLS → MP4 pipeline: playlist parse → variant pick
 * (highest BANDWIDTH) → live/DRM refusal → segment fetch (4 parallel, AES-128) →
 * concat → stream-copy remux → move to the app-private Downloads dir.
 * Remux failure keeps the assembled .ts (still playable in VLC/MX Player).
 */
class HlsDownloadJob(
    private val appContext: Context,
    val id: String,
    private val playlistUrl: String,
    private val baseHeaders: Map<String, String>,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val onProgress: (done: Int, total: Int) -> Unit,
    private val onFinished: (Outcome) -> Unit
) {

    enum class Kind { COMPLETED_MP4, COMPLETED_TS_FALLBACK, FAILED, PAUSED, CANCELLED }

    data class Outcome(
        val kind: Kind,
        val finalFile: File? = null,
        val message: String? = null
    )

    private var job: Job? = null

    @Volatile
    private var paused = false

    fun start() {
        job = scope.launch { run() }
    }

    fun pause() {
        paused = true
        job?.cancel()
    }

    fun cancel() {
        paused = false
        job?.cancel()
        scope.launch {
            workDir().deleteRecursively()
        }
    }

    private fun workDir(): File {
        val dir = File(File(appContext.cacheDir, "hls"), id)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private suspend fun run() {
        try {
            onProgress(0, 0)
            val dir = workDir()

            // 1. Fetch + parse playlist (master or media)
            val playlistText = fetchText(playlistUrl)
            var parsed = M3U8Parser.parse(playlistText, playlistUrl)

            // 2. Master playlist → highest-bandwidth variant
            if (parsed is HlsPlaylist.Master) {
                val variant = parsed.variants.maxByOrNull { it.bandwidth }
                    ?: throw IOException("Master playlist has no variants")
                parsed = M3U8Parser.parse(fetchText(variant.url), variant.url)
            }
            val media = (parsed as HlsPlaylist.Media).playlist

            // 3. Refuse live streams (no ENDLIST)
            if (media.isLive) {
                onFinished(Outcome(Kind.FAILED, message = "Live stream — only VOD streams can be saved"))
                return
            }

            // 4. Refuse DRM / unsupported encryption
            val drmKey = media.segments.mapNotNull { it.key }
                .firstOrNull { it.isDrmOrUnsupported }
            if (drmKey != null) {
                onFinished(Outcome(Kind.FAILED, message = "DRM protected stream cannot be saved"))
                return
            }
            if (media.segments.isEmpty()) {
                onFinished(Outcome(Kind.FAILED, message = "Playlist contains no segments"))
                return
            }

            // 5. Fetch init + segments (resume skips existing parts)
            val fetcher = HlsSegmentFetcher(client, baseHeaders, dir)
            fetcher.fetchInitSegment(media)
            val total = media.segments.size
            fetcher.fetchSegments(media.segments) { done ->
                onProgress(done, total)
            }
            fetcher.requireAllParts(media.segments)

            // 6. Concatenate parts in order
            val concatTs = File(dir, "concat.ts")
            concatParts(dir, media, concatTs)

            // 7. Remux to MP4 (stream copy). On failure keep the .ts.
            val baseName = outputBaseName()
            val targetDir = DownloadStorage.downloadsDir(appContext)
            val remuxOut = File(dir, "$baseName.mp4")

            when (val remux = Remuxer.remuxTsToMp4(concatTs, remuxOut)) {
                is Remuxer.Result.Success -> {
                    val final = File(targetDir, DownloadStorage.uniqueFileName(targetDir, "$baseName.mp4"))
                    remux.output.copyTo(final, overwrite = false)
                    dir.deleteRecursively()
                    onFinished(Outcome(Kind.COMPLETED_MP4, finalFile = final))
                }

                is Remuxer.Result.Failed -> {
                    val tsFinal = File(targetDir, DownloadStorage.uniqueFileName(targetDir, "$baseName.ts"))
                    concatTs.copyTo(tsFinal, overwrite = false)
                    dir.deleteRecursively()
                    onFinished(
                        Outcome(Kind.COMPLETED_TS_FALLBACK, finalFile = tsFinal, message = remux.message)
                    )
                }
            }
        } catch (e: CancellationException) {
            onFinished(if (paused) Outcome(Kind.PAUSED) else Outcome(Kind.CANCELLED))
        } catch (e: Exception) {
            onFinished(Outcome(Kind.FAILED, message = e.message ?: "HLS download failed"))
        }
    }

    private fun concatParts(dir: File, media: HlsMediaPlaylist, output: File) {
        val init = File(dir, "init.part")
        output.outputStream().use { out ->
            if (media.initSegmentUrl != null && init.exists()) {
                init.inputStream().use { it.copyTo(out, BUFFER_SIZE) }
            }
            media.segments.indices.forEach { index ->
                val part = File(dir, String.format("seg_%06d.part", index))
                if (!part.exists()) throw IOException("Missing part $index")
                part.inputStream().use { it.copyTo(out, BUFFER_SIZE) }
            }
            out.fd.sync()
        }
    }

    private fun outputBaseName(): String {
        val raw = DownloadStorage.fileNameFromUrl(playlistUrl)
        val withoutExt = raw.substringBeforeLast('.', missingDelimiterValue = raw)
        return DownloadStorage.sanitizeFileName(
            withoutExt.ifBlank { "stream" }
        )
    }

    private suspend fun fetchText(url: String): String {
        val request = okhttp3.Request.Builder()
            .url(url)
            .apply { baseHeaders.forEach { (name, value) -> header(name, value) } }
            .build()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} for playlist $url")
                }
                response.body?.string() ?: throw IOException("Empty playlist body")
            }
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
    }
}
