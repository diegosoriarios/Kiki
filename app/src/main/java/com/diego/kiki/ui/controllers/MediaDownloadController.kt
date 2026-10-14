package com.diego.kiki.ui.controllers

import android.content.Context
import android.widget.Toast
import com.diego.kiki.browser.TabManager
import com.diego.kiki.browser.TabState
import com.diego.kiki.data.DownloadRecord
import com.diego.kiki.data.DownloadStatus
import com.diego.kiki.download.BulkItem
import com.diego.kiki.download.BulkItemsBuilder
import com.diego.kiki.download.DownloadCoordinator
import com.diego.kiki.download.DownloadRequest
import com.diego.kiki.download.DownloadStorage
import com.diego.kiki.download.MediaCandidate
import com.diego.kiki.download.MediaNaming
import com.diego.kiki.download.MediaSniffer
import com.diego.kiki.download.MediaTypes
import com.diego.kiki.download.VariantQuality
import com.diego.kiki.hls.HlsPlaylist
import com.diego.kiki.hls.M3U8Parser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * All media-capture download paths: HLS (with variant-quality metadata),
 * in-page blob transfers, direct media files, plus the bulk-download
 * checklist built from the tab's capture state.
 */
class MediaDownloadController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val tabManager: TabManager,
    private val coordinator: DownloadCoordinator,
    private val activeTab: StateFlow<TabState?>,
    private val activeTabId: StateFlow<String>,
    private val downloads: StateFlow<List<DownloadRecord>>,
    private val hideMediaSheet: () -> Unit
) {
    // HLS quality metadata -----------------------------------------------------------

    /**
     * Variant playlists with parsed quality (RESOLUTION/BANDWIDTH), keyed by
     * master playlist URL. Fetched lazily when the media sheet opens.
     */
    private val _variantQuality = MutableStateFlow<Map<String, List<VariantQuality>>>(emptyMap())

    val variantQuality: StateFlow<Map<String, List<VariantQuality>>> =
        _variantQuality.asStateFlow()

    private val variantQualityCache =
        java.util.concurrent.ConcurrentHashMap<String, List<VariantQuality>>()
    private var variantQualityJob: Job? = null

    fun refreshStreamQualities() {
        val capture = tabManager.segmentRegistry.captureFor(activeTabId.value)
        val pending = capture.streamGroups
            .filter { it.kind == MediaTypes.HLS }
            .map { it.best.url }
            .filter { !variantQualityCache.containsKey(it) }
        if (pending.isEmpty()) {
            _variantQuality.value = variantQualityCache.toMap()
            return
        }
        variantQualityJob?.cancel()
        variantQualityJob = scope.launch {
            pending.forEach { url ->
                val variants = fetchVariantQuality(url)
                if (variants.isNotEmpty()) {
                    variantQualityCache[url] = variants
                }
            }
            _variantQuality.value = variantQualityCache.toMap()
        }
    }

    fun getAllStreamQualities(): Map<String, Map<String, List<VariantQuality>>> {
        return mapOf(activeTabId.value to variantQualityCache.toMap())
    }

    /** Fetches + parses a master playlist; empty list on any failure. */
    private suspend fun fetchVariantQuality(masterUrl: String): List<VariantQuality> =
        withContext(Dispatchers.IO) {
            try {
                val tab = activeTab.value
                val headers = DownloadRequest.pageHeaders(
                    pageUrl = tab?.url,
                    targetUrl = masterUrl,
                    userAgent = tab?.webView?.settings?.userAgentString
                )
                val request = okhttp3.Request.Builder()
                    .url(masterUrl)
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .build()
                coordinator.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    val body = response.body?.string() ?: return@use emptyList()
                    val playlist = M3U8Parser.parse(body, masterUrl)
                    (playlist as? HlsPlaylist.Master)?.variants
                        ?.sortedByDescending { it.bandwidth }
                        ?.map { VariantQuality(it.url, it.bandwidth, it.resolution) }
                        ?: emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    // Single downloads -----------------------------------------------------------------

    fun downloadMediaCandidate(candidate: MediaCandidate) {
        if (candidate.type == MediaTypes.BLOB) {
            downloadBlob(candidate.url, candidate)
            return
        }
        if (candidate.type == MediaTypes.HLS) {
            downloadHlsStream(
                playlistUrl = candidate.url,
                qualityLabel = MediaNaming.qualityLabel(candidate.height),
                thumbnailUrl = candidate.posterUrl
            )
            return
        }
        startMediaDownload(
            url = candidate.url,
            thumbnailUrl = candidate.posterUrl,
            qualityLabel = MediaNaming.qualityLabel(candidate.height),
            sourceHost = MediaNaming.shortHost(candidate.url)
        )
        hideMediaSheet()
    }

    fun downloadHlsStream(
        playlistUrl: String,
        qualityLabel: String? = null,
        thumbnailUrl: String? = null
    ) {
        startHlsDownload(playlistUrl, qualityLabel, thumbnailUrl, showToast = true)
        hideMediaSheet()
    }

    private fun startHlsDownload(
        playlistUrl: String,
        qualityLabel: String? = null,
        thumbnailUrl: String? = null,
        showToast: Boolean
    ) {
        val tab = activeTab.value
        val headers = DownloadRequest.pageHeaders(
            pageUrl = tab?.url,
            targetUrl = playlistUrl,
            userAgent = tab?.webView?.settings?.userAgentString
        )
        // Skip generic playlist names (index/playlist/chunklist): use the nearest
        // meaningful path segment, else host + timestamp so files stay distinguishable
        val base = MediaNaming.smartBaseName(playlistUrl).ifBlank {
            "video-${MediaNaming.shortHost(playlistUrl).ifBlank { "stream" }}-${System.currentTimeMillis() / 1000}"
        }
        val fileName = DownloadStorage.sanitizeFileName(base) + ".mp4"
        coordinator.enqueueHls(
            url = playlistUrl,
            headers = headers,
            fileName = fileName,
            thumbnailUrl = thumbnailUrl,
            qualityLabel = qualityLabel,
            sourceHost = MediaNaming.shortHost(playlistUrl)
        )
        if (showToast) {
            Toast.makeText(context, "Stream download started", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Downloads a blob: URL by streaming it out of the page in chunks.
     * The source page must stay open until the transfer finishes.
     */
    fun downloadBlob(blobUrl: String, candidate: MediaCandidate? = null) {
        startBlobDownload(
            blobUrl = blobUrl,
            thumbnailUrl = candidate?.posterUrl,
            qualityLabel = MediaNaming.qualityLabel(candidate?.height ?: 0)
        )
        hideMediaSheet()
        Toast.makeText(
            context, "Download started — keep this page open", Toast.LENGTH_SHORT
        ).show()
    }

    private fun startBlobDownload(
        blobUrl: String,
        thumbnailUrl: String? = null,
        qualityLabel: String? = null
    ): String? {
        val tab = activeTab.value
        val tabId = tab?.id ?: return null
        val headers = DownloadRequest.pageHeaders(
            pageUrl = tab?.url,
            targetUrl = tab?.url ?: blobUrl
        )
        val host = try {
            android.net.Uri.parse(tab?.url).host
        } catch (_: Exception) {
            null
        } ?: "video"
        val fileName = DownloadStorage.sanitizeFileName("video-$host-${System.currentTimeMillis() / 1000}") + ".mp4"
        val id = coordinator.enqueueBlob(
            blobUrl = blobUrl,
            tabId = tabId,
            headers = headers,
            fileName = fileName,
            thumbnailUrl = thumbnailUrl,
            qualityLabel = qualityLabel,
            sourceHost = MediaNaming.shortHost(tab?.url ?: "").ifBlank { null }
        )
        injectBlobFetch(id, blobUrl, tabId)
        return id
    }

    private fun injectBlobFetch(jobKey: String, blobUrl: String, tabId: String) {
        val webView = tabManager.tabs.value.find { it.id == tabId }?.webView
            ?: tabManager.getActiveTab()?.webView
            ?: return
        webView.evaluateJavascript(MediaSniffer.fetchBlobScript(jobKey, blobUrl), null)
    }

    /** Context-menu download of an arbitrary media URL. */
    fun downloadUrl(url: String) = startMediaDownload(url)

    private fun startMediaDownload(
        url: String,
        thumbnailUrl: String? = null,
        qualityLabel: String? = null,
        sourceHost: String? = null,
        showToast: Boolean = true
    ) {
        val tab = activeTab.value
        val headers = DownloadRequest.pageHeaders(
            pageUrl = tab?.url,
            targetUrl = url,
            userAgent = tab?.webView?.settings?.userAgentString
        )
        var fileName = DownloadStorage.fileNameFromHeaders(url, null, null)
        // Generic names (index.mp4, video.mp4) get a host prefix so parallel
        // downloads stay distinguishable in the list
        val nameBase = fileName.substringBeforeLast('.')
        if (MediaNaming.isGenericToken(nameBase)) {
            val host = MediaNaming.shortHost(url).ifBlank { "media" }
            val ext = DownloadStorage.fileExtensionOf(fileName).ifBlank { "mp4" }
            fileName = DownloadStorage.sanitizeFileName("$host-$nameBase") + ".$ext"
        }
        coordinator.enqueue(
            DownloadRequest(
                url = url,
                headers = headers,
                fileName = fileName,
                mimeType = null,
                thumbnailUrl = thumbnailUrl,
                qualityLabel = qualityLabel,
                sourceHost = sourceHost ?: MediaNaming.shortHost(url).ifBlank { null }
            )
        )
        if (showToast) {
            Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
        }
    }

    /** Resumes a paused download; blob transfers need the fetch script re-injected. */
    fun resumeDownload(id: String) {
        coordinator.resume(id)
        val blobTabId = coordinator.blobJobTabId(id)
        if (blobTabId != null) {
            val record = downloads.value.find { it.id == id }
            if (record != null) {
                injectBlobFetch(id, record.url, blobTabId)
            }
        }
    }

    // Bulk download ----------------------------------------------------------------------

    /** Everything captured for the active tab as deduped bulk rows. */
    fun buildBulkItems(): List<BulkItem> =
        BulkItemsBuilder.build(
            capture = tabManager.segmentRegistry.captureFor(activeTabId.value),
            domCandidates = tabManager.mediaCandidates.value[activeTabId.value] ?: emptyList()
        )

    fun bulkDownload(items: List<BulkItem>) {
        if (items.isEmpty()) return
        var started = 0
        var blobs = 0
        items.forEach { item ->
            val (poster, quality, host) = bulkMetadataFor(item.url)
            when (item.type) {
                MediaTypes.HLS -> {
                    startHlsDownload(item.url, quality, poster, showToast = false)
                    started++
                }

                MediaTypes.BLOB -> {
                    if (startBlobDownload(item.url, poster, quality) != null) {
                        blobs++
                        started++
                    }
                }

                MediaTypes.DASH -> Unit // unsupported pipeline

                else -> {
                    startMediaDownload(item.url, poster, quality, host, showToast = false)
                    started++
                }
            }
        }
        Toast.makeText(
            context,
            if (blobs > 0) {
                "$started downloads started — keep this page open until blob videos finish"
            } else {
                "$started downloads started"
            },
            Toast.LENGTH_LONG
        ).show()
    }

    fun downloadAllVideos(
        allCandidates: Map<String, List<MediaCandidate>>,
        allCaptures: Map<String, com.diego.kiki.download.SegmentCandidateRegistry.TabMediaCapture>,
        allVariantQualities: Map<String, Map<String, List<VariantQuality>>>
    ) {
        if (allCandidates.isEmpty() && allCaptures.isEmpty()) return
        
        var started = 0
        var blobs = 0
        
        allCandidates.forEach { (tabId, candidates) ->
            val capture = allCaptures[tabId] ?: return@forEach
            val bulkItems = BulkItemsBuilder.build(capture, candidates)
            
            bulkItems.forEach { item ->
                val candidate = candidates.find { it.url == item.url }
                val poster = candidate?.posterUrl
                val quality = MediaNaming.qualityLabel(candidate?.height ?: 0)
                    ?: allVariantQualities[tabId]?.get(item.url)?.firstOrNull()?.let {
                        MediaNaming.qualityLabelFor(it.resolution, it.bandwidth)
                    }
                val host = MediaNaming.shortHost(item.url).ifBlank { null }
                
                when (item.type) {
                    MediaTypes.HLS -> {
                        startHlsDownloadForTab(tabId, item.url, quality, poster, showToast = false)
                        started++
                    }
                    MediaTypes.BLOB -> {
                        if (startBlobDownloadForTab(tabId, item.url, poster, quality) != null) {
                            blobs++
                            started++
                        }
                    }
                    MediaTypes.DASH -> Unit
                    else -> {
                        startMediaDownloadForTab(tabId, item.url, poster, quality, host, showToast = false)
                        started++
                    }
                }
            }
        }
        
        Toast.makeText(
            context,
            if (blobs > 0) {
                "$started downloads started — keep pages open until blob videos finish"
            } else {
                "$started downloads started"
            },
            Toast.LENGTH_LONG
        ).show()
    }
    
    private fun startHlsDownloadForTab(
        tabId: String,
        playlistUrl: String,
        qualityLabel: String? = null,
        thumbnailUrl: String? = null,
        showToast: Boolean
    ) {
        val tab = tabManager.tabs.value.find { it.id == tabId }
        val headers = DownloadRequest.pageHeaders(
            pageUrl = tab?.url,
            targetUrl = playlistUrl,
            userAgent = tab?.webView?.settings?.userAgentString
        )
        val base = MediaNaming.smartBaseName(playlistUrl).ifBlank {
            "video-${MediaNaming.shortHost(playlistUrl).ifBlank { "stream" }}-${System.currentTimeMillis() / 1000}"
        }
        val fileName = DownloadStorage.sanitizeFileName(base) + ".mp4"
        coordinator.enqueueHls(
            url = playlistUrl,
            headers = headers,
            fileName = fileName,
            thumbnailUrl = thumbnailUrl,
            qualityLabel = qualityLabel,
            sourceHost = MediaNaming.shortHost(playlistUrl)
        )
        if (showToast) {
            Toast.makeText(context, "Stream download started", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun startBlobDownloadForTab(
        tabId: String,
        blobUrl: String,
        thumbnailUrl: String? = null,
        qualityLabel: String? = null
    ): String? {
        val tab = tabManager.tabs.value.find { it.id == tabId } ?: return null
        val headers = DownloadRequest.pageHeaders(
            pageUrl = tab.url,
            targetUrl = tab.url ?: blobUrl
        )
        val host = try {
            android.net.Uri.parse(tab.url).host
        } catch (_: Exception) {
            null
        } ?: "video"
        val fileName = DownloadStorage.sanitizeFileName("video-$host-${System.currentTimeMillis() / 1000}") + ".mp4"
        val id = coordinator.enqueueBlob(
            blobUrl = blobUrl,
            tabId = tabId,
            headers = headers,
            fileName = fileName,
            thumbnailUrl = thumbnailUrl,
            qualityLabel = qualityLabel,
            sourceHost = MediaNaming.shortHost(tab.url ?: "").ifBlank { null }
        )
        injectBlobFetch(id, blobUrl, tabId)
        return id
    }
    
    private fun startMediaDownloadForTab(
        tabId: String,
        url: String,
        thumbnailUrl: String? = null,
        qualityLabel: String? = null,
        sourceHost: String? = null,
        showToast: Boolean = true
    ) {
        val tab = tabManager.tabs.value.find { it.id == tabId }
        val headers = DownloadRequest.pageHeaders(
            pageUrl = tab?.url,
            targetUrl = url,
            userAgent = tab?.webView?.settings?.userAgentString
        )
        var fileName = DownloadStorage.fileNameFromHeaders(url, null, null)
        val nameBase = fileName.substringBeforeLast('.')
        if (MediaNaming.isGenericToken(nameBase)) {
            val host = MediaNaming.shortHost(url).ifBlank { "media" }
            val ext = DownloadStorage.fileExtensionOf(fileName).ifBlank { "mp4" }
            fileName = DownloadStorage.sanitizeFileName("$host-$nameBase") + ".$ext"
        }
        coordinator.enqueue(
            DownloadRequest(
                url = url,
                headers = headers,
                fileName = fileName,
                mimeType = null,
                thumbnailUrl = thumbnailUrl,
                qualityLabel = qualityLabel,
                sourceHost = sourceHost ?: MediaNaming.shortHost(url).ifBlank { null }
            )
        )
        if (showToast) {
            Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Poster / quality / host for a bulk item, looked up from the tab's DOM
     * candidates and parsed HLS variants (whatever was captured at sniff time).
     */
    private fun bulkMetadataFor(url: String): Triple<String?, String?, String?> {
        val candidate = tabManager.mediaCandidates.value[activeTabId.value]
            ?.find { it.url == url }
        val quality = MediaNaming.qualityLabel(candidate?.height ?: 0)
            ?: variantQualityCache[url]?.firstOrNull()?.let {
                MediaNaming.qualityLabelFor(it.resolution, it.bandwidth)
            }
        val host = MediaNaming.shortHost(url).ifBlank {
            MediaNaming.shortHost(activeTab.value?.url ?: "")
        }
        return Triple(candidate?.posterUrl, quality, host.ifBlank { null })
    }

    // Thumbnails ---------------------------------------------------------------------------

    /**
     * One-shot local thumbnail extraction for completed downloads that predate
     * thumbnail support (the coordinator dedupes attempts per process run).
     */
    fun backfillThumbnail(record: DownloadRecord) {
        if (record.status != DownloadStatus.COMPLETED || record.thumbnailPath != null) return
        scope.launch(Dispatchers.IO) {
            coordinator.attachThumbnail(record.id)
        }
    }
}
