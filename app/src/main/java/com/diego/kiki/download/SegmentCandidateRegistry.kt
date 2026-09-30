package com.diego.kiki.download

import android.net.Uri
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Network-level media capture. Classifies requests observed in shouldInterceptRequest:
 * - Playlists (.m3u8/.mpd by extension OR query token like format=m3u8) and playlists
 *   confirmed by the JS XHR/fetch hook (any URL shape) — always savable.
 * - Direct media (video/audio extensions, query strings allowed) — savable.
 * - Segment groups (.ts/.m4s) grouped by host + normalized pattern; savable when a
 *   playlist was captured for the tab (the JS hook catches extension-less playlists).
 */
class SegmentCandidateRegistry {

    data class PlaylistCapture(
        val url: String,
        val kind: String, // MediaTypes.HLS or MediaTypes.DASH
        val role: String = ROLE_UNKNOWN
    ) {
        companion object {
            const val ROLE_MASTER = "master"
            const val ROLE_MEDIA = "media"
            const val ROLE_UNKNOWN = "unknown"
        }
    }

    /**
     * One user-facing stream: a master playlist plus its quality variants
     * (usually same-host paths under the master's directory). [best] is the
     * recommended download target.
     */
    data class StreamGroup(
        val host: String,
        val kind: String,
        val best: PlaylistCapture,
        val variants: List<PlaylistCapture>,
        val segmentCount: Int
    )

    data class MediaGroup(
        val host: String,
        val pattern: String,
        val requestCount: Int,
        val sampleUrl: String,
        val kind: String // MediaTypes.VIDEO / AUDIO
    )

    data class SegmentGroup(
        val host: String,
        val pattern: String,
        val requestCount: Int,
        val sampleUrl: String
    )

    data class TabMediaCapture(
        val playlists: List<PlaylistCapture>,
        val mediaGroups: List<MediaGroup>,
        val segmentGroups: List<SegmentGroup>,
        val streamGroups: List<StreamGroup> = emptyList()
    ) {
        val isEmpty: Boolean
            get() = playlists.isEmpty() && mediaGroups.isEmpty() && segmentGroups.isEmpty()
    }

    private class MutableGroup(
        val host: String,
        val pattern: String,
        val sampleUrl: String
    ) {
        val count = AtomicInteger(1)
    }

    private val tabPlaylists =
        ConcurrentHashMap<String, CopyOnWriteArrayList<PlaylistCapture>>()
    private val tabMediaGroups =
        ConcurrentHashMap<String, ConcurrentHashMap<String, MutableGroup>>()
    private val tabSegmentGroups =
        ConcurrentHashMap<String, ConcurrentHashMap<String, MutableGroup>>()

    /** Notified when a tab transitions between "has captures" and "none". */
    var onGroupsChanged: ((tabId: String, hasGroups: Boolean) -> Unit)? = null

    fun record(tabId: String, url: String) {
        val uri = Uri.parse(url) ?: return
        val host = uri.host?.lowercase() ?: return
        val path = (uri.path ?: "").lowercase()
        val query = (uri.query ?: "").lowercase()
        if (host.isBlank()) return

        val playlistKind = classifyPlaylist(path, query)
        if (playlistKind != null) {
            addPlaylist(tabId, url, playlistKind)
            return
        }

        val mediaKind = classifyDirectMedia(path)
        if (mediaKind != null) {
            val groups = tabMediaGroups.getOrPut(tabId) { ConcurrentHashMap() }
            val wasEmpty = groups.isEmpty()
            val group = groups.getOrPut(groupKey(host, path)) {
                MutableGroup(host, normalizePattern(path), url)
            }
            if (group.count.incrementAndGet() > MAX_COUNT_PER_GROUP) group.count.decrementAndGet()
            if (wasEmpty) onGroupsChanged?.invoke(tabId, true)
            return
        }

        if (classifySegment(path)) {
            val groups = tabSegmentGroups.getOrPut(tabId) { ConcurrentHashMap() }
            val wasEmpty = groups.isEmpty()
            val group = groups.getOrPut(groupKey(host, path)) {
                MutableGroup(host, normalizePattern(path), url)
            }
            if (group.count.incrementAndGet() > MAX_COUNT_PER_GROUP) group.count.decrementAndGet()
            if (wasEmpty) onGroupsChanged?.invoke(tabId, true)
        }
    }

