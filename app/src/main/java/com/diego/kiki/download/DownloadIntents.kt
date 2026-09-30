package com.diego.kiki.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

object DownloadIntents {

    fun contentUri(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
    }

    fun resolveMimeType(mimeType: String?, fileName: String): String {
        if (!mimeType.isNullOrBlank() && mimeType != "application/octet-stream") return mimeType
        val ext = DownloadStorage.fileExtensionOf(fileName)
        if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        }
        return when (ext) {
            "mp4", "webm", "mkv", "mov", "3gp", "avi", "ts" -> "video/*"
            "mp3", "m4a", "ogg", "wav", "aac", "flac" -> "audio/*"
            "jpg", "jpeg", "png", "gif", "webp" -> "image/*"
            else -> "application/octet-stream"
        }
    }

    fun openFile(context: Context, file: File, mimeType: String?): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    contentUri(context, file),
                    resolveMimeType(mimeType, file.name)
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun shareFile(context: Context, file: File, mimeType: String?): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = resolveMimeType(mimeType, file.name)
                putExtra(Intent.EXTRA_STREAM, contentUri(context, file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Exception) {
            false
        }
    }
}
