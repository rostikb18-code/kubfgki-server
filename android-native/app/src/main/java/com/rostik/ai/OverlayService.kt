package com.rostik.ai

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class OverlayService : Service() {
    companion object {
        const val ACTION_START = "com.rostik.ai.overlay.START"
        const val ACTION_PAUSE = "com.rostik.ai.overlay.PAUSE"
        const val ACTION_RESIZE = "com.rostik.ai.overlay.RESIZE"
    }

    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_PAUSE -> removeBubble()
            ACTION_RESIZE -> if (bubble != null) showBubble()
            ACTION_START -> {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Нужно разрешение на отображение поверх приложений", Toast.LENGTH_LONG).show()
                    stopSelf()
                } else showBubble()
            }
        }
        return START_NOT_STICKY
    }

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this)) return
        removeBubble()
        val prefs = getSharedPreferences("rostikai", MODE_PRIVATE)
        val widthDp = prefs.getInt("overlay_width", 64).coerceIn(44, 120)
        val heightDp = prefs.getInt("overlay_height", 64).coerceIn(44, 120)
        val density = resources.displayMetrics.density
        val view = TextView(this).apply {
            text = "AI"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18 * density
                setColor(Color.rgb(42, 151, 118))
                setStroke((1.5f * density).toInt(), Color.rgb(139, 255, 211))
            }
            contentDescription = "RostikAI floating button"
            setOnClickListener {
                val launch = packageManager.getLaunchIntentForPackage(packageName)
                launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (launch != null) startActivity(launch)
                Toast.makeText(this@OverlayService, "Игровой ИИ ещё не подключён", Toast.LENGTH_SHORT).show()
            }
        }
        val lp = WindowManager.LayoutParams(
            (widthDp * density).toInt(),
            (heightDp * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - (widthDp * density).toInt() - 12 * density).toInt().coerceAtLeast(0)
            y = (resources.displayMetrics.heightPixels * 0.28f).toInt()
        }
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = event.rawX; touchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 4 || abs(dy) > 4) {
                        lp.x = (startX + dx).coerceAtLeast(0)
                        lp.y = (startY + dy).coerceAtLeast(0)
                        try { windowManager.updateViewLayout(v, lp) } catch (_: Exception) {}
                        true
                    } else false
                }
                else -> false
            }
        }
        bubble = view
        params = lp
        try {
            windowManager.addView(view, lp)
        } catch (_: Exception) {
            bubble = null
            params = null
            Toast.makeText(this, "Не удалось показать кнопку AI", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeBubble() {
        val current = bubble ?: return
        try { windowManager.removeView(current) } catch (_: Exception) {}
        bubble = null
        params = null
    }

    override fun onDestroy() {
        removeBubble()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
