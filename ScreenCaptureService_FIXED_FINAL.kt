package com.rostik.touchbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Activity.RESULT_OK
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat

/**
 * Stable screen capture service.
 *
 * The order is intentional for Android 14+:
 * 1) MainActivity obtains the user's MediaProjection consent.
 * 2) The service is started as a foreground service.
 * 3) Only after that do we call getMediaProjection().
 *
 * This avoids the crash path that occurred in the later 30.14 branch.
 */
class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val NOTIFICATION_ID = 29
        const val CHANNEL_ID = "rostik_screen_capture"

        @Volatile
        var onBitmap: ((Bitmap) -> Unit)? = null
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var started = false
    private var projectionCallback: MediaProjection.Callback? = null
    private var lastFrameAt = 0L
    private var feedWindowStartedAt = 0L
    private var feedFrames = 0L
    private var recoveryAttempts = 0
    private val watchdog = object : Runnable {
        override fun run() {
            if (!started) return
            val now = System.currentTimeMillis()
            if (now - lastFrameAt > 1800L && recoveryAttempts < 3) {
                recoveryAttempts++
                BotRuntime.runtimeMetrics.recoveryCount++
                BotRuntime.runtimeMetrics.lastRecovery = "capture_watchdog#$recoveryAttempts"
                try { RecoveryEvents.log(BotRuntime.applicationContext(), "capture_watchdog", "No frame for 1800ms", "Reader/display restart #$recoveryAttempts") } catch (_: Throwable) {}
                Log.w("RostikCapture", "Frame watchdog recovery #$recoveryAttempts")
                DiscordLogger.info(DiscordLogger.Category.SCREEN, "Frame watchdog recovery", "recovery=$recoveryAttempts")
                try {
                    restartReaderAndVirtualDisplay()
                } catch (t: Throwable) {
                    Log.w("RostikCapture", "watchdog recovery failed", t)
                    DiscordLogger.error(DiscordLogger.Category.SCREEN, "Watchdog recovery failed", t)
                }
            }
            handler?.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        thread = HandlerThread("RostikCapture").also { it.start() }
        val captureThread = thread ?: throw IllegalStateException("Capture thread was not created")
        handler = Handler(captureThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != RESULT_OK || resultData == null) {
            Log.w("RostikCapture", "Missing MediaProjection consent data")
            DiscordLogger.error(DiscordLogger.Category.SCREEN, "MediaProjection consent missing", message = "Screen capture service started without valid consent data")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        return try {
            // IMPORTANT: promote to mediaProjection foreground BEFORE getMediaProjection().
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }

            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("MediaProjection returned null")

            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i("RostikCapture", "MediaProjection stopped by system/user")
                    stopCapture(stopProjection = false)
                    stopSelf()
                }
            }
            val activeProjection = projection ?: throw IllegalStateException("MediaProjection became null")
            val callback = projectionCallback ?: throw IllegalStateException("Projection callback was not created")
            activeProjection.registerCallback(callback, handler)

            startReaderAndVirtualDisplay()
            started = true
            recoveryAttempts = 0
            lastFrameAt = System.currentTimeMillis()
            feedWindowStartedAt = lastFrameAt
            feedFrames = 0
            BotRuntime.updateFeedFps(0.0)
            handler?.postDelayed(watchdog, 1000L)
            BotRuntime.setCaptureActive(true)
            START_NOT_STICKY
        } catch (t: Throwable) {
            Log.e("RostikCapture", "Unable to start screen capture", t)
            BotRuntime.setCaptureActive(false)
            stopCapture()
            stopSelfResult(startId)
            START_NOT_STICKY
        }
    }

    private fun startReaderAndVirtualDisplay() {
        val dm = resources.displayMetrics
        val width = dm.widthPixels.coerceAtLeast(1)
        val height = dm.heightPixels.coerceAtLeast(1)
        val density = dm.densityDpi.coerceAtLeast(1)

        reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )
        val activeReader = reader ?: throw IllegalStateException("ImageReader was not created")

        activeReader.setOnImageAvailableListener({ source ->
            val image = try { source.acquireLatestImage() } catch (_: Throwable) { null } ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes.firstOrNull() ?: return@setOnImageAvailableListener
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride.coerceAtLeast(1)
                val rowStride = plane.rowStride.coerceAtLeast(width * pixelStride)
                val paddedWidth = (rowStride / pixelStride).coerceAtLeast(width)

                val bitmap = Bitmap.createBitmap(
                    paddedWidth,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                val frame = if (paddedWidth != width) {
                    Bitmap.createBitmap(bitmap, 0, 0, width, height).also {
                        bitmap.recycle()
                    }
                } else {
                    bitmap
                }

                lastFrameAt = System.currentTimeMillis()
                recoveryAttempts = 0
                feedFrames++
                val feedElapsed = (lastFrameAt - feedWindowStartedAt).coerceAtLeast(1L)
                if (feedElapsed >= 2000L) {
                    BotRuntime.updateFeedFps(feedFrames * 1000.0 / feedElapsed)
                    feedWindowStartedAt = lastFrameAt
                    feedFrames = 0
                }
                val callback = onBitmap
                if (callback != null) {
                    var handedOff = false
                    try {
                        callback(frame)
                        handedOff = true
                    } finally {
                        // The normal callback transfers ownership to MatchSupervisor.
                        // If the callback itself throws before accepting the frame,
                        // recycle it here so a capture exception cannot leak one frame.
                        if (!handedOff) {
                            try { if (!frame.isRecycled) frame.recycle() } catch (_: Throwable) {}
                        }
                    }
                } else {
                    try { if (!frame.isRecycled) frame.recycle() } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                Log.w("RostikCapture", "Frame processing failed", t)
            } finally {
                try { image.close() } catch (_: Throwable) {}
            }
        }, handler)

        val activeProjection = projection ?: throw IllegalStateException("MediaProjection is unavailable")
        virtualDisplay = activeProjection.createVirtualDisplay(
            "RostikTouchBotCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            activeReader.surface,
            null,
            handler
        )
    }

    private fun restartReaderAndVirtualDisplay() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try { reader?.close() } catch (_: Throwable) {}
        reader = null
        startReaderAndVirtualDisplay()
        lastFrameAt = System.currentTimeMillis()
        feedWindowStartedAt = lastFrameAt
        feedFrames = 0
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Rostik screen capture",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun notification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("RostikAI")
                .setContentText("Захват экрана активен")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("RostikAI")
                .setContentText("Захват экрана активен")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
        }
    }

    private fun stopCapture(stopProjection: Boolean = true) {
        started = false
        try { handler?.removeCallbacks(watchdog) } catch (_: Throwable) {}
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try { reader?.close() } catch (_: Throwable) {}
        reader = null
        try { projectionCallback?.let { projection?.unregisterCallback(it) } } catch (_: Throwable) {}
        projectionCallback = null
        if (stopProjection) {
            try { projection?.stop() } catch (_: Throwable) {}
        }
        projection = null
        BotRuntime.setCaptureActive(false)
    }

    override fun onDestroy() {
        stopCapture()
        onBitmap = null
        try { thread?.quitSafely() } catch (_: Throwable) {}
        thread = null
        handler = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