    /** Called by the JS hook when a playlist response is confirmed (any URL shape). */
    fun addPlaylist(
        tabId: String,
        url: String,
        kind: String,
        role: String = PlaylistCapture.ROLE_UNKNOWN
    ): Boolean {
        val list = tabPlaylists.getOrPut(tabId) { CopyOnWriteArrayList() }
        val wasEmpty = list.isEmpty()
        val added = list.none { it.url == url } && list.size < MAX_PLAYLISTS_PER_TAB
        if (added) {
            list.add(PlaylistCapture(url, kind, role))
        }
        if (wasEmpty || added) {
            onGroupsChanged?.invoke(tabId, true)
        }
        return added
    }

    fun captureFor(tabId: String): TabMediaCapture {
        val playlists = tabPlaylists[tabId]?.toList() ?: emptyList()
        val media = tabMediaGroups[tabId]?.values?.map { group ->
            MediaGroup(
                host = group.host,
                pattern = group.pattern,
                requestCount = group.count.get(),
                sampleUrl = group.sampleUrl,
                kind = classifyDirectMedia((Uri.parse(group.sampleUrl).path ?: "").lowercase())
                    ?: MediaTypes.VIDEO
            )
        }?.sortedByDescending { it.requestCount } ?: emptyList()
        val segments = tabSegmentGroups[tabId]?.values?.map { group ->
            SegmentGroup(
                host = group.host,
                pattern = group.pattern,
                requestCount = group.count.get(),
                sampleUrl = group.sampleUrl
            )
        }?.sortedByDescending { it.requestCount } ?: emptyList()
        val byUrl = playlists.associateBy { it.url }
        val streams = groupStreams(
            playlists.map { StreamInput(it.url, it.kind, it.role) },
            segments.map { SegmentSummary(it.host, it.sampleUrl, it.requestCount) }
        ).map { group ->
            StreamGroup(
                host = group.host,
                kind = group.kind,
                best = byUrl.getValue(group.bestUrl),
                variants = group.variantUrls.mapNotNull { byUrl[it] },
                segmentCount = group.segmentCount
            )
        }
        return TabMediaCapture(playlists, media, segments, streams)
    }

    fun hasContent(tabId: String): Boolean = !captureFor(tabId).isEmpty

    fun clear(tabId: String) {
        val removed = tabPlaylists.remove(tabId) != null ||
                tabMediaGroups.remove(tabId) != null ||
                tabSegmentGroups.remove(tabId) != null
        if (removed) {
            onGroupsChanged?.invoke(tabId, false)
        }
    }

    fun clearAll() {
        val tabs = mutableSetOf<String>()
        tabs += tabPlaylists.keys
        tabs += tabMediaGroups.keys
        tabs += tabSegmentGroups.keys
        tabPlaylists.clear()
        tabMediaGroups.clear()
        tabSegmentGroups.clear()
        tabs.forEach { onGroupsChanged?.invoke(it, false) }
    }

    // Classification ---------------------------------------------------------------

    private fun classifyPlaylist(path: String, query: String): String? {
        return when {
            path.endsWith(".m3u8") -> MediaTypes.HLS
            path.endsWith(".mpd") -> MediaTypes.DASH
            // Extension-less playlists: /manifest?format=m3u8, /get_video?type=m3u8, ...
            query.contains("m3u8") -> MediaTypes.HLS
            query.contains("mpd") -> MediaTypes.DASH
            else -> null
        }
    }

    private fun classifyDirectMedia(path: String): String? {
        return when {
            MEDIA_VIDEO_EXTENSIONS.any { path.endsWith(it) } -> MediaTypes.VIDEO
            MEDIA_AUDIO_EXTENSIONS.any { path.endsWith(it) } -> MediaTypes.AUDIO
            else -> null
        }
    }

    private fun classifySegment(path: String): Boolean {
        return path.endsWith(".ts") || path.endsWith(".m4s")
    }

