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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Stream
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaCandidatesSheet(
    candidates: List<MediaCandidate>,
    capture: SegmentCandidateRegistry.TabMediaCapture,
    variantQuality: Map<String, List<VariantQuality>> = emptyMap(),
    onDownload: (MediaCandidate) -> Unit,
    onDownloadHls: (url: String, qualityLabel: String?, posterUrl: String?) -> Unit,
    onOpenBulk: () -> Unit,
    onDismiss: () -> Unit
) {
    val playlistUrls = capture.playlists.map { it.url }.toSet()
    val streamHosts = capture.streamGroups.map { it.host }.toSet()
    val fileGroups = groupFileCandidates(candidates, playlistUrls)
    val fileUrls = fileGroups.map { it.best.url }.toSet()
    // A URL seen by both the DOM and the network layer renders once (file row
    // carries richer metadata), so the media-request copy is dropped
    val mediaGroups = capture.mediaGroups.filter { it.url !in fileUrls }
    val standaloneSegments = capture.segmentGroups.filter { it.host !in streamHosts }
    val hasAnyContent = candidates.isNotEmpty() || !capture.isEmpty
    val posterByUrl = candidates
        .filter { !it.posterUrl.isNullOrEmpty() }
        .associate { it.url to it.posterUrl!! }

    ModalBottomSheet(onDismissRequest = onDismiss) {
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
            LazyColumn(modifier = Modifier.padding(bottom = 24.dp)) {

                // 1. Streams — one row per stream, expandable quality picker
                if (capture.streamGroups.isNotEmpty()) {
                    item(key = "streams_header") {
                        SectionHeader("Streams — saved as MP4")
                    }
                    items(capture.streamGroups, key = { "stream_" + it.best.url }) { group ->
                        StreamRow(
                            group = group,
                            parsed = variantQuality[group.best.url].orEmpty(),
                            posterUrl = streamPoster(posterByUrl, group),
                            onDownloadHls = onDownloadHls
                        )
                    }
                }

                // 2. Files found in the page (videos, audio, blobs, collapsed images)
                if (fileGroups.isNotEmpty()) {
                    item(key = "files_header") {
                        SectionHeader("Files on the page")
                    }
                    items(fileGroups, key = { "file_" + it.best.url }) { group ->
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

                // 3. Direct media requests — one distinct URL per row
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

                // 4. Segment traffic on hosts where no playlist was captured
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

/** One stream: tap row = save best quality; chevron expands the quality picker. */
@Composable
private fun StreamRow(
    group: SegmentCandidateRegistry.StreamGroup,
    parsed: List<VariantQuality>,
    posterUrl: String?,
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

    Column(modifier = Modifier.fillMaxWidth()) {
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
                Text(
                    text = MediaNaming.displayTitle(group.best.url, fallback = "Stream"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
    group: SegmentCandidateRegistry.StreamGroup
): String? {
    posterByUrl[group.best.url]?.let { return it }
    return group.variants.firstNotNullOfOrNull { posterByUrl[it.url] }
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
    badge: String? = null
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
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        MediaThumb(posterUrl = posterUrl, icon = icon, enabled = enabled)
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
