package com.rostik.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * User-enabled Android accessibility service.
 * This service only provides the platform gesture capability; no game strategy is embedded here.
 */
class GameGestureService : AccessibilityService() {
    private var commandReceiver: BroadcastReceiver? = null
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Unity-based games often expose no useful gameplay state through accessibility nodes.
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility gestures interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null || intent.getPackage() != packageName) return
                if (!intent.getBooleanExtra(EXTRA_USER_ARMED, false)) {
                    Log.w(TAG, "Rejected gesture command: explicit user arming is required")
                    return
                }
                when (intent.action) {
                    ACTION_TAP -> dispatchValidatedTap(
                        intent.getFloatExtra(EXTRA_X, Float.NaN),
                        intent.getFloatExtra(EXTRA_Y, Float.NaN))
                    ACTION_SWIPE -> dispatchValidatedSwipe(
                        intent.getFloatExtra(EXTRA_X1, Float.NaN),
                        intent.getFloatExtra(EXTRA_Y1, Float.NaN),
                        intent.getFloatExtra(EXTRA_X2, Float.NaN),
                        intent.getFloatExtra(EXTRA_Y2, Float.NaN),
                        intent.getLongExtra(EXTRA_DURATION, 250L))
                }
            }
        }
        val filter = IntentFilter().apply { addAction(ACTION_TAP); addAction(ACTION_SWIPE) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
        commandReceiver = receiver
        Log.i(TAG, "Gesture service connected; commands require explicit arming")
    }

    override fun onDestroy() {
        commandReceiver?.let { runCatching { unregisterReceiver(it) } }
        commandReceiver = null
        super.onDestroy()
    }

    private fun dispatchValidatedTap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        if (!x.isFinite() || !y.isFinite()) return false
        val metrics = resources.displayMetrics
        if (x < 0f || y < 0f || x >= metrics.widthPixels || y >= metrics.heightPixels) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun dispatchValidatedSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite()) return false
        val metrics = resources.displayMetrics
        if (x1 < 0f || y1 < 0f || x2 < 0f || y2 < 0f ||
            x1 >= metrics.widthPixels || x2 >= metrics.widthPixels ||
            y1 >= metrics.heightPixels || y2 >= metrics.heightPixels) return false
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(40L, 1200L)))
            .build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Gesture completed")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Gesture cancelled")
            }
        }, null)
    }

    companion object {
        private const val TAG = "RostikGestures"
        const val ACTION_TAP = "com.rostik.ai.action.GESTURE_TAP"
        const val ACTION_SWIPE = "com.rostik.ai.action.GESTURE_SWIPE"
        const val EXTRA_USER_ARMED = "user_armed"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"
        const val EXTRA_X1 = "x1"
        const val EXTRA_Y1 = "y1"
        const val EXTRA_X2 = "x2"
        const val EXTRA_Y2 = "y2"
        const val EXTRA_DURATION = "duration_ms"
    }
}
