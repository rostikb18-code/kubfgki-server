package com.rostik.ai

import android.content.Context
import android.util.Log
import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Discovers and opens bundled ONNX assets and reports their real tensor metadata.
 *
 * Opening a session proves that ONNX Runtime can parse the file; it does NOT prove
 * that preprocessing, output decoding, labels, or gameplay behavior are correct.
 * Do not use an unverified model to control touch gestures.
 */
class OnDeviceModelRegistry(private val context: Context) : AutoCloseable {
    data class TensorDescription(
        val name: String,
        val type: String,
        val shape: String
    )

    data class ModelInfo(
        val assetPath: String,
        val sha256: String,
        val inputs: List<TensorDescription>,
        val outputs: List<TensorDescription>
    )

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessions = mutableMapOf<String, OrtSession>()

    @Synchronized
    fun discoverAndOpen(): List<ModelInfo> {
        val found = mutableListOf<ModelInfo>()
        for (assetPath in findModelAssets("models")) {
            try {
                val localFile = copyAssetToCache(assetPath)
                require(localFile.isFile && localFile.length() > 0L) {
                    "Model asset is missing or empty: $assetPath"
                }
                val session = environment.createSession(localFile.absolutePath, OrtSession.SessionOptions())
                val info = ModelInfo(
                    assetPath = assetPath,
                    sha256 = sha256(localFile),
                    inputs = describeTensors(session.inputInfo),
                    outputs = describeTensors(session.outputInfo)
                )
                sessions.remove(assetPath)?.close()
                sessions[assetPath] = session
                found += info
                Log.i(TAG, "Opened $assetPath sha256=${info.sha256}; inputs=${info.inputs}; outputs=${info.outputs}")
            } catch (error: Exception) {
                Log.e(TAG, "Could not validate ONNX model $assetPath", error)
            }
        }
        if (found.isEmpty()) {
            Log.w(TAG, "No usable ONNX models found. No model is bundled unless a real .onnx asset is present.")
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

    private fun describeTensors(nodes: Map<String, NodeInfo>): List<TensorDescription> =
        nodes.map { (name, node) ->
            val tensor = node.info as? TensorInfo
            TensorDescription(
                name = name,
                type = tensor?.type?.toString() ?: node.info.javaClass.simpleName,
                shape = tensor?.shape?.contentToString() ?: "non-tensor/unknown"
            )
        }.sortedBy { it.name }

    private fun findModelAssets(directory: String): List<String> {
        val entries = try {
            context.assets.list(directory)?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        return entries.flatMap { name ->
            val path = "${directory}/${name}"
            val children = try { context.assets.list(path)?.toList().orEmpty() } catch (_: Exception) { emptyList() }
            when {
                name.endsWith(".onnx", ignoreCase = true) -> listOf(path)
                children.isNotEmpty() -> findModelAssets(path)
                else -> emptyList()
            }
        }
    }

    private fun copyAssetToCache(assetPath: String): File {
        val output = File(context.codeCacheDir, assetPath.replace('/', '_'))
        if (!output.exists() || output.length() == 0L) {
            val temporary = File(output.parentFile, output.name + ".tmp")
            context.assets.open(assetPath).use { input ->
                FileOutputStream(temporary).use { target -> input.copyTo(target) }
            }
            if (output.exists() && !output.delete()) {
                temporary.delete()
                error("Cannot replace cached model: $assetPath")
            }
            if (!temporary.renameTo(output)) {
                temporary.delete()
                error("Cannot finalize cached model: $assetPath")
            }
        }
        return output
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "RostikModelRegistry"
    }
}
