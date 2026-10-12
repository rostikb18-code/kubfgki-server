package com.rostik.ai

import android.content.Context
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream

/**
 * Strict inventory/loader for the five planned on-device model roles.
 *
 * IMPORTANT: loading an ONNX graph proves only that ONNX Runtime can parse it. It does NOT
 * prove semantic compatibility. Each model still needs a documented tensor contract,
 * preprocessing/postprocessing, labels, fixture tests and a validated checksum before it
 * can be used by gameplay. This project currently ships no verified models for these roles.
 */
class OnDeviceModelRegistry(private val context: Context) : AutoCloseable {
    enum class Role(val assetPath: String, val purpose: String) {
        SCENE_DETECTOR("models/scene_detector.onnx", "Player/enemy/object detections"),
        HUD_READER("models/hud_reader.onnx", "Health, ammo and super HUD"),
        TERRAIN_SEGMENTER("models/terrain_segmenter.onnx", "Walkable ground, walls and bushes"),
        GAS_SEGMENTER("models/gas_segmenter.onnx", "Poison gas/safe-zone perception"),
        TACTICAL_POLICY("models/tactical_policy.onnx", "Action scores from a documented state vector")
    }

    enum class Status { MISSING, OPEN_FAILED, GRAPH_OPENED_UNVALIDATED }

    data class RoleStatus(
        val role: Role,
        val status: Status,
        val detail: String
    )

    data class ModelInfo(
        val assetPath: String,
        val inputs: List<String>,
        val outputs: List<String>
    )

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessions = linkedMapOf<String, OrtSession>()

    /**
     * Opens any bundled ONNX files for diagnostic inspection. Arbitrary files are not
     * assigned a role just because they end in .onnx.
     */
    @Synchronized
    fun discoverAndOpen(): List<ModelInfo> {
        val found = mutableListOf<ModelInfo>()
        for (assetPath in findModelAssets("models")) {
            if (sessions.containsKey(assetPath)) {
                val existing = sessions.getValue(assetPath)
                found += ModelInfo(assetPath, existing.inputNames.toList(), existing.outputNames.toList())
                continue
            }
            try {
                val session = openSession(assetPath)
                sessions[assetPath] = session
                found += ModelInfo(assetPath, session.inputNames.toList(), session.outputNames.toList())
                Log.i(TAG, "ONNX graph opened (not semantically validated): $assetPath")
            } catch (error: Exception) {
                Log.e(TAG, "Could not open ONNX model $assetPath", error)
            }
        }
        if (found.isEmpty()) Log.w(TAG, "No ONNX models found under app/src/main/assets/models/")
        return found
    }

    /**
     * Opens the five explicitly named roles and returns a status for every role, including
     * missing ones. GRAPH_OPENED_UNVALIDATED must never be treated as gameplay-ready.
     */
    @Synchronized
    fun loadRequiredRoles(): List<RoleStatus> = Role.entries.map { role ->
        val existing = sessions[role.assetPath]
        if (existing != null) {
            RoleStatus(role, Status.GRAPH_OPENED_UNVALIDATED,
                "Graph opened; input/output contract and accuracy have not been validated")
        } else if (!assetExists(role.assetPath)) {
            RoleStatus(role, Status.MISSING, "Missing asset: app/src/main/assets/${role.assetPath}")
        } else {
            try {
                sessions[role.assetPath] = openSession(role.assetPath)
                val session = sessions.getValue(role.assetPath)
                RoleStatus(role, Status.GRAPH_OPENED_UNVALIDATED,
                    "Inputs=${session.inputNames}; outputs=${session.outputNames}; semantic validation still required")
            } catch (error: Exception) {
                Log.e(TAG, "Could not open role ${role.name}", error)
                RoleStatus(role, Status.OPEN_FAILED, error.message ?: error.javaClass.simpleName)
            }
        }
    }

    @Synchronized
    fun availableModels(): List<String> = sessions.keys.toList()

    @Synchronized
    fun isGameplayReady(): Boolean = false // No role has passed model-specific validation yet.

    @Synchronized
    override fun close() {
        sessions.values.forEach { session ->
            try { session.close() } catch (error: Exception) {
                Log.w(TAG, "Error closing ONNX session", error)
            }
        }
        sessions.clear()
    }

    private fun openSession(assetPath: String): OrtSession {
        val localFile = copyAssetToCache(assetPath)
        return environment.createSession(localFile.absolutePath, OrtSession.SessionOptions())
    }

    private fun assetExists(assetPath: String): Boolean {
        val parent = assetPath.substringBeforeLast('/', "")
        val name = assetPath.substringAfterLast('/')
        return try { context.assets.list(parent)?.contains(name) == true } catch (_: Exception) { false }
    }

    private fun findModelAssets(directory: String): List<String> {
        val entries = try { context.assets.list(directory)?.toList().orEmpty() } catch (_: Exception) { emptyList() }
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
        val safeName = assetPath.replace('/', '_')
        val output = File(context.codeCacheDir, safeName)
        if (!output.exists() || output.length() == 0L) {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(output).use { target -> input.copyTo(target) }
            }
        }
        return output
    }

    companion object { private const val TAG = "RostikModelRegistry" }
}
