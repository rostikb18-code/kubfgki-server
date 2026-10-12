package com.rostik.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * User-enabled Android accessibility service.
 * This service only provides the platform gesture capability; no game strategy is embedded here.
 */
class GameGestureService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Unity-based games often expose no useful gameplay state through accessibility nodes.
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility gestures interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Gesture service connected")
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
    }
}
