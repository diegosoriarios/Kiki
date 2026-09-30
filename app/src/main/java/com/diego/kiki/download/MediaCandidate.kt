package com.diego.kiki.download

import kotlinx.serialization.Serializable

object MediaTypes {
    const val VIDEO = "VIDEO"
    const val AUDIO = "AUDIO"
    const val IMAGE = "IMAGE"
    const val HLS = "HLS"
    const val DASH = "DASH"
    const val BLOB = "BLOB"

    fun label(type: String): String = when (type) {
        VIDEO -> "Video"
        AUDIO -> "Audio"
        IMAGE -> "Image"
        HLS -> "HLS stream"
        DASH -> "DASH stream"
        BLOB -> "Page video (blob)"
        else -> "Media"
    }
}

@Serializable
data class MediaCandidate(
    val url: String,
    val type: String,
    val sourceTag: String = ""
)

/** One row in the bulk-download checklist. */
data class BulkItem(
    val url: String,
    val label: String,
    val type: String // MediaTypes.* (HLS, VIDEO, AUDIO, BLOB, ...)
)
