package com.diego.kiki.hls

import java.net.URI

/**
 * M3U8 parser (VOD subset): master playlists (variants), media playlists,
 * #EXT-X-KEY, #EXT-X-BYTERANGE, #EXT-X-MAP, #EXT-X-ENDLIST detection and
 * relative URL resolution.
 */
object M3U8Parser {

    class ParseException(message: String) : Exception(message)

    fun parse(text: String, baseUrl: String): HlsPlaylist {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        if (lines.none { it.startsWith("#EXTM3U") }) {
            throw ParseException("Not an M3U8 playlist")
        }

        val isMaster = lines.any { it.startsWith("#EXT-X-STREAM-INF") }
        return if (isMaster) {
            HlsPlaylist.Master(parseMaster(lines, baseUrl))
        } else {
            HlsPlaylist.Media(parseMedia(lines, baseUrl))
        }
    }

    // Master -------------------------------------------------------------------

    private fun parseMaster(lines: List<String>, baseUrl: String): List<HlsVariant> {
        val variants = mutableListOf<HlsVariant>()
        var pendingAttributes: Map<String, String>? = null

        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pendingAttributes = parseAttributeList(line.substringAfter(':'))
            } else if (!line.startsWith("#")) {
                val attrs = pendingAttributes
                if (attrs != null) {
                    variants += HlsVariant(
                        url = resolve(baseUrl, line),
                        bandwidth = attrs["BANDWIDTH"]?.toLongOrNull()
                            ?: attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull()
                            ?: 0L,
                        codecs = attrs["CODECS"],
                        resolution = attrs["RESOLUTION"]
                    )
                    pendingAttributes = null
                }
            }
        }
        return variants
    }

    // Media --------------------------------------------------------------------

    private fun parseMedia(lines: List<String>, baseUrl: String): HlsMediaPlaylist {
        val segments = mutableListOf<HlsSegment>()
        var currentKey = HlsKey(method = "NONE")
        var targetDuration = 0L
        var mediaSequence = 0L
        var hasEndList = false
        var pendingDuration: Double? = null
        var pendingByterange: HlsByterange? = null
        var initSegmentUrl: String? = null
        var initSegmentByterange: HlsByterange? = null

        // Per-URI running offsets for BYTERANGE entries without explicit offset
        val byterangeOffsets = HashMap<String, Long>()

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-TARGETDURATION:") ->
                    targetDuration = line.substringAfter(':').toLongOrNull() ?: 0L

                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    mediaSequence = line.substringAfter(':').toLongOrNull() ?: 0L

                line.startsWith("#EXT-X-ENDLIST") ->
                    hasEndList = true

                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = parseAttributeList(line.substringAfter(':'))
                    val method = attrs["METHOD"] ?: "NONE"
                    val uri = attrs["URI"]?.let { resolve(baseUrl, it) }
                    // "identity" (or absent) key format means plain AES-128
                    val keyFormat = attrs["KEYFORMAT"]?.takeIf { it != "identity" }
                    if (method.equals("NONE", true)) {
                        currentKey = HlsKey(method = "NONE")
                    } else {
                        currentKey = HlsKey(
                            method = method,
                            uri = uri,
                            ivHex = attrs["IV"],
                            keyFormat = keyFormat
                        )
                    }
                }

                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = parseAttributeList(line.substringAfter(':'))
                    initSegmentUrl = attrs["URI"]?.let { resolve(baseUrl, it) }
                    initSegmentByterange = attrs["BYTERANGE"]?.let { parseByterange(it) }
                }

                line.startsWith("#EXTINF:") ->
                    pendingDuration = line.substringAfter(':')
                        .substringBefore(',')
                        .trim()
                        .toDoubleOrNull()

                line.startsWith("#EXT-X-BYTERANGE:") ->
                    pendingByterange = parseByterange(line.substringAfter(':'))

                !line.startsWith("#") -> {
                    val duration = pendingDuration ?: 0.0
                    val url = resolve(baseUrl, line)
                    var byterange = pendingByterange
                    if (byterange != null && byterange.offset == null) {
                        val previous = byterangeOffsets[url] ?: 0L
                        byterange = byterange.copy(offset = previous)
                    }
                    if (byterange != null) {
                        byterangeOffsets[url] = (byterange.offset ?: 0L) + byterange.length
                    }
                    pendingDuration = null
                    pendingByterange = null

                    segments += HlsSegment(
                        url = url,
                        durationSeconds = duration,
                        sequence = mediaSequence + segments.size,
                        key = currentKey.takeIf { !it.isNone },
                        byterange = byterange
                    )
                }
            }
        }

        return HlsMediaPlaylist(
            segments = segments,
            isLive = !hasEndList,
            targetDurationSeconds = targetDuration,
            mediaSequence = mediaSequence,
            initSegmentUrl = initSegmentUrl,
            initSegmentByterange = initSegmentByterange
        )
    }

    // Helpers --------------------------------------------------------------------

    fun parseByterange(value: String): HlsByterange {
        val lengthPart = value.substringBefore('@').trim()
        val offsetPart = value.substringAfter('@', "").trim()
        return HlsByterange(
            length = lengthPart.toLongOrNull() ?: 0L,
            offset = offsetPart.toLongOrNull()
        )
    }

    /**
     * Parses an HLS attribute list: KEY=VALUE pairs separated by commas,
     * where values may be quoted strings containing commas.
     */
    fun parseAttributeList(input: String): Map<String, String> {
        val result = HashMap<String, String>()
        var index = 0
        val length = input.length

        while (index < length) {
            // Read attribute name
            val equals = input.indexOf('=', index)
            if (equals == -1) break
            val name = input.substring(index, equals).trim()
            index = equals + 1

            if (index < length && input[index] == '"') {
                // Quoted value: scan to the closing quote
                val closing = input.indexOf('"', index + 1)
                if (closing == -1) {
                    result[name] = input.substring(index + 1).trim()
                    break
                }
                result[name] = input.substring(index + 1, closing)
                index = closing + 1
                if (index < length && input[index] == ',') index++
            } else {
                // Unquoted value: scan to the next comma
                val comma = input.indexOf(',', index)
                if (comma == -1) {
                    result[name] = input.substring(index).trim()
                    break
                }
                result[name] = input.substring(index, comma).trim()
                index = comma + 1
            }
        }
        return result
    }

    fun resolve(baseUrl: String, reference: String): String {
        val trimmed = reference.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        return try {
            URI(baseUrl).resolve(trimmed.replace(" ", "%20")).toString()
        } catch (_: Exception) {
            // Last resort: naive resolution against the base directory
            val baseDir = baseUrl.substringBeforeLast('/')
            if (trimmed.startsWith("/")) {
                val root = URI(baseUrl).let { "${it.scheme}://${it.host}" }
                "$root$trimmed"
            } else {
                "$baseDir/$trimmed"
            }
        }
    }
}
