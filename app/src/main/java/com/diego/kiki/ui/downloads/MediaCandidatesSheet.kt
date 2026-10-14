package com.diego.kiki.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Stream
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diego.kiki.download.MediaCandidate
import com.diego.kiki.download.MediaNaming
import com.diego.kiki.download.MediaTypes
import com.diego.kiki.download.SegmentCandidateRegistry
import com.diego.kiki.download.VariantQuality
import com.diego.kiki.download.MediaNaming.qualityLabelFor
import com.diego.kiki.ui.TabDisplayInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaCandidatesSheet(
    candidates: List<MediaCandidate>,
    capture: SegmentCandidateRegistry.TabMediaCapture,
    variantQuality: Map<String, List<VariantQuality>> = emptyMap(),
    activeVideo: MediaCandidate? = null,
    pageTitle: String? = null,
    allMediaByTab: Map<String, List<MediaCandidate>> = emptyMap(),
    allCapturesByTab: Map<String, SegmentCandidateRegistry.TabMediaCapture> = emptyMap(),
    allVariantQualitiesByTab: Map<String, Map<String, List<VariantQuality>>> = emptyMap(),
    tabDisplayInfo: List<TabDisplayInfo> = emptyList(),
    onDownload: (MediaCandidate) -> Unit,
    onDownloadHls: (url: String, qualityLabel: String?, posterUrl: String?) -> Unit,
    onOpenBulk: () -> Unit,
    onDownloadAll: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column {
            ScrollableTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Current Page") },
                    icon = { Icon(Icons.Default.Visibility, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("All Videos") },
                    icon = { Icon(Icons.Default.Download, contentDescription = null) }
                )
            }

            when (selectedTab) {
                0 -> CurrentPageTab(
                    candidates = candidates,
                    capture = capture,
                    variantQuality = variantQuality,
                    activeVideo = activeVideo,
                    pageTitle = pageTitle,
                    onDownload = onDownload,
                    onDownloadHls = onDownloadHls,
                    onOpenBulk = onOpenBulk
                )
                1 -> AllVideosTab(
                    tabDisplayInfo = tabDisplayInfo,
                    allMediaByTab = allMediaByTab,
                    allCapturesByTab = allCapturesByTab,
                    allVariantQualitiesByTab = allVariantQualitiesByTab,
                    onDownload = onDownload,
                    onDownloadHls = onDownloadHls,
                    onDownloadAll = onDownloadAll
                )
            }
        }
    }
}

