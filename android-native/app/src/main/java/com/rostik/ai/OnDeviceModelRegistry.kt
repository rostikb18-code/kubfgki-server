package com.rostik.ai

import android.content.Context
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream

/**
 * Discovers and opens ONNX models bundled under app/src/main/assets/models/.
 *
 * This class deliberately does not guess tensor shapes, normalize pixels, or interpret
 * model outputs. Those steps must match the exact model's documented preprocessing,
 * labels and output schema. No model is shipped by this class.
 */
class OnDeviceModelRegistry(private val context: Context) : AutoCloseable {
    data class ModelInfo(
        val assetPath: String,
        val inputs: List<String>,
        val outputs: List<String>
    )

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessions = mutableMapOf<String, OrtSession>()

    @Synchronized
    fun discoverAndOpen(): List<ModelInfo> {
        val modelPaths = findModelAssets("models")
        val found = mutableListOf<ModelInfo>()
        for (assetPath in modelPaths) {
            try {
                val localFile = copyAssetToCache(assetPath)
                val session = environment.createSession(localFile.absolutePath, OrtSession.SessionOptions())
                sessions[assetPath] = session
                found += ModelInfo(
                    assetPath = assetPath,
                    inputs = session.inputNames.toList(),
                    outputs = session.outputNames.toList()
                )
                Log.i(TAG, "Opened ONNX model $assetPath; inputs=${session.inputNames}; outputs=${session.outputNames}")
            } catch (error: Exception) {
                Log.e(TAG, "Could not open ONNX model $assetPath", error)
            }
        }
        if (found.isEmpty()) {
            Log.w(TAG, "No ONNX models found. Add licensed, verified .onnx files to app/src/main/assets/models/")
        }
        return found
    }

    @Synchronized
    fun availableModels(): List<String> = sessions.keys.toList()

    @Synchronized
    override fun close() {
        sessions.values.forEach { session ->
            try {
                session.close()
            } catch (error: Exception) {
                Log.w(TAG, "Error closing ONNX session", error)
            }
        }
        sessions.clear()
    }

    private fun findModelAssets(directory: String): List<String> {
        val entries = try {
            context.assets.list(directory)?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        return entries.flatMap { name ->
            val path = "$" + "{directory}/$" + "{name}"
            val children = try { context.assets.list(path)?.toList().orEmpty() } catch (_: Exception) { emptyList() }
            when {
                name.endsWith(".onnx", ignoreCase = true) -> listOf(path)
                children.isNotEmpty() -> findModelAssets(path)
                else -> emptyList()
            }
        }
    }

    private fun copyAssetToCache(assetPath: String): File {
        val safeName = assetPath.replace('/', '_')
        val output = File(context.codeCacheDir, safeName)
        if (!output.exists() || output.length() == 0L) {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(output).use { target -> input.copyTo(target) }
            }
        }
        return output
    }

    companion object {
        private const val TAG = "RostikModelRegistry"
    }
}
