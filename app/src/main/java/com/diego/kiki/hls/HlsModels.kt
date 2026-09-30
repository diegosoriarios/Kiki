package com.diego.kiki.hls

/**
 * HLS playlist models. VOD subset per PLAN Phase 4.
 */

data class HlsVariant(
    val url: String,
    val bandwidth: Long,
    val codecs: String? = null,
    val resolution: String? = null
)

data class HlsKey(
    val method: String,     // NONE, AES-128, SAMPLE-AES
    val uri: String? = null,
    val ivHex: String? = null,
    val keyFormat: String? = null
) {
    val isNone: Boolean get() = method.equals("NONE", ignoreCase = true)

    val isAes128: Boolean get() = method.equals("AES-128", ignoreCase = true)

    /** DRM or unsupported protection (SAMPLE-AES, FairPlay, Widevine, ClearKey...). */
    val isDrmOrUnsupported: Boolean
        get() {
            if (isNone || isAes128) {
                return keyFormat?.let {
                    val f = it.lowercase()
                    f.contains("widevine") ||
                            f.contains("streamingkeydelivery") ||
                            f.contains("clearkey") ||
                            f.startsWith("urn:uuid:")
                } == true
            }
            return true
        }
}

data class HlsByterange(
    val length: Long,
    val offset: Long?   // null = previous offset + length of the same resource
)

data class HlsSegment(
    val url: String,
    val durationSeconds: Double,
    val sequence: Long,
    val key: HlsKey? = null,
    val byterange: HlsByterange? = null
)

data class HlsMediaPlaylist(
    val segments: List<HlsSegment>,
    val isLive: Boolean,
    val targetDurationSeconds: Long,
    val mediaSequence: Long,
    val initSegmentUrl: String? = null,
    val initSegmentByterange: HlsByterange? = null
)

sealed interface HlsPlaylist {
    data class Master(val variants: List<HlsVariant>) : HlsPlaylist
    data class Media(val playlist: HlsMediaPlaylist) : HlsPlaylist
}
