package com.rostik.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv8 detector for the pinned upstream BrawlStarsBot model.
 *
 * This is perception only. It must not directly issue touch actions. The model
 * was trained for the upstream project's desktop capture domain, so detections
 * on phone screenshots need manual evaluation before a policy can trust them.
 */
class OnDeviceBrawlerDetector(context: Context) : AutoCloseable {
    data class Detection(
        val label: String,
        val classId: Int,
        val confidence: Float,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    private data class Letterbox(
        val bitmap: Bitmap,
        val scale: Float,
        val padX: Float,
        val padY: Float
    )

    private val env = OrtEnvironment.getEnvironment()
    private val modelFile = copyAsset(context, MODEL_ASSET)
    private val session: OrtSession = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
    private val inputName: String
    private val outputName: String
    private val outputShape: LongArray
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        val inputs = session.inputInfo
        require(inputs.size == 1) { "Detector must have exactly one input, found ${inputs.size}" }
        val input = inputs.entries.first()
        inputName = input.key
        val inputInfo = input.value.info as? ai.onnxruntime.TensorInfo
            ?: error("Detector input is not a tensor")
        require(inputInfo.shape.contentEquals(longArrayOf(1, 3, MODEL_HEIGHT.toLong(), MODEL_WIDTH.toLong()))) {
            "Unsupported detector input shape: ${inputInfo.shape.contentToString()}"
        }
        require(session.outputInfo.size == 1) { "Detector must have exactly one output" }
        val output = session.outputInfo.entries.first()
        outputName = output.key
        val outputInfo = output.value.info as? ai.onnxruntime.TensorInfo
            ?: error("Detector output is not a tensor")
        outputShape = outputInfo.shape
        require(outputShape.size == 3 && outputShape.any { it == (4 + LABELS.size).toLong() }) {
            "Unsupported YOLO output shape: ${outputShape.contentToString()}"
        }
    }

    @Synchronized
    fun detect(source: Bitmap): List<Detection> {
        val prepared = letterbox(source)
        val input = FloatArray(3 * MODEL_WIDTH * MODEL_HEIGHT)
        val pixels = IntArray(MODEL_WIDTH * MODEL_HEIGHT)
        prepared.bitmap.getPixels(pixels, 0, MODEL_WIDTH, 0, 0, MODEL_WIDTH, MODEL_HEIGHT)
        val planeSize = MODEL_WIDTH * MODEL_HEIGHT
        for (i in pixels.indices) {
            val pixel = pixels[i]
            input[i] = Color.red(pixel) / 255.0f
            input[planeSize + i] = Color.green(pixel) / 255.0f
            input[planeSize * 2 + i] = Color.blue(pixel) / 255.0f
        }
        prepared.bitmap.recycle()

        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(
            1, 3, MODEL_HEIGHT.toLong(), MODEL_WIDTH.toLong()
        ))
        try {
            val result = session.run(mapOf(inputName to tensor))
            try {
                val value = result.get(outputName).orElseThrow { IllegalStateException("YOLO output is missing") }
                val output = (value as? OnnxTensor)?.floatBuffer
                    ?: error("YOLO output is not a float tensor")
                val raw = FloatArray(output.remaining())
                output.get(raw)
                return decode(raw, outputShape, source.width, source.height, prepared.scale, prepared.padX, prepared.padY)
            } finally {
                result.close()
            }
        } finally {
            tensor.close()
        }
    }

    private fun letterbox(source: Bitmap): Letterbox {
        val scale = min(MODEL_WIDTH.toFloat() / source.width, MODEL_HEIGHT.toFloat() / source.height)
        val scaledWidth = max(1, (source.width * scale).toInt())
        val scaledHeight = max(1, (source.height * scale).toInt())
        val padX = (MODEL_WIDTH - scaledWidth) / 2f
        val padY = (MODEL_HEIGHT - scaledHeight) / 2f
        val output = Bitmap.createBitmap(MODEL_WIDTH, MODEL_HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawColor(Color.rgb(114, 114, 114))
            val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
            drawBitmap(scaled, padX, padY, paint)
            if (scaled !== source) scaled.recycle()
        }
        return Letterbox(output, scale, padX, padY)
    }

    private fun decode(
        raw: FloatArray,
        shape: LongArray,
        sourceWidth: Int,
        sourceHeight: Int,
        scale: Float,
        padX: Float,
        padY: Float
    ): List<Detection> {
        val second = shape[1].toInt()
        val third = shape[2].toInt()
        val channelsFirst = second == 4 + LABELS.size
        val channels = if (channelsFirst) second else third
        val candidates = if (channelsFirst) third else second
        require(channels == 4 + LABELS.size) { "Unexpected output tensor dimensions: ${shape.contentToString()}" }
        val stride = candidates
        val boxes = mutableListOf<Detection>()
        for (index in 0 until candidates) {
            fun at(channel: Int): Float =
                if (channelsFirst) raw[channel * stride + index] else raw[index * channels + channel]
            var bestClass = -1
            var bestScore = 0f
            for (classId in LABELS.indices) {
                val score = at(4 + classId)
                if (score > bestScore) {
                    bestScore = score
                    bestClass = classId
                }
            }
            if (bestClass < 0 || bestScore < THRESHOLDS[bestClass]) continue
            val cx = at(0)
            val cy = at(1)
            val width = at(2)
            val height = at(3)
            if (!cx.isFinite() || !cy.isFinite() || !width.isFinite() || !height.isFinite()) continue
            val left = ((cx - width / 2f - padX) / scale).coerceIn(0f, sourceWidth.toFloat())
            val top = ((cy - height / 2f - padY) / scale).coerceIn(0f, sourceHeight.toFloat())
            val right = ((cx + width / 2f - padX) / scale).coerceIn(0f, sourceWidth.toFloat())
            val bottom = ((cy + height / 2f - padY) / scale).coerceIn(0f, sourceHeight.toFloat())
            if (right <= left || bottom <= top) continue
            boxes += Detection(LABELS[bestClass], bestClass, bestScore, left, top, right, bottom)
        }
        return nonMaximumSuppression(boxes, 0.45f)
    }

    private fun nonMaximumSuppression(detections: List<Detection>, iouLimit: Float): List<Detection> {
        val kept = mutableListOf<Detection>()
        for (classId in LABELS.indices) {
            val remaining = detections.filter { it.classId == classId }
                .sortedByDescending { it.confidence }.toMutableList()
            while (remaining.isNotEmpty()) {
                val best = remaining.removeAt(0)
                kept += best
                remaining.removeAll { iou(best, it) > iouLimit }
            }
        }
        return kept.sortedByDescending { it.confidence }
    }

    private fun iou(a: Detection, b: Detection): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    @Synchronized
    override fun close() {
        session.close()
    }

    private fun copyAsset(context: Context, asset: String): File {
        val file = File(context.codeCacheDir, "rostikai-brawler-detector.onnx")
        if (!file.isFile || file.length() == 0L) {
            val temp = File(file.parentFile, file.name + ".tmp")
            context.assets.open(asset).use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            }
            if (!temp.renameTo(file)) {
                temp.delete()
                error("Could not finalize detector model cache")
            }
        }
        return file
    }

    companion object {
        const val MODEL_ASSET = "models/brawler_detector/brawler_detector_yolov8.onnx"
        const val MODEL_WIDTH = 640
        const val MODEL_HEIGHT = 384
        val LABELS = listOf("Player", "Bush", "Enemy", "Cubebox")
        val THRESHOLDS = floatArrayOf(0.37f, 0.47f, 0.57f, 0.65f)
    }
}
