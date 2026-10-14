package com.diego.kiki.download

import java.net.URI
import java.net.URLDecoder

/**
 * Pure (JVM-only) helpers that turn raw media URLs and metadata into
 * human-readable labels: "https://cdn/video/ep2.mp4" → "ep2 · video.acme.com".
 * Also derives download filenames that skip generic playlist names.
 */
object MediaNaming {

    private val GENERIC_TOKENS = setOf(
        "index", "playlist", "master", "chunklist", "manifest", "video", "media",
        "stream", "hls", "vod", "audio", "track", "file", "download", "main",
        "m3u8", "mpd", "mp4", "webm", "m4v", "mov", "mkv", "avi", "flv", "ogv",
        "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac"
    )

    /** File name (with extension) from the URL path, decoded; "" when absent. */
    fun fileNameFromUrl(url: String): String {
        val path = pathOf(url)
        val raw = path.substringBefore(';').substringAfterLast('/')
        if (raw.isBlank()) return ""
        return try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (_: Exception) {
            raw
        }
    }

    /**
     * Host without the "www." prefix, for compact "name · host" labels.
     */
    fun shortHost(url: String): String {
        return try {
            (URI(url).host ?: "").lowercase().removePrefix("www.")
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * A readable base name for display and for download filenames: the file
     * name if meaningful, otherwise the nearest non-generic path segment
     * (e.g. /hls/video1/index.m3u8 → "video1"). Empty when nothing usable.
     */
    fun smartBaseName(url: String): String {
        fileNameFromUrl(url).let { name ->
            val base = name.substringBeforeLast('.')
            if (base.isNotBlank() && !isGenericToken(base)) return sanitize(base)
        }
        val path = pathOf(url)
        val segment = path.split('/')
            .filter { it.isNotBlank() }
            .dropLast(1)
            .lastOrNull { token ->
                val clean = token.substringBeforeLast('.')
                clean.isNotBlank() && !isGenericToken(clean)
            } ?: ""
        return sanitize(segment.substringBeforeLast('.'))
    }

    /**
     * Short display title for a media row: smart base name, falling back to
     * the host and then to a neutral label.
     */
    fun displayTitle(url: String, fallback: String = "Media"): String {
        val base = smartBaseName(url)
        if (base.isNotBlank()) return base
        val host = shortHost(url)
        return if (host.isNotBlank()) host else fallback
    }

    /**
     * Display title for a stream with quality info: uses smart base name,
     * falls back to page title if provided, then to host.
     * Includes quality count in the title for better differentiation.
     */
    fun streamDisplayTitle(
        url: String,
        pageTitle: String? = null,
        qualityCount: Int = 0,
        bestQuality: String? = null,
        fallback: String = "Stream"
    ): String {
        val base = smartBaseName(url)
        val name = when {
            base.isNotBlank() && !isGenericToken(base) -> base
            !pageTitle.isNullOrBlank() -> pageTitle.take(40)
            else -> {
                val host = shortHost(url)
                if (host.isNotBlank()) host else fallback
            }
        }
        
        val parts = mutableListOf(name)
        if (bestQuality != null) {
            parts.add(bestQuality)
        } else if (qualityCount > 1) {
            parts.add("$qualityCount qualities")
        }
        
        return parts.joinToString(" · ")
    }

    /** True when the base name carries no episode/content information. */
    fun isGenericToken(token: String): Boolean {
        val clean = token.lowercase()
            .replace(Regex("[^a-z0-9]+"), "")
        if (clean.isEmpty()) return true
        if (clean in GENERIC_TOKENS) return true
        // hash-like names (a1b2c3d4e5...) and pure numbers carry no meaning
        if (clean.length >= 16 && clean.all { it.isDigit() || it in 'a'..'f' }) return true
        if (clean.all { it.isDigit() }) return true
        return false
    }

    /** Resolution label from intrinsic video height: 1080 → "1080p", 2160 → "4K". */
    fun qualityLabel(height: Int): String? = when {
        height >= 4320 -> "8K"
        height >= 2160 -> "4K"
        height >= 1440 -> "1440p"
        height >= 1080 -> "1080p"
        height >= 720 -> "720p"
        height >= 480 -> "480p"
        height >= 360 -> "360p"
        height > 0 -> "${height}p"
        else -> null
    }

    /** Compact bandwidth label: 4_500_000 → "4.5 Mbps", 480_000 → "480 kbps". */
    fun bandwidthLabel(bandwidth: Long): String? = when {
        bandwidth <= 0 -> null
        bandwidth >= 1_000_000 -> {
            val mbps = bandwidth / 1_000_000.0
            val rounded = (mbps * 10).toInt() / 10.0
            if (rounded % 1.0 == 0.0) "${rounded.toInt()} Mbps" else "$rounded Mbps"
        }
        else -> "${bandwidth / 1_000} kbps"
    }

    /**
     * Combined quality label for a variant or media item:
     * "1080p · 4.5 Mbps" / "720p" / "4.5 Mbps" / null. [heightOverride]
     * (DOM-reported video height) wins over the height parsed from a
     * "WIDTHxHEIGHT" [resolution] string.
     */
    fun qualityLabelFor(resolution: String?, bandwidth: Long, heightOverride: Int = 0): String? {
        val height = heightOverride.takeIf { it > 0 }
            ?: resolution?.lowercase()?.substringAfter('x', "")?.toIntOrNull()
            ?: 0
        val parts = listOfNotNull(qualityLabel(height), bandwidthLabel(bandwidth))
        return parts.joinToString(" · ").ifEmpty { null }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(60)

    private fun pathOf(url: String): String =
        try {
            URI(url).path ?: ""
        } catch (_: Exception) {
            // Fallback for URLs java.net.URI rejects (unencoded spaces etc.)
            val noQuery = url.substringBefore('?').substringBefore('#')
            val schemeEnd = noQuery.indexOf("://")
            if (schemeEnd > 0) {
                val afterHost = noQuery.indexOf('/', schemeEnd + 3)
                if (afterHost > 0) noQuery.substring(afterHost) else ""
            } else {
                ""
            }
        }
}