    /**
     * Replaces path segments containing digits with a truncated wildcard so
     * /hls/video123/seg-4.ts and /hls/video123/seg-5.ts collapse into one group.
     */
    private fun normalizePattern(path: String): String {
        val normalized = path.split('/')
            .joinToString("/") { segment ->
                if (segment.any { it.isDigit() }) {
                    segment.take(24) + "*"  // keep some readable prefix
                } else {
                    segment
                }
            }
        return normalized.take(MAX_PATTERN_LENGTH)
    }

    private fun groupKey(host: String, path: String): String = "$host${normalizePattern(path)}"

    companion object {
        private val MEDIA_VIDEO_EXTENSIONS = listOf(
            ".mp4", ".webm", ".m4v", ".mov", ".mkv", ".flv", ".avi", ".ogv"
        )
        private val MEDIA_AUDIO_EXTENSIONS = listOf(
            ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".flac"
        )

        private const val MAX_PATTERN_LENGTH = 160
        private const val MAX_COUNT_PER_GROUP = 9999
        private const val MAX_PLAYLISTS_PER_TAB = 12
    }
}

/** Plain inputs for [groupStreams] — java.net.URI-parseable, unit-testable. */
data class StreamInput(val url: String, val kind: String, val role: String)

data class SegmentSummary(val host: String, val sampleUrl: String, val requestCount: Int)

data class StreamGrouping(
    val host: String,
    val kind: String,
    val bestUrl: String,
    val variantUrls: List<String>,
    val segmentCount: Int
)

/**
 * Collapses the raw playlist list into user-facing streams: same-host
 * playlists under a master's parent directory join that master's group;
 * leftovers group by their own parent directory. Pure function over
 * java.net.URI so it runs in unit tests.
 */
fun groupStreams(
    playlists: List<StreamInput>,
    segments: List<SegmentSummary>
): List<StreamGrouping> {
    fun hostOf(url: String): String =
        try {
            java.net.URI(url).host?.lowercase() ?: ""
        } catch (_: Exception) {
            ""
        }

    fun pathOf(url: String): String =
        try {
            java.net.URI(url).path ?: "/"
        } catch (_: Exception) {
            "/"
        }

    fun parentDir(url: String): String {
        val p = pathOf(url)
        return p.substringBeforeLast('/', "") + "/"
    }

    fun rank(role: String): Int = when (role) {
        SegmentCandidateRegistry.PlaylistCapture.ROLE_MASTER -> 0
        SegmentCandidateRegistry.PlaylistCapture.ROLE_MEDIA -> 1
        else -> 2
    }

    fun segmentsUnder(host: String, dir: String): Int =
        segments.filter { it.host == host && pathOf(it.sampleUrl).startsWith(dir) }
            .sumOf { it.requestCount }

    val out = mutableListOf<StreamGrouping>()
    playlists.groupBy { hostOf(it.url) to it.kind }.forEach { (_, sameHostKind) ->
        val masters = sameHostKind.filter { it.role == SegmentCandidateRegistry.PlaylistCapture.ROLE_MASTER }
        val remaining = sameHostKind.filter { it.role != SegmentCandidateRegistry.PlaylistCapture.ROLE_MASTER }
            .toMutableList()
        masters.forEach { master ->
            val dir = parentDir(master.url)
            val mine = remaining.filter { parentDir(it.url).startsWith(dir) }
            remaining.removeAll(mine)
            val members = (listOf(master) + mine).distinctBy { it.url }
            out += StreamGrouping(
                host = hostOf(master.url),
                kind = master.kind,
                bestUrl = master.url,
                variantUrls = members.map { it.url },
                segmentCount = segmentsUnder(hostOf(master.url), dir)
            )
        }
        remaining.groupBy { parentDir(it.url) }.forEach { (_, sameDir) ->
            val best = sameDir.minByOrNull { rank(it.role) } ?: sameDir.first()
            out += StreamGrouping(
                host = hostOf(best.url),
                kind = best.kind,
                bestUrl = best.url,
                variantUrls = sameDir.map { it.url },
                segmentCount = segmentsUnder(hostOf(best.url), parentDir(best.url))
            )
        }
    }
    return out
}
