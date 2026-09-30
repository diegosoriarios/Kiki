package com.diego.kiki.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.diego.kiki.R

/**
 * Minimal progress notifications for HLS downloads (direct downloads use the
 * WorkManager foreground notification).
 */
object HlsNotifier {

    private const val CHANNEL_ID = "kiki_downloads"
    private const val BASE_NOTIFICATION_ID = 30000

    fun notificationId(downloadId: String): Int =
        BASE_NOTIFICATION_ID + (downloadId.hashCode() and 0x7FFFFFFF)

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.notification_channel_downloads),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }

    fun progress(context: Context, downloadId: String, fileName: String, done: Int, total: Int) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_downloading_title))
            .setContentText(fileName)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (total > 0) {
            builder.setProgress(total, done, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        try {
            manager.notify(notificationId(downloadId), builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted
        }
    }

    fun finished(
        context: Context,
        downloadId: String,
        fileName: String,
        success: Boolean,
        message: String?
    ) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId(downloadId))
        if (!success || message != null) {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(
                    if (success) context.getString(R.string.notification_download_done)
                    else context.getString(R.string.notification_download_failed)
                )
                .setContentText(message ?: fileName)
                .setSmallIcon(
                    if (success) android.R.drawable.stat_sys_download_done
                    else android.R.drawable.stat_notify_error
                )
                .setAutoCancel(true)
            try {
                manager.notify(notificationId(downloadId) + 1, builder.build())
            } catch (_: SecurityException) {
            }
        }
    }
}
