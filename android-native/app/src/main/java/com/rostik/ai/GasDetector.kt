package com.rostik.ai

import android.media.Image
import kotlin.math.max
import kotlin.math.min

/**
 * Experimental Kotlin port of the reference project's HSV gas mask.
 *
 * The reference detector uses OpenCV HSV ranges calibrated against its own fixtures.
 * Android's screen capture is RGBA, so this class converts sampled pixels to equivalent
 * HSV values and reports local gas-like pixel density around a supplied player candidate.
 *
 * This is diagnostic only: it cannot locate the player, identify the safe zone, or plan a
 * route. The caller must treat UNKNOWN as unknown and must not turn this signal into movement
 * until it has been compared with labelled captures from the target device and game themes.
 */
internal class GasDetector {
    enum class Status { UNKNOWN, LIKELY_IN_GAS, LIKELY_OUTSIDE_GAS }

    data class Result(
        val status: Status,
        val gasFraction: Float?,
        val sampledPixels: Int,
        val gasPixels: Int
    )

    fun inspect(frame: Image, candidate: GameAiPipeline.Point?): Result {
        if (candidate == null || !candidate.x.isFinite() || !candidate.y.isFinite()) {
            return Result(Status.UNKNOWN, null, 0, 0)
        }
        val plane = frame.planes.firstOrNull()
            ?: return Result(Status.UNKNOWN, null, 0, 0)
        val width = frame.width
        val height = frame.height
        if (width < 2 || height < 2) return Result(Status.UNKNOWN, null, 0, 0)

        val cx = (candidate.x * width).toInt().coerceIn(0, width - 1)
        val cy = (candidate.y * height).toInt().coerceIn(0, height - 1)
        // Reference implementation inspects a window of 2.5 player radii. The current
        // Android color cue has no calibrated radius, so use a conservative 4.5%-of-frame
        // half-window and expose this limitation rather than pretending it is scale-invariant.
        val halfX = max(8, (width * WINDOW_HALF_FRACTION).toInt())
        val halfY = max(8, (height * WINDOW_HALF_FRACTION).toInt())
        val x0 = max(0, cx - halfX)
        val x1 = min(width, cx + halfX + 1)
        val y0 = max(0, cy - halfY)
        val y1 = min(height, cy + halfY + 1)

        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride < 3) return Result(Status.UNKNOWN, null, 0, 0)

        var sampled = 0
        var gas = 0
        val stepX = max(1, (x1 - x0) / GRID_SAMPLES_PER_AXIS)
        val stepY = max(1, (y1 - y0) / GRID_SAMPLES_PER_AXIS)
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val offset = y * rowStride + x * pixelStride
                if (offset >= 0 && offset + 2 < buffer.limit()) {
                    val r = buffer.get(offset).toInt() and 0xff
                    val g = buffer.get(offset + 1).toInt() and 0xff
                    val b = buffer.get(offset + 2).toInt() and 0xff
                    sampled++
                    val hsv = rgbToHsv(r, g, b)
                    // Reference OpenCV range H=42..66 (0..179), S=55..155, V=165..255.
                    // Hue is converted to degrees here; saturation/value stay 0..255.
                    val gasLike = hsv[0] in 84f..132f && hsv[1] in 55f..155f && hsv[2] >= 165f
                    // Exclude bright yellow power-cube pixels. Full spatial dilation from the
                    // Python implementation is intentionally omitted in this first Kotlin port.
                    val cubeYellow = hsv[0] in 36f..70f && hsv[1] >= 140f && hsv[2] >= 150f
                    if (gasLike && !cubeYellow) gas++
                }
                x += stepX
            }
            y += stepY
        }
        if (sampled == 0) return Result(Status.UNKNOWN, null, 0, 0)
        val fraction = gas.toFloat() / sampled
        val status = when {
            fraction >= IN_GAS_FRACTION -> Status.LIKELY_IN_GAS
            else -> Status.LIKELY_OUTSIDE_GAS
        }
        return Result(status, fraction, sampled, gas)
    }

    private fun rgbToHsv(r8: Int, g8: Int, b8: Int): FloatArray {
        val r = r8 / 255f
        val g = g8 / 255f
        val b = b8 / 255f
        val maximum = max(r, max(g, b))
        val minimum = min(r, min(g, b))
        val delta = maximum - minimum
        var hue = when {
            delta == 0f -> 0f
            maximum == r -> 60f * (((g - b) / delta) % 6f)
            maximum == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        if (hue < 0f) hue += 360f
        val saturation = if (maximum == 0f) 0f else delta / maximum
        return floatArrayOf(hue, saturation * 255f, maximum * 255f)
    }

    companion object {
        private const val WINDOW_HALF_FRACTION = 0.045f
        private const val GRID_SAMPLES_PER_AXIS = 48
        private const val IN_GAS_FRACTION = 0.08f
    }
}
