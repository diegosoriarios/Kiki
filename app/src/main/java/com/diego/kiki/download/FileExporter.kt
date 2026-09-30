package com.diego.kiki.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Exports a completed in-app download to the public MediaStore Downloads collection.
 * API 29+: MediaStore, no permission needed.
 * API 26-28: legacy public Downloads dir, requires WRITE_EXTERNAL_STORAGE (granted at runtime).
 */
object FileExporter {

    fun exportToDownloads(context: Context, source: File, mimeType: String?): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportViaMediaStore(context, source, mimeType)
        } else {
            exportLegacy(context, source)
        }
    }

    private fun exportViaMediaStore(context: Context, source: File, mimeType: String?): Uri? {
        return try {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType ?: "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: return null
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun exportLegacy(context: Context, source: File): Uri? {
        if (!DownloadStorage.isExternalStorageWritable()) return null
        return try {
            val downloadsDir = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val target = File(downloadsDir, DownloadStorage.uniqueFileName(downloadsDir, source.name))
            source.copyTo(target, overwrite = false)
            Uri.fromFile(target)
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
