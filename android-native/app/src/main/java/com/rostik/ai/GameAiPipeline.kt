package com.rostik.ai

import android.media.Image
import android.util.Log
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lightweight, deterministic on-device policy for the first integration milestone.
 *
 * IMPORTANT: this is a conservative color-based baseline, not a trained Brawl Stars model.
 * It must not be described as the brawlstarsRL PPO policy: that project's .zip checkpoints
 * are Stable-Baselines3/Python archives, not ONNX models and cannot be opened by OrtSession.
 *
 * This class intentionally separates perception, state and policy so a verified detector
 * and a compatible exported model can replace the baseline without changing gesture APIs.
 */
internal class GameAiPipeline {
    data class Point(val x: Float, val y: Float)
    data class Observation(
        val frameWidth: Int,
        val frameHeight: Int,
        val player: Point?,
        val nearestThreat: Point?,
        val playerConfidence: Float,
        val threatConfidence: Float
    )
    sealed interface Action {
        data class Move(val dx: Float, val dy: Float, val durationMs: Long = 280L) : Action
        data object Attack : Action
        data object NoOp : Action
    }

    fun analyze(image: Image): Observation {
        val plane = image.planes.firstOrNull()
            ?: return Observation(image.width, image.height, null, null, 0f, 0f)
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val w = image.width
        val h = image.height
        // Sparse scan bounds work on both portrait and landscape captures.
        val step = (minOf(w, h) / 180).coerceAtLeast(3)
        var greenX = 0.0; var greenY = 0.0; var greenN = 0
        var redX = 0.0; var redY = 0.0; var redN = 0
        var considered = 0
        var y = (h * 0.08f).toInt()
        while (y < (h * 0.94f).toInt()) {
            var x = (w * 0.04f).toInt()
            while (x < (w * 0.96f).toInt()) {
                val offset = y * rowStride + x * pixelStride
                if (offset >= 0 && offset + 2 < buffer.limit()) {
                    val r = buffer.get(offset).toInt() and 0xff
                    val g = buffer.get(offset + 1).toInt() and 0xff
                    val b = buffer.get(offset + 2).toInt() and 0xff
                    considered++
                    // Loose visual cues only. HUD, skins, damage effects and map palettes
                    // can produce false positives; confidence is deliberately conservative.
                    if (g > 145 && g > r * 1.28 && g > b * 1.10 && y > h * 0.12 && y < h * 0.88) {
                        greenX += x; greenY += y; greenN++
                    }
                    if (r > 165 && r > g * 1.35 && r > b * 1.25 &&
                        x > w * 0.12 && x < w * 0.88 && y > h * 0.12 && y < h * 0.88) {
                        redX += x; redY += y; redN++
                    }
                }
                x += step
            }
            y += step
        }
        val player = if (greenN >= 3) Point((greenX / greenN / w).toFloat(), (greenY / greenN / h).toFloat()) else null
        val threat = if (redN >= 5) Point((redX / redN / w).toFloat(), (redY / redN / h).toFloat()) else null
        val sampleCount = considered.coerceAtLeast(1)
        val playerConfidence = (greenN.toFloat() / sampleCount * 18f).coerceIn(0f, 1f)
        val threatConfidence = (redN.toFloat() / sampleCount * 12f).coerceIn(0f, 1f)
        return Observation(w, h, player, threat, playerConfidence, threatConfidence)
    }

    fun decide(observation: Observation): Action {
        val player = observation.player ?: return Action.NoOp
        val enemy = observation.nearestThreat ?: return Action.NoOp
        // Do not drive the screen from weak color evidence.
        if (observation.playerConfidence < 0.18f || observation.threatConfidence < 0.12f) return Action.NoOp
        val dx = enemy.x - player.x
        val dy = enemy.y - player.y
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        if (distance < 0.015f) return Action.Attack
        val norm = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.0001f)
        return Action.Move((dx / norm).coerceIn(-1f, 1f), (dy / norm).coerceIn(-1f, 1f))
    }

    companion object {
        private const val TAG = "RostikGameAI"
    }
}
