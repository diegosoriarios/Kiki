package com.diego.kiki.screenrec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.diego.kiki.data.AppDatabase
import com.diego.kiki.data.DownloadRecord
import com.diego.kiki.data.DownloadStatus
import com.diego.kiki.download.DownloadCoordinator
import com.diego.kiki.download.DownloadStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service capturing the screen with MediaProjection + MediaRecorder
 * (video-only v1). The resulting mp4 is written to the app downloads dir and
 * registered as a COMPLETED download record so it shows in the Downloads list.
 */
class RecordingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                @Suppress("DEPRECATION")
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultCode == Int.MIN_VALUE || resultData == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundWithNotification()
                if (!startRecording(resultCode, resultData)) {
                    stopSelf()
                }
                return START_STICKY
            }
        }
        stopSelf()
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Screen recording",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Recording screen")
            .setContentText("Tap to stop")
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startRecording(resultCode: Int, resultData: Intent): Boolean {
        return try {
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: return false
            projection = mediaProjection

            val metrics = screenMetrics()
            val width = even(metrics.widthPixels.coerceAtMost(1920))
            val height = even(metrics.heightPixels.coerceAtMost(1920))

            val file = File(
                DownloadStorage.downloadsDir(this),
                "screen-${System.currentTimeMillis() / 1000}.mp4"
            )
            outputFile = file

            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder = mediaRecorder
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setOutputFile(file.absolutePath)
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mediaRecorder.setVideoEncodingBitRate(8_000_000)
            mediaRecorder.setVideoFrameRate(30)
            mediaRecorder.setVideoSize(width, height)
            mediaRecorder.prepare()

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "kiki-screen-rec",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.surface,
                null,
                null
            )
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // User revoked projection from the system cast/record tile
                    finalizeRecording()
                    stopSelf()
                }
            }, null)

            mediaRecorder.start()
            _isRecording.value = true
            true
        } catch (_: Exception) {
            cleanup(keepFile = false)
            false
        }
    }

    private fun stopRecording() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // No frames recorded or already stopped
        }
        finalizeRecording()
    }

    private fun finalizeRecording() {
        if (!_isRecording.value && recorder == null) return
        val file = outputFile
        cleanup(keepFile = true)
        if (file != null && file.exists() && file.length() > 0) {
            serviceScope.launch {
                AppDatabase.getDatabase(applicationContext).downloadDao().insertDownload(
                    DownloadRecord(
                        id = java.util.UUID.randomUUID().toString(),
                        url = "screen-recording",
                        fileName = file.name,
                        filePath = file.absolutePath,
                        mimeType = "video/mp4",
                        totalBytes = file.length(),
                        downloadedBytes = file.length(),
                        status = DownloadStatus.COMPLETED,
                        downloadType = DownloadCoordinator.TYPE_SCREEN
                    )
                )
            }
        }
    }

    private fun cleanup(keepFile: Boolean) {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            recorder?.reset()
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        if (keepFile) {
            outputFile = null
        } else {
            outputFile?.delete()
            outputFile = null
        }
        _isRecording.value = false
    }

    override fun onDestroy() {
        if (_isRecording.value) {
            stopRecording()
        }
        super.onDestroy()
    }

    private fun screenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }
        display?.getRealMetrics(metrics)
        return metrics
    }

    private fun even(value: Int): Int = value - (value % 2)

    companion object {
        private const val CHANNEL_ID = "kiki_screen_record"
        private const val NOTIFICATION_ID = 4242
        const val ACTION_START = "com.diego.kiki.screenrec.START"
        const val ACTION_STOP = "com.diego.kiki.screenrec.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
