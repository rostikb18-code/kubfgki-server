package com.rostik.touchbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serialized gesture gateway.
 * Android AccessibilityService can silently reject overlapping gestures on some
 * devices. We therefore keep exactly one gesture in flight and release the lock
 * from the real completion callback (with a timeout fallback).
 */
class BotAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: BotAccessibilityService? = null }

    private val main = Handler(Looper.getMainLooper())
    private val gestureBusy = AtomicBoolean(false)
    @Volatile private var lastGestureAt = 0L

    override fun onServiceConnected(){ super.onServiceConnected(); instance=this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt(){ releaseGesture() }
    override fun onDestroy(){ releaseGesture(); if(instance===this) instance=null; super.onDestroy() }

    fun isGestureBusy(): Boolean = gestureBusy.get()
    fun lastGestureAt(): Long = lastGestureAt

    fun dispatch(frame:ControlFrame, left:Point = TouchLayout.MOVE, right:Point = TouchLayout.ATTACK, duration:Long=85L):Boolean {
        if (!gestureBusy.compareAndSet(false, true)) return false
        val w=resources.displayMetrics.widthPixels.toFloat()
        val h=resources.displayMetrics.heightPixels.toFloat()
        val builder=GestureDescription.Builder()
        var maxDuration = 55L
        var hasStroke = false

        if (kotlin.math.abs(frame.moveX) > .035f || kotlin.math.abs(frame.moveY) > .035f) {
            val move=TouchLayout.moveTarget(left, frame.moveX, frame.moveY)
            val lp=Path().apply{ moveTo(left.x*w,left.y*h); lineTo(move.x*w,move.y*h) }
            builder.addStroke(GestureDescription.StrokeDescription(lp,0,duration))
            maxDuration = maxOf(maxDuration, duration)
            hasStroke = true
        }
        if(frame.fire){
            val ax = frame.aimX.coerceIn(-1f, 1f)
            val ay = frame.aimY.coerceIn(-1f, 1f)
            val magnitude = kotlin.math.hypot(ax.toDouble(), ay.toDouble()).toFloat()
            val reach = if (magnitude > .08f) .105f else 0f
            val tx = (right.x + ax * reach).coerceIn(.06f, .94f)
            val ty = (right.y + ay * reach).coerceIn(.12f, .94f)
            val ap=Path().apply{
                moveTo(right.x*w,right.y*h)
                if (reach > 0f) lineTo(tx*w,ty*h) else lineTo(right.x*w,right.y*h)
            }
            builder.addStroke(GestureDescription.StrokeDescription(ap,0,70L))
            maxDuration = maxOf(maxDuration, 70L)
            hasStroke = true
        }
        if(frame.useSuper){
            val s=frame.superPoint ?: TouchLayout.SUPER
            val sp=Path().apply{ moveTo(s.x*w,s.y*h); lineTo(s.x*w,s.y*h) }
            builder.addStroke(GestureDescription.StrokeDescription(sp,0,55L))
            hasStroke = true
        }
        if (!hasStroke) {
            releaseGesture()
            return false
        }
        return submit(builder.build(), maxDuration + 420L)
    }

    fun tap(p:Point):Boolean {
        if (!gestureBusy.compareAndSet(false, true)) return false
        val x=p.x*resources.displayMetrics.widthPixels
        val y=p.y*resources.displayMetrics.heightPixels
        val path=Path().apply{moveTo(x,y);lineTo(x,y)}
        return submit(GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path,0,55L)).build(), 600L)
    }

    fun swipe(from: Point, to: Point, duration: Long = 420L): Boolean {
        if (!gestureBusy.compareAndSet(false, true)) return false
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val path = Path().apply { moveTo(from.x * w, from.y * h); lineTo(to.x * w, to.y * h) }
        return submit(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), duration + 700L)
    }

    private fun submit(gesture: GestureDescription, timeoutMs: Long): Boolean {
        lastGestureAt = System.currentTimeMillis()
        val ok = try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { releaseGesture() }
                override fun onCancelled(gestureDescription: GestureDescription?) { releaseGesture() }
            }, null)
        } catch (t: Throwable) {
            DiscordLogger.error(DiscordLogger.Category.GESTURE, "dispatchGesture exception", t)
            false
        }
        if (!ok) {
            DiscordLogger.error(DiscordLogger.Category.GESTURE, "dispatchGesture rejected", message = "Android rejected or cancelled the gesture submission")
            releaseGesture()
            return false
        }
        main.postDelayed({ releaseGesture() }, timeoutMs.coerceIn(250L, 1800L))
        return true
    }

    private fun releaseGesture() { gestureBusy.set(false) }
}