@Composable
private fun CurrentPageTab(
    candidates: List<MediaCandidate>,
    capture: SegmentCandidateRegistry.TabMediaCapture,
    variantQuality: Map<String, List<VariantQuality>>,
    activeVideo: MediaCandidate?,
    pageTitle: String?,
    onDownload: (MediaCandidate) -> Unit,
    onDownloadHls: (url: String, qualityLabel: String?, posterUrl: String?) -> Unit,
    onOpenBulk: () -> Unit
) {
    val playlistUrls = capture.playlists.map { it.url }.toSet()
    val streamHosts = capture.streamGroups.map { it.host }.toSet()
    val fileGroups = groupFileCandidates(candidates, playlistUrls)
    val fileUrls = fileGroups.map { it.best.url }.toSet()
    val mediaGroups = capture.mediaGroups.filter { it.url !in fileUrls }
    val standaloneSegments = capture.segmentGroups.filter { it.host !in streamHosts }
    val hasAnyContent = candidates.isNotEmpty() || !capture.isEmpty
    val posterByUrl = candidates
        .filter { !it.posterUrl.isNullOrEmpty() }
        .associate { it.url to it.posterUrl!! }

    val listState = rememberLazyListState()
    val activeVideoIndex = remember(activeVideo, candidates) {
        candidates.indexOfFirst { it.url == activeVideo?.url }
    }

    LaunchedEffect(activeVideoIndex) {
        if (activeVideoIndex >= 0) {
            listState.animateScrollToItem(activeVideoIndex)
        }
    }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Media on this page",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (hasAnyContent) {
                    Text(
                        text = buildSummaryLine(capture.streamGroups, fileGroups),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            if (hasAnyContent) {
                TextButton(onClick = onOpenBulk) {
                    Text("Download all…")
                }
            }
        }

        if (!hasAnyContent) {
            Text(
                text = "Nothing detected yet. Play the video and reopen this panel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                if (capture.streamGroups.isNotEmpty()) {
                    item(key = "streams_header") {
                        SectionHeader("Streams — saved as MP4")
                    }
                    items(capture.streamGroups, key = { "stream_" + it.best.url }) { group ->
                        val isActive = isStreamActive(group, activeVideo)
                        StreamRow(
                            group = group,
                            parsed = variantQuality[group.best.url].orEmpty(),
                            posterUrl = streamPoster(posterByUrl, group, activeVideo),
                            isActive = isActive,
                            pageTitle = pageTitle,
                            onDownloadHls = onDownloadHls
                        )
                    }
                }

                if (fileGroups.isNotEmpty()) {
                    item(key = "files_header") {
                        SectionHeader("Files on the page")
                    }
                    items(fileGroups, key = { "file_" + it.best.url }) { group ->
                        val savableHls = group.kind == MediaTypes.HLS
                        val isActive = group.best.url == activeVideo?.url
                        CandidateRow(
                            title = group.title,
                            detail = group.detail,
                            icon = group.icon,
                            posterUrl = group.posterUrl,
                            badge = group.badge,
                            isActive = isActive,
                            onClick = {
                                if (savableHls) {
                                    onDownloadHls(group.best.url, group.badge, group.posterUrl)
                                } else {
                                    onDownload(group.best)
                                }
                            },
                            enabled = group.enabled
                        )
                    }
                }

                if (mediaGroups.isNotEmpty()) {
                    item(key = "media_header") {
                        SectionHeader("Media requests")
                    }
                    items(mediaGroups, key = { "media_" + it.url }) { group ->
                        CandidateRow(
                            title = MediaNaming.displayTitle(group.url),
                            detail = rowDetail(
                                MediaTypes.label(group.kind),
                                MediaNaming.shortHost(group.url),
                                if (group.requestCount > 1) "${group.requestCount} requests" else null
                            ),
                            icon = iconForType(group.kind),
                            onClick = { onDownload(MediaCandidate(group.url, group.kind)) }
                        )
                    }
                }

                if (standaloneSegments.isNotEmpty()) {
                    item(key = "segments_header") {
                        SectionHeader("Segments — needs replay to save")
                    }
                    items(standaloneSegments, key = { "seg_" + it.host + it.pattern }) { group ->
                        CandidateRow(
                            title = "${group.requestCount} requests",
                            detail = "Playlist not captured — replay the video, or use auto-scroll to catch it",
                            icon = Icons.Default.Stream,
                            onClick = null,
                            enabled = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AllVideosTab(
    tabDisplayInfo: List<TabDisplayInfo>,
    allMediaByTab: Map<String, List<MediaCandidate>>,
    allCapturesByTab: Map<String, SegmentCandidateRegistry.TabMediaCapture>,
    allVariantQualitiesByTab: Map<String, Map<String, List<VariantQuality>>>,
    onDownload: (MediaCandidate) -> Unit,
    onDownloadHls: (url: String, qualityLabel: String?, posterUrl: String?) -> Unit,
    onDownloadAll: () -> Unit
) {
    val totalVideos = tabDisplayInfo.sumOf { it.mediaCount }
    val hasAnyContent = tabDisplayInfo.isNotEmpty()

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "All Videos",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (hasAnyContent) {
                    Text(
                        text = "$totalVideos videos · ${tabDisplayInfo.size} tabs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            if (hasAnyContent) {
                TextButton(onClick = onDownloadAll) {
                    Text("Download All")
                }
            }
        }

        if (!hasAnyContent) {
            Text(
                text = "No videos detected across all tabs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.padding(bottom = 24.dp)) {
                tabDisplayInfo.forEach { tabInfo ->
                    item(key = "tab_header_${tabInfo.tabId}") {
                        TabHeader(tabInfo.title, tabInfo.mediaCount)
                    }

                    val tabCandidates = allMediaByTab[tabInfo.tabId].orEmpty()
                    val tabCapture = allCapturesByTab[tabInfo.tabId]
                    val tabVariants = allVariantQualitiesByTab[tabInfo.tabId] ?: emptyMap()

                    if (tabCapture != null) {
                        val playlistUrls = tabCapture.playlists.map { it.url }.toSet()
                        val fileGroups = groupFileCandidates(tabCandidates, playlistUrls)
                        val posterByUrl = tabCandidates
                            .filter { !it.posterUrl.isNullOrEmpty() }
                            .associate { it.url to it.posterUrl!! }

                        if (tabCapture.streamGroups.isNotEmpty()) {
                            items(tabCapture.streamGroups, key = { "stream_${tabInfo.tabId}_" + it.best.url }) { group ->
                                StreamRow(
                                    group = group,
                                    parsed = tabVariants[group.best.url].orEmpty(),
                                    posterUrl = streamPoster(posterByUrl, group),
                                    isActive = false,
                                    onDownloadHls = onDownloadHls
                                )
                            }
                        }

                        if (fileGroups.isNotEmpty()) {
                            items(fileGroups, key = { "file_${tabInfo.tabId}_" + it.best.url }) { group ->
                                val savableHls = group.kind == MediaTypes.HLS
                                CandidateRow(
                                    title = group.title,
                                    detail = group.detail,
                                    icon = group.icon,
                                    posterUrl = group.posterUrl,
                                    badge = group.badge,
                                    onClick = {
                                        if (savableHls) {
                                            onDownloadHls(group.best.url, group.badge, group.posterUrl)
                                        } else {
                                            onDownload(group.best)
                                        }
                                    },
                                    enabled = group.enabled
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One stream: tap row = save best quality; chevron expands the quality picker. */
@Composable
private fun StreamRow(
    group: SegmentCandidateRegistry.StreamGroup,
    parsed: List<VariantQuality>,
    posterUrl: String?,
    isActive: Boolean = false,
    pageTitle: String? = null,
    onDownloadHls: (url: String, qualityLabel: String?, posterUrl: String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val savable = group.kind == MediaTypes.HLS
    // Parsed variants carry real quality; captured-only playlists fill the gaps
    val variantRows = remember(group, parsed) { buildVariantRows(group, parsed) }
    val bestQualityText = topQualityLabel(parsed)
    val bestFullQuality = parsed.firstOrNull()?.let {
        qualityLabelFor(it.resolution, it.bandwidth)
    }
    val multi = variantRows.size > 1
    val alpha = if (savable) 1f else 0.5f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isActive) {
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                } else {
                    Modifier
                }
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (savable) Modifier.clickable {
                        onDownloadHls(group.best.url, bestFullQuality, posterUrl)
                    } else Modifier
                )
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            MediaThumb(
                posterUrl = posterUrl,
                icon = Icons.Default.Stream,
                enabled = savable
            )
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = MediaNaming.streamDisplayTitle(
                            url = group.best.url,
                            pageTitle = pageTitle,
                            qualityCount = variantRows.size,
                            bestQuality = bestQualityText
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isActive) {
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = "PLAYING",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = streamLabel(group, variantRows.size, bestQualityText),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f * alpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (multi) {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Hide qualities" else "Pick a quality",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
                    )
                }
            }
        }
        if (expanded) {
            variantRows.forEach { variant ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (savable) Modifier.clickable {
                                onDownloadHls(variant.url, variant.qualityLabel, posterUrl)
                            } else Modifier
                        )
                        .padding(start = 80.dp, end = 20.dp, top = 6.dp, bottom = 6.dp)
                ) {
                    Text(
                        text = variant.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (variant.isBest) 1f else 0.7f
                        ),
                        fontWeight = if (variant.isBest) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (variant.isBest) "best" else "save",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private data class VariantRow(
    val url: String,
    val label: String,
    val isBest: Boolean,
    val qualityLabel: String?
)

/**
 * Merges variants parsed from the master playlist (with RESOLUTION/BANDWIDTH)
 * with playlists captured from the network, best (highest bandwidth) first.
 */
private fun buildVariantRows(
    group: SegmentCandidateRegistry.StreamGroup,
    parsed: List<VariantQuality>
): List<VariantRow> {
    val rows = mutableListOf<VariantRow>()
    parsed.forEachIndexed { index, variant ->
        val quality = qualityLabelFor(variant.resolution, variant.bandwidth)
        rows += VariantRow(
            url = variant.url,
            label = if (quality != null) quality else fileNameLabel(variant.url),
            isBest = index == 0,
            qualityLabel = quality
        )
    }
    group.variants
        .filter { variant -> parsed.none { it.url == variant.url } }
        .forEach { variant ->
            rows += VariantRow(
                url = variant.url,
                label = fileNameLabel(variant.url),
                isBest = rows.isEmpty(),
                qualityLabel = null
            )
        }
    return rows
}

/** "playlist.m3u8" style fallback label for variants without parsed quality. */
private fun fileNameLabel(url: String): String {
    val path = url.substringBefore('?').substringAfterLast('/')
    return path.ifBlank { url }
}

/** Highest advertised quality of a stream: "up to 1080p". */
private fun topQualityLabel(parsed: List<VariantQuality>): String? {
    return parsed.firstNotNullOfOrNull { variant ->
        qualityLabelFor(variant.resolution, 0L)
    }
}

private fun streamLabel(group: SegmentCandidateRegistry.StreamGroup, qualityCount: Int, best: String?): String {
    val qualities = when {
        qualityCount > 1 && best != null -> " · $qualityCount qualities · up to $best"
        qualityCount > 1 -> " · $qualityCount qualities"
        else -> ""
    }
    val segments = if (group.segmentCount > 0) {
        " · ${group.segmentCount} segments seen"
    } else {
        ""
    }
    return if (group.kind == MediaTypes.HLS) {
        "HLS video$qualities$segments · tap to save as MP4"
    } else {
        "DASH video$qualities · not supported yet"
    }
}

private fun streamPoster(
    posterByUrl: Map<String, String>,
    group: SegmentCandidateRegistry.StreamGroup,
    activeVideo: MediaCandidate? = null
): String? {
    // 1. Check if active video matches this stream (URL prefix match)
    if (activeVideo != null && activeVideo.posterUrl != null) {
        if (urlsMatch(activeVideo.url, group.best.url)) {
            return activeVideo.posterUrl
        }
        for (variant in group.variants) {
            if (urlsMatch(activeVideo.url, variant.url)) {
                return activeVideo.posterUrl
            }
        }
    }
    // 2. Check direct URL match in poster map
    posterByUrl[group.best.url]?.let { return it }
    // 3. Check variant URLs
    val variantPoster = group.variants.firstNotNullOfOrNull { posterByUrl[it.url] }
    if (variantPoster != null) return variantPoster
    // 4. Fall back to any poster from active video
    if (activeVideo?.posterUrl != null) {
        return activeVideo.posterUrl
    }
    return null
}

/** Check if a stream group matches the currently playing video. */
private fun isStreamActive(
    group: SegmentCandidateRegistry.StreamGroup,
    activeVideo: MediaCandidate?
): Boolean {
    if (activeVideo == null) return false
    // Check if active video's URL matches stream's best URL
    if (urlsMatch(activeVideo.url, group.best.url)) return true
    // Check if active video's URL matches any variant URL
    return group.variants.any { urlsMatch(activeVideo.url, it.url) }
}

/** Compare URLs ignoring query parameters (handles token differences). */
private fun urlsMatch(url1: String, url2: String): Boolean {
    val base1 = url1.substringBefore('?').substringBefore('#')
    val base2 = url2.substringBefore('?').substringBefore('#')
    return base1 == base2
}

// File groups (videos/audio/blobs single rows; same-stem images collapsed) ------

private data class FileGroup(
    val kind: String,
    val title: String,
    val detail: String,
    val icon: ImageVector,
    val best: MediaCandidate,
    val enabled: Boolean,
    val posterUrl: String? = null,
    val badge: String? = null
)

private fun groupFileCandidates(
    candidates: List<MediaCandidate>,
    playlistUrls: Set<String>
): List<FileGroup> {
    val out = mutableListOf<FileGroup>()
    val images = mutableListOf<MediaCandidate>()
    candidates.forEach { candidate ->
        if (candidate.url in playlistUrls) return@forEach // already covered by a stream row
        when (candidate.type) {
            MediaTypes.IMAGE -> images += candidate
            MediaTypes.HLS -> out += FileGroup(
                kind = candidate.type,
                title = MediaNaming.displayTitle(candidate.url, fallback = "HLS video"),
                detail = "HLS stream · ${MediaNaming.shortHost(candidate.url)} · tap to save as MP4",
                icon = Icons.Default.Stream,
                best = candidate,
                enabled = true
            )
            MediaTypes.DASH -> out += FileGroup(
                kind = candidate.type,
                title = MediaNaming.displayTitle(candidate.url, fallback = "DASH video"),
                detail = "DASH stream · not supported yet",
                icon = Icons.Default.Stream,
                best = candidate,
                enabled = false
            )
            MediaTypes.BLOB -> out += FileGroup(
                kind = candidate.type,
                title = "Page video",
                detail = "In-page video · tap to download · keep page open",
                icon = Icons.Default.Videocam,
                best = candidate,
                enabled = true,
                posterUrl = candidate.posterUrl,
                badge = qualityLabelFor(null, 0L, heightOverride = candidate.height)
            )
            MediaTypes.AUDIO -> out += FileGroup(
                kind = candidate.type,
                title = MediaNaming.displayTitle(candidate.url, fallback = "Audio"),
                detail = rowDetail("Audio", MediaNaming.shortHost(candidate.url)),
                icon = Icons.Default.MusicNote,
                best = candidate,
                enabled = true
            )
            else -> out += FileGroup(
                kind = candidate.type,
                title = MediaNaming.displayTitle(candidate.url, fallback = MediaTypes.label(candidate.type)),
                detail = rowDetail(MediaTypes.label(candidate.type), MediaNaming.shortHost(candidate.url)),
                icon = iconForType(candidate.type),
                best = candidate,
                enabled = true,
                posterUrl = candidate.posterUrl,
                badge = qualityLabelFor(null, 0L, heightOverride = candidate.height)
            )
        }
    }

    // Same-stem images (srcset / size variants) collapse to one row: "largest"
    images.groupBy { imageGroupKey(it.url) }.forEach { (_, sizes) ->
        val best = sizes.maxByOrNull { imageSizeScore(it.url) } ?: sizes.first()
        out += FileGroup(
            kind = MediaTypes.IMAGE,
            title = MediaNaming.displayTitle(best.url, fallback = "Image"),
            detail = if (sizes.size > 1) {
                "Image · ${sizes.size} sizes · saves largest"
            } else {
                "Image · ${MediaNaming.shortHost(best.url)}"
            },
            icon = Icons.Default.Image,
            best = best,
            enabled = true,
            posterUrl = best.url // the image itself is its thumbnail
        )
    }
    return out
}

/** "Video · video.acme.com · 3 requests" — null/blank parts are skipped. */
private fun rowDetail(vararg parts: String?): String =
    parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")

/** Collapses size-variant filenames (photo-800w.jpg, photo@2x.jpg → "photo"). */
private fun imageGroupKey(url: String): String {
    val name = url.substringBefore('?').substringAfterLast('/')
    val base = name.substringBeforeLast('.')
    return base
        .replace(Regex("[-_]?\\d+w$"), "")
        .replace(Regex("@\\d+x$"), "")
        .replace(Regex("[-_]?\\d+x\\d+$"), "")
        .replace(Regex("[-_]?\\d{2,4}$"), "")
        .replace(Regex("[-_]?\\d+x$"), "")
        .lowercase()
}

/** Rough size proxy for picking the largest variant: biggest number in the filename. */
private fun imageSizeScore(url: String): Int =
    Regex("\\d+").findAll(url.substringBefore('?').substringAfterLast('/'))
        .mapNotNull { it.value.toIntOrNull() }
        .maxOrNull() ?: 0

private fun buildSummaryLine(streams: List<SegmentCandidateRegistry.StreamGroup>, files: List<FileGroup>): String {
    val parts = mutableListOf<String>()
    val hlsStreams = streams.count { it.kind == MediaTypes.HLS }
    val dashStreams = streams.count { it.kind == MediaTypes.DASH }
    if (hlsStreams > 0) parts += "$hlsStreams stream" + if (hlsStreams > 1) "s" else ""
    if (dashStreams > 0) parts += "$dashStreams DASH"
    val videos = files.count { it.kind == MediaTypes.VIDEO || it.kind == MediaTypes.BLOB }
    val audio = files.count { it.kind == MediaTypes.AUDIO }
    val images = files.count { it.kind == MediaTypes.IMAGE }
    if (videos > 0) parts += "$videos video" + if (videos > 1) "s" else ""
    if (audio > 0) parts += "$audio audio" + if (audio > 1) "s" else ""
    if (images > 0) parts += "$images image" + if (images > 1) "s" else ""
    return if (parts.isEmpty()) "" else parts.joinToString(" · ")
}

@Composable
private fun SectionHeader(title: String) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
    )
}

@Composable
private fun TabHeader(title: String, mediaCount: Int) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$mediaCount",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/** Poster thumbnail with a type-icon fallback. */
@Composable
private fun MediaThumb(
    posterUrl: String?,
    icon: ImageVector,
    enabled: Boolean = true
) {
    MediaThumbnail(model = posterUrl, icon = icon, enabled = enabled, size = 48.dp)
}

@Composable
private fun CandidateRow(
    title: String,
    detail: String,
    icon: ImageVector,
    onClick: (() -> Unit)?,
    enabled: Boolean = true,
    posterUrl: String? = null,
    badge: String? = null,
    isActive: Boolean = false
) {
    val alpha = if (enabled) 1f else 0.5f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .then(
                if (isActive) {
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        MediaThumb(posterUrl = posterUrl, icon = icon, enabled = enabled)
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isActive) {
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = "PLAYING",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f * alpha),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!badge.isNullOrBlank()) {
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

private fun iconForType(type: String): ImageVector = when (type) {
    MediaTypes.VIDEO -> Icons.Default.Videocam
    MediaTypes.AUDIO -> Icons.Default.MusicNote
    MediaTypes.IMAGE -> Icons.Default.Image
    MediaTypes.HLS, MediaTypes.DASH -> Icons.Default.Stream
    MediaTypes.BLOB -> Icons.Default.Videocam
    else -> Icons.Default.Videocam
}
