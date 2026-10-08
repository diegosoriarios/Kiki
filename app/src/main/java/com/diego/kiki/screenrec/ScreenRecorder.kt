package com.diego.kiki.screenrec

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.media.MediaRecorder
import android.view.Surface
import android.view.View
import android.webkit.WebView
import com.diego.kiki.data.AppDatabase
import com.diego.kiki.data.DownloadRecord
import com.diego.kiki.data.DownloadStatus
import com.diego.kiki.download.DownloadCoordinator
import com.diego.kiki.download.DownloadStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * In-process page recorder: captures ONLY the WebView's drawing by re-drawing
 * it into a bitmap and posting the frames to a MediaRecorder input surface.
 *
 * Deliberately no MediaProjection:
 * - no system consent dialog and no "share screen / app" choice
 * - browser chrome (URL bar, toolbars, download pill, auto-scroll panel and
 *   pins, the recording indicator itself) never appears in the video because
 *   it lives outside the WebView
 * - frames are only produced while the page is visible; recording auto-stops
 *   when the app goes to the background (wired in MainActivity.onStop)
 */
object ScreenRecorder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val stateLock = Any()
    private var appContext: Context? = null
    private var recorder: MediaRecorder? = null
    private var surface: Surface? = null
    private var outputFile: File? = null

    /** lockHardwareCanvas is API 29+; fall back to the software canvas once. */
    @Volatile
    private var hardwareCanvas = true

    /**
     * Starts recording the page rendered by [webViewProvider]'s WebView.
     * Returns false when there is no laid-out WebView to record yet.
     */
    fun start(context: Context, webViewProvider: () -> WebView?): Boolean {
        synchronized(stateLock) {
            if (_isRecording.value) return true
            val webView = webViewProvider() ?: return false
            if (webView.width <= 0 || webView.height <= 0) return false

            // Keep the page's aspect ratio: one uniform scale caps both dims at 1920
            val scale = minOf(
                1f,
                MAX_DIMENSION_PX.toFloat() / maxOf(webView.width, webView.height)
            )
            val width = even((webView.width * scale).toInt().coerceAtLeast(2))
            val height = even((webView.height * scale).toInt().coerceAtLeast(2))
            val file = File(
                DownloadStorage.downloadsDir(context.applicationContext),
                "screen-${System.currentTimeMillis() / 1000}.mp4"
            )
            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context.applicationContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            return try {
                mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mediaRecorder.setOutputFile(file.absolutePath)
                mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                mediaRecorder.setVideoEncodingBitRate(VIDEO_BIT_RATE)
                mediaRecorder.setVideoFrameRate(FRAME_RATE)
                mediaRecorder.setVideoSize(width, height)
                mediaRecorder.prepare()
                val inputSurface = mediaRecorder.surface

                appContext = context.applicationContext
                recorder = mediaRecorder
                surface = inputSurface
                outputFile = file
                hardwareCanvas = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

                mediaRecorder.start()
                _isRecording.value = true
                scope.launch {
                    frameLoop(webViewProvider, inputSurface, width, height)
                }
                true
            } catch (_: Exception) {
                try {
                    mediaRecorder.reset()
                    mediaRecorder.release()
                } catch (_: Exception) {
                }
                cleanup(keepFile = false)
                false
            }
        }
    }

    fun stop() {
        synchronized(stateLock) {
            if (!_isRecording.value) return
            _isRecording.value = false
        }
        scope.launch(Dispatchers.IO) { finalize() }
    }

    /** For lifecycle observers: stop only when a recording is live. */
    fun stopIfRecording() {
        if (_isRecording.value) stop()
    }

    // Frame pump ---------------------------------------------------------------------

    /**
     * Re-rasterizes the WebView into a recorder-resolution bitmap on the main
     * thread and posts it to the encoder surface, paced at ~30 fps max.
     *
     * PixelCopy has no View overload — and its Window source would composite
     * the app's overlay chrome into the video. [View.draw] into a software
     * canvas captures exactly the WebView (and nothing else), which is the
     * point of this recorder. Rasterizing at output resolution (via the canvas
     * scale) keeps the per-frame cost low on large screens.
     */
    private suspend fun frameLoop(
        webViewProvider: () -> WebView?,
        targetSurface: Surface,
        outWidth: Int,
        outHeight: Int
    ) {
        val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        var failures = 0
        while (_isRecording.value) {
            val view = webViewProvider()
            if (view == null || !view.isAttachedToWindow || view.width <= 0 || view.height <= 0) {
                // Page temporarily unavailable (tab switch mid-relayout...):
                // keep the recorder alive for a grace period, then finalize
                if (++failures >= MAX_IDLE_FRAMES) {
                    stop()
                    return
                }
                delay(FRAME_INTERVAL_MS)
                continue
            }
            val frameStart = android.os.SystemClock.uptimeMillis()
            if (drawViewToBitmap(view, bitmap, outWidth, outHeight)) {
                failures = 0
                postFrame(bitmap, targetSurface)
            } else if (++failures >= MAX_IDLE_FRAMES) {
                stop()
                return
            }
            val elapsed = android.os.SystemClock.uptimeMillis() - frameStart
            delay((FRAME_INTERVAL_MS - elapsed).coerceAtLeast(4L))
        }
    }

    /**
     * Rasterizes [view] into [bitmap] (output resolution) on the main thread —
     * View.draw must run on the view's thread.
     */
    private suspend fun drawViewToBitmap(
        view: View,
        bitmap: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Boolean = withContext(Dispatchers.Main) {
        try {
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.BLACK)
            val scale = minOf(
                outWidth.toFloat() / view.width,
                outHeight.toFloat() / view.height
            )
            canvas.scale(scale, scale)
            view.draw(canvas)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Posts a finished frame to the encoder surface (already output-sized). */
    private fun postFrame(bitmap: Bitmap, target: Surface) {
        try {
            val canvas = if (hardwareCanvas) {
                try {
                    target.lockHardwareCanvas()
                } catch (_: Exception) {
                    hardwareCanvas = false
                    target.lockCanvas(null)
                }
            } else {
                target.lockCanvas(null)
            } ?: return
            canvas.drawColor(android.graphics.Color.BLACK)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            target.unlockCanvasAndPost(canvas)
        } catch (_: Exception) {
            // Surface already shutting down — the next loop iteration decides
        }
    }

    // Teardown -----------------------------------------------------------------------

    private suspend fun finalize() {
        val file = outputFile
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // No frames recorded or already stopped
        }
        cleanup(keepFile = true)
        val context = appContext ?: return
        if (file != null && file.exists() && file.length() > 0) {
            try {
                val id = java.util.UUID.randomUUID().toString()
                AppDatabase.getDatabase(context).downloadDao().insertDownload(
                    DownloadRecord(
                        id = id,
                        url = "page-recording",
                        fileName = file.name,
                        filePath = file.absolutePath,
                        mimeType = "video/mp4",
                        totalBytes = file.length(),
                        downloadedBytes = file.length(),
                        status = DownloadStatus.COMPLETED,
                        downloadType = DownloadCoordinator.TYPE_SCREEN
                    )
                )
                DownloadCoordinator.getInstance(context).attachThumbnail(id)
            } catch (_: Exception) {
            }
        }
    }

    private fun cleanup(keepFile: Boolean) {
        try {
            surface?.release()
        } catch (_: Exception) {
        }
        surface = null
        try {
            recorder?.reset()
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        if (keepFile) {
            outputFile = null
        } else {
            outputFile?.delete()
            outputFile = null
        }
        _isRecording.value = false
    }

    private fun even(value: Int): Int = value - (value % 2)

    private const val MAX_DIMENSION_PX = 1920
    private const val VIDEO_BIT_RATE = 8_000_000
    private const val FRAME_RATE = 30

    /** Max frame pace (~30 fps); the actual fps adapts to raster cost. */
    private const val FRAME_INTERVAL_MS = 33L

    /** Frames without capturable content before the recording finalizes. */
    private const val MAX_IDLE_FRAMES = 90
}
