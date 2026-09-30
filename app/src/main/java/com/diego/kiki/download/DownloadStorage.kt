package com.diego.kiki.download

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.util.Locale

object DownloadStorage {

    fun downloadsDir(context: Context): File {
        val external = context.getExternalFilesDir(null)
        val base = if (external != null && external.exists()) {
            File(external, "Downloads")
        } else {
            File(context.filesDir, "Downloads")
        }
        if (!base.exists()) base.mkdirs()
        return base
    }

    fun partFile(context: Context, id: String): File {
        val cacheDir = File(context.cacheDir, "downloads")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        return File(cacheDir, "$id.part")
    }

    fun sanitizeFileName(name: String): String {
        val cleaned = name
            .substringAfterLast('/')
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.ifEmpty { "download.bin" }
    }

    fun fileNameFromUrl(url: String): String {
        val path = Uri.parse(url).path ?: return "download.bin"
        val raw = path.substringBefore(';').substringAfterLast('/')
        val decoded = try {
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (_: Exception) {
            raw
        }
        return sanitizeFileName(decoded)
    }

    fun fileNameFromHeaders(url: String, contentDisposition: String?, mimeType: String?): String {
        val fromDisposition = contentDisposition
            ?.substringAfter("filename=", "")
            ?.trim('"', '\'', ' ')
            ?.takeIf { it.isNotEmpty() }
        val name = fromDisposition
            ?: fileNameFromUrl(url)
        val withExt = if (name.contains('.')) name else appendExtension(name, mimeType)
        return sanitizeFileName(withExt)
    }

    fun uniqueFileName(dir: File, fileName: String): String {
        var candidate = fileName
        var index = 1
        while (File(dir, candidate).exists()) {
            val dot = fileName.lastIndexOf('.')
            candidate = if (dot > 0) {
                "${fileName.substring(0, dot)} (${index})${fileName.substring(dot)}"
            } else {
                "$fileName ($index)"
            }
            index++
        }
        return candidate
    }

    /**
     * Race-safe target resolution: [uniqueFileName] is check-then-act, so two
     * concurrent jobs can pick the same name before either creates its file.
     * Loop until the returned File truly does not exist.
     */
    fun resolveUniqueTarget(dir: File, fileName: String): File {
        repeat(10) {
            val candidate = File(dir, uniqueFileName(dir, fileName))
            if (!candidate.exists()) return candidate
        }
        val ext = fileExtensionOf(fileName).ifBlank { "bin" }
        return File(dir, "dl_${System.currentTimeMillis()}_${(100..999).random()}.$ext")
    }

    fun fileExtensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase(Locale.US)

    private fun appendExtension(name: String, mimeType: String?): String {
        val ext = when (mimeType?.substringBefore('/')) {
            "image" -> MIME_IMAGE_EXTENSIONS[mimeType] ?: "jpg"
            "video" -> MIME_VIDEO_EXTENSIONS[mimeType] ?: "mp4"
            "audio" -> MIME_AUDIO_EXTENSIONS[mimeType] ?: "mp3"
            "application" -> MIME_APP_EXTENSIONS[mimeType]
            else -> null
        } ?: "bin"
        return "$name.$ext"
    }

    fun isExternalStorageWritable(): Boolean =
        Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED

    private val MIME_IMAGE_EXTENSIONS = mapOf(
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "image/bmp" to "bmp",
        "image/svg+xml" to "svg"
    )

    private val MIME_VIDEO_EXTENSIONS = mapOf(
        "video/mp4" to "mp4",
        "video/webm" to "webm",
        "video/3gpp" to "3gp",
        "video/x-matroska" to "mkv",
        "video/quicktime" to "mov"
    )

    private val MIME_AUDIO_EXTENSIONS = mapOf(
        "audio/mpeg" to "mp3",
        "audio/mp4" to "m4a",
        "audio/ogg" to "ogg",
        "audio/wav" to "wav",
        "audio/aac" to "aac",
        "audio/flac" to "flac"
    )

    private val MIME_APP_EXTENSIONS = mapOf(
        "application/pdf" to "pdf",
        "application/zip" to "zip",
        "application/vnd.android.package-archive" to "apk"
    )
}
