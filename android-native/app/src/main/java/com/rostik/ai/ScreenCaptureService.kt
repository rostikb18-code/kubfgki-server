package com.rostik.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

/**
 * MediaProjection capture with experimental color-cue diagnostics.
 * Color cues are not verified game recognition; this service deliberately sends no gameplay gestures.
 */
class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.rostik.ai.capture.START"
        const val ACTION_STOP = "com.rostik.ai.capture.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "rostikai_screen_capture"
        private const val NOTIFICATION_ID = 7301
        private const val TAG = "RostikCapture"
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var lastSampleAt = 0L
    private var sampledFrames = 0L
    private var analysisThread: HandlerThread? = null
    private val gameAiPipeline = GameAiPipeline()
    private val gasDetector = GasDetector()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode == 0 || data == null) {
                    Log.e(TAG, "Screen-capture permission result is missing")
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    startForegroundCompat()
                    startCapture(resultCode, data)
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to start screen capture", e)
                    stopCapture()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "RostikAI screen capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setContentTitle("RostikAI")
            .setContentText("Захват экрана активен; распознавание бойцов не подтверждено")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        stopCapture()
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
        // Android 14+ requires a registered callback before creating a VirtualDisplay.
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, android.os.Handler(mainLooper))
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        // Frame analysis is CPU work: keep it off the main looper to avoid freezing the UI.
        val worker = HandlerThread("RostikAI-FrameAnalysis").also { it.start() }
        analysisThread = worker
        reader.setOnImageAvailableListener({ source ->
            val frame = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastSampleAt >= 500L) {
                    lastSampleAt = now
                    val sample = sampleHudRegions(frame)
                    val observation = gameAiPipeline.analyze(frame)
                    val proposal = gameAiPipeline.decide(observation)
                    val gas = gasDetector.inspect(frame, observation.player)
                    sampledFrames++
                    Log.i(TAG, "EXPERIMENTAL_VISION frame=$sampledFrames size=${frame.width}x${frame.height} " +
                        "left=${sample[0]} right=${sample[1]} centerRight=${sample[2]} " +
                        "greenCandidate=${observation.player} greenConfidence=${observation.playerConfidence} " +
                        "redCandidate=${observation.nearestThreat} redConfidence=${observation.threatConfidence} " +
                        "proposal=$proposal gasStatus=${gas.status} gasFraction=${gas.gasFraction} " +
                        "gasSamples=${gas.sampledPixels} execution=DISABLED_NO_VERIFIED_DETECTOR")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Frame sampling failed; no actions sent", e)
            } finally {
                frame.close()
            }
        }, android.os.Handler(worker.looper))
        virtualDisplay = projection?.createVirtualDisplay(
            "RostikAI-Capture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )
        if (virtualDisplay == null) throw IllegalStateException("VirtualDisplay creation failed")
        Log.i(TAG, "Screen capture started: ${width}x${height}; experimental color diagnostics only; actions disabled")
    }


    /**
     * Lightweight normalized-region color sampling for diagnostics only.
     * Average colors do not identify attacks, super readiness, players, or movement.
     */
    private fun sampleHudRegions(frame: android.media.Image): List<String> {
        val plane = frame.planes.firstOrNull() ?: return listOf("n/a", "n/a", "n/a")
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = frame.width
        val height = frame.height
        fun average(xStart: Float, yStart: Float, xEnd: Float, yEnd: Float): String {
            val x0 = (width * xStart).toInt().coerceIn(0, width - 1)
            val x1 = (width * xEnd).toInt().coerceIn(x0 + 1, width)
            val y0 = (height * yStart).toInt().coerceIn(0, height - 1)
            val y1 = (height * yEnd).toInt().coerceIn(y0 + 1, height)
            var red = 0L
            var green = 0L
            var blue = 0L
            var count = 0L
            val stepX = ((x1 - x0) / 8).coerceAtLeast(1)
            val stepY = ((y1 - y0) / 8).coerceAtLeast(1)
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val offset = y * rowStride + x * pixelStride
                    if (offset >= 0 && offset + 2 < buffer.limit()) {
                        red += buffer.get(offset).toInt() and 0xff
                        green += buffer.get(offset + 1).toInt() and 0xff
                        blue += buffer.get(offset + 2).toInt() and 0xff
                        count++
                    }
                    x += stepX
                }
                y += stepY
            }
            if (count == 0L) return "n/a"
            return "%02x%02x%02x".format((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
        }
        return listOf(
            average(0.03f, 0.70f, 0.32f, 0.98f),
            average(0.70f, 0.70f, 0.98f, 0.98f),
            average(0.52f, 0.70f, 0.82f, 0.96f)
        )
    }

    private fun stopCapture() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { analysisThread?.quitSafely() } catch (_: Exception) {}
        analysisThread = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
