package com.diego.kiki.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File

/**
 * Extracts a small JPEG thumbnail from a finished media file: a frame for
 * videos (works for direct MP4s, HLS-remuxed MP4s and page recordings),
 * embedded album art for audio. Purely local — no network, no codec deps.
 */
object ThumbnailExtractor {

    /** Longest thumbnail edge in px — list rows render at 56dp. */
    private const val MAX_EDGE_PX = 320

    /** Frame grab position: 1 s in (skips black intros), closest keyframe. */
    private const val FRAME_TIME_US = 1_000_000L

    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")
    private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov", "3gp", "avi", "ts", "m4v")

    /**
     * Extracts a thumbnail from [source] into [outFile]. Returns false when
     * the file isn't audio/video or no frame/art could be decoded.
     */
    fun extract(source: File, outFile: File, mimeType: String?): Boolean {
        val ext = DownloadStorage.fileExtensionOf(source.name)
        val isAudio = mimeType?.startsWith("audio") == true || ext in AUDIO_EXTENSIONS
        val isVideo = mimeType?.startsWith("video") == true || ext in VIDEO_EXTENSIONS
        if (!isAudio && !isVideo) return false

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source.absolutePath)
            val bitmap = if (isAudio) {
                retriever.embeddedPicture?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            } else {
                retriever.getFrameAtTime(FRAME_TIME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0)
            } ?: return false
            saveJpeg(bitmap, outFile)
            true
        } catch (_: Exception) {
            false
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun saveJpeg(source: Bitmap, outFile: File) {
        val longest = maxOf(source.width, source.height)
        val scaled = if (longest > MAX_EDGE_PX) {
            val factor = MAX_EDGE_PX.toFloat() / longest
            Bitmap.createScaledBitmap(
                source,
                (source.width * factor).toInt().coerceAtLeast(1),
                (source.height * factor).toInt().coerceAtLeast(1),
                true
            )
        } else {
            source
        }
        outFile.parentFile?.mkdirs()
        outFile.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (scaled !== source) scaled.recycle()
        source.recycle()
    }
}
