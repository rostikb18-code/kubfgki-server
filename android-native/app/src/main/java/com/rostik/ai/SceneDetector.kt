package com.rostik.ai

import android.content.Context
import android.media.Image
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Runs the bundled YOLO scene detector for observation/logging only.
 * This does not issue gestures. Until real held-out gameplay frames are evaluated,
 * detections are not trusted enough to drive game actions.
 */
internal class SceneDetector(context: Context) : AutoCloseable {
    data class Detection(val label: String, val score: Float, val cx: Float, val cy: Float, val width: Float, val height: Float)

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val asset = "models/scene_detector.onnx"
        val file = File(context.codeCacheDir, "scene_detector.onnx")
        if (!file.exists() || file.length() == 0L) {
            context.assets.open(asset).use { input -> FileOutputStream(file).use { output -> input.copyTo(output) } }
        }
        session = env.createSession(file.absolutePath, OrtSession.SessionOptions())
        val input = session.inputInfo.values.firstOrNull()
            ?: throw IllegalStateException("Scene detector has no input")
        val shape = input.info as? ai.onnxruntime.TensorInfo
            ?: throw IllegalStateException("Scene detector input is not a tensor")
        require(shape.shape.contentEquals(longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong()))) {
            "Unexpected scene detector input shape: ${shape.shape.contentToString()}"
        }
    }

    @Synchronized
    fun detect(frame: Image, threshold: Float = 0.35f, maxResults: Int = 20): List<Detection> {
        val plane = frame.planes.firstOrNull() ?: return emptyList()
        val input = FloatArray(3 * SIZE * SIZE)
        val scale = min(SIZE.toFloat() / frame.width, SIZE.toFloat() / frame.height)
        val scaledW = (frame.width * scale).toInt().coerceAtLeast(1)
        val scaledH = (frame.height * scale).toInt().coerceAtLeast(1)
        val padX = (SIZE - scaledW) / 2
        val padY = (SIZE - scaledH) / 2
        // YOLO export uses RGB float32 in NCHW and 114-valued letterbox padding.
        for (i in input.indices) input[i] = 114f / 255f
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        for (dy in 0 until scaledH) {
            val sy = ((dy / scale).toInt()).coerceIn(0, frame.height - 1)
            for (dx in 0 until scaledW) {
                val sx = ((dx / scale).toInt()).coerceIn(0, frame.width - 1)
                val offset = sy * rowStride + sx * pixelStride
                if (offset < 0 || offset + 2 >= buffer.limit()) continue
                val target = (dy + padY) * SIZE + dx + padX
                input[target] = (buffer.get(offset).toInt() and 0xff) / 255f
                input[SIZE * SIZE + target] = (buffer.get(offset + 1).toInt() and 0xff) / 255f
                input[2 * SIZE * SIZE + target] = (buffer.get(offset + 2).toInt() and 0xff) / 255f
            }
        }
        val inputName = session.inputNames.first()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val raw = result[0].value
                val batch = raw as? Array<*> ?: return emptyList()
                val rows = batch.firstOrNull() as? Array<*> ?: return emptyList()
                if (rows.size < 5) return emptyList()
                val count = (rows.firstOrNull() as? FloatArray)?.size ?: return emptyList()
                val names = listOf("player", "enemy", "powercube", "powercube-box", "wall", "water", "bush", "zone")
                val detections = ArrayList<Detection>()
                // Exported graph contract observed in CI: [1, 12, 8400] = 4 box channels + 8 class channels.
                for (i in 0 until count) {
                    var bestClass = -1
                    var bestScore = 0f
                    for (c in 4 until min(rows.size, 4 + names.size)) {
                        val row = rows[c] as? FloatArray ?: continue
                        val score = row[i]
                        if (score > bestScore) { bestScore = score; bestClass = c - 4 }
                    }
                    if (bestClass < 0 || bestScore < threshold) continue
                    fun value(channel: Int): Float = ((rows[channel] as FloatArray)[i])
                    val cx = value(0)
                    val cy = value(1)
                    val bw = value(2)
                    val bh = value(3)
                    // Keep coordinates normalized for either normalized or pixel-space export output.
                    val divisor = if (max(max(cx, cy), max(bw, bh)) > 2f) SIZE.toFloat() else 1f
                    val left = ((cx - bw / 2f) / divisor - padX / SIZE.toFloat()) / (scaledW / SIZE.toFloat())
                    val top = ((cy - bh / 2f) / divisor - padY / SIZE.toFloat()) / (scaledH / SIZE.toFloat())
                    val w = (bw / divisor) / (scaledW / SIZE.toFloat())
                    val h = (bh / divisor) / (scaledH / SIZE.toFloat())
                    detections += Detection(names[bestClass], bestScore, left + w / 2f, top + h / 2f, w, h)
                }
                return detections.sortedByDescending { it.score }.take(maxResults)
            }
        }
    }

    override fun close() { session.close() }

    companion object { private const val SIZE = 640 }
}
