package com.diego.kiki.ui.downloads

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diego.kiki.download.MediaCandidate
import com.diego.kiki.download.MediaTypes
import com.diego.kiki.download.SegmentCandidateRegistry
import com.diego.kiki.download.SegmentCandidateRegistry.PlaylistCapture
import com.diego.kiki.download.SegmentCandidateRegistry.StreamGroup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaCandidatesSheet(
    candidates: List<MediaCandidate>,
    capture: SegmentCandidateRegistry.TabMediaCapture,
    onDownload: (MediaCandidate) -> Unit,
    onDownloadHls: (String) -> Unit,
    onOpenBulk: () -> Unit,
    onDismiss: () -> Unit
) {
    val playlistUrls = capture.playlists.map { it.url }.toSet()
    val streamHosts = capture.streamGroups.map { it.host }.toSet()
    val fileGroups = groupFileCandidates(candidates, playlistUrls)
    val standaloneSegments = capture.segmentGroups.filter { it.host !in streamHosts }
    val hasAnyContent = candidates.isNotEmpty() || !capture.isEmpty

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
                        StreamRow(group = group, onDownloadHls = onDownloadHls)
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
                            label = group.label,
                            detail = group.best.url,
                            icon = group.icon,
                            onClick = {
                                if (savableHls) {
                                    onDownloadHls(group.best.url)
                                } else {
                                    onDownload(group.best)
                                }
                            },
                            enabled = group.enabled
                        )
                    }
                }

                // 3. Direct media request groups
                if (capture.mediaGroups.isNotEmpty()) {
                    item(key = "media_header") {
                        SectionHeader("Media requests")
                    }
                    items(capture.mediaGroups, key = { "media_" + it.host + it.pattern }) { group ->
                        CandidateRow(
                            label = "${MediaTypes.label(group.kind)} · ${group.requestCount} request" +
                                    if (group.requestCount > 1) "s" else "",
                            detail = group.sampleUrl,
                            icon = iconForType(group.kind),
                            onClick = { onDownload(MediaCandidate(group.sampleUrl, group.kind)) }
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
                            label = "${group.requestCount} requests · playlist not captured, replay the video",
                            detail = group.host + group.pattern,
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

/** One stream: tap row = save best quality; chevron expands the variant picker. */
@Composable
private fun StreamRow(
    group: StreamGroup,
    onDownloadHls: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val savable = group.kind == MediaTypes.HLS
    val multi = group.variants.size > 1
    val alpha = if (savable) 1f else 0.5f

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (savable) Modifier.clickable { onDownloadHls(group.best.url) } else Modifier)
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Stream,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha)
            )
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = streamLabel(group),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = group.best.url,
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
            group.variants.forEach { variant ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (savable) Modifier.clickable { onDownloadHls(variant.url) } else Modifier)
                        .padding(start = 52.dp, end = 20.dp, top = 6.dp, bottom = 6.dp)
                ) {
                    Text(
                        text = variantName(variant, group),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (variant.url == group.best.url) 1f else 0.7f
                        ),
                        fontWeight = if (variant.url == group.best.url) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (variant.url == group.best.url) "best" else "save",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private fun streamLabel(group: StreamGroup): String {
    val qualities = if (group.variants.size > 1) {
        " · ${group.variants.size} qualities"
    } else {
        ""
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

/** Short, human label for a variant playlist inside the picker. */
private fun variantName(variant: PlaylistCapture, group: StreamGroup): String {
    val path = variant.url.substringBefore('?').substringAfterLast('/')
    return if (variant.url == group.best.url) "$path (best)" else path.ifBlank { variant.url }
}

// File groups (videos/audio/blobs single rows; same-stem images collapsed) ------

private data class FileGroup(
    val kind: String,
    val label: String,
    val icon: ImageVector,
    val best: MediaCandidate,
    val enabled: Boolean
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
                label = "HLS video · tap to save as MP4",
                icon = Icons.Default.Stream,
                best = candidate,
                enabled = true
            )
            MediaTypes.DASH -> out += FileGroup(
                kind = candidate.type,
                label = "DASH video · not supported yet",
                icon = Icons.Default.Stream,
                best = candidate,
                enabled = false
            )
            MediaTypes.BLOB -> out += FileGroup(
                kind = candidate.type,
                label = "Page video · tap to download (keep page open)",
                icon = Icons.Default.Videocam,
                best = candidate,
                enabled = true
            )
            else -> out += FileGroup(
                kind = candidate.type,
                label = MediaTypes.label(candidate.type),
                icon = iconForType(candidate.type),
                best = candidate,
                enabled = true
            )
        }
    }

    // Same-stem images (srcset / size variants) collapse to one row: "largest"
    images.groupBy { imageGroupKey(it.url) }.forEach { (_, sizes) ->
        val best = sizes.maxByOrNull { imageSizeScore(it.url) } ?: sizes.first()
        out += FileGroup(
            kind = MediaTypes.IMAGE,
            label = if (sizes.size > 1) "Image · ${sizes.size} sizes · saves largest" else "Image",
            icon = Icons.Default.Image,
            best = best,
            enabled = true
        )
    }
    return out
}

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

private fun buildSummaryLine(streams: List<StreamGroup>, files: List<FileGroup>): String {
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
private fun CandidateRow(
    label: String,
    detail: String,
    icon: ImageVector,
    onClick: (() -> Unit)?,
    enabled: Boolean = true
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
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha)
        )
        Spacer(modifier = Modifier.size(12.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
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
    }
}

private fun iconForType(type: String): ImageVector = when (type) {
    MediaTypes.VIDEO -> Icons.Default.Videocam
    MediaTypes.AUDIO -> Icons.Default.MusicNote
    MediaTypes.IMAGE -> Icons.Default.Image
    MediaTypes.HLS, MediaTypes.DASH -> Icons.Default.Stream
    MediaTypes.BLOB -> Icons.Default.Description
    else -> Icons.Default.Videocam
}
