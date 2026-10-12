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
    enum class CombatRole { CLOSE_RANGE, LONG_RANGE, UNKNOWN }

    data class Threat(
        val position: Point,
        val confidence: Float,
        val role: CombatRole = CombatRole.UNKNOWN,
        // True only when a validated detector confirms that this threat is currently visible.
        val visible: Boolean = true
    )

    data class Observation(
        val frameWidth: Int,
        val frameHeight: Int,
        val player: Point?,
        val nearestThreat: Point?,
        val playerConfidence: Float,
        val threatConfidence: Float,
        // Null until a validated HUD-health decoder supplies a calibrated estimate [0..1].
        val playerHealthFraction: Float? = null,
        // Optional evidence from a future trained scene detector; absent means unknown, not false.
        val visibleThreats: List<Threat> = emptyList(),
        val nearestBush: Point? = null,
        val safeZoneCenter: Point? = null,
        val safeZoneRadius: Float? = null,
        val smokeBoundaryPoint: Point? = null,
        val ownRole: CombatRole = CombatRole.UNKNOWN,
        val ammoFraction: Float? = null,
        val hasLineOfSight: Boolean? = null
    )
    sealed interface Action {
        data class Move(val dx: Float, val dy: Float, val durationMs: Long = 280L, val reason: String = "reposition") : Action
        data class Retreat(val dx: Float, val dy: Float, val durationMs: Long = 320L, val reason: String = "disengage") : Action
        data class Attack(val target: Point, val reason: String = "engage") : Action
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

    /**
     * Conservative tactical policy scaffold. It only consumes explicit detector outputs;
     * the current color-only analyze() method does not produce reliable health, bushes,
     * range classes, safe-zone geometry, ammo or line-of-sight values.
     */
    fun decide(observation: Observation): Action {
        val player = observation.player ?: return Action.NoOp
        if (observation.playerConfidence < MIN_PLAYER_CONFIDENCE) return Action.NoOp

        val health = observation.playerHealthFraction
        val safeRadius = observation.safeZoneRadius?.takeIf { it.isFinite() && it > 0f }
        val safeCenter = observation.safeZoneCenter

        // Avoid the poison gas / storm only when a detector provides a boundary point.
        // Move inward toward a known safe-zone center when available; otherwise move away
        // from the observed boundary point. No fabricated smoke estimate is used.
        val smokePoint = observation.smokeBoundaryPoint
        if (smokePoint != null && (health == null || health > CRITICAL_HEALTH_THRESHOLD)) {
            val target = safeCenter ?: Point(
                (player.x + (player.x - smokePoint.x)).coerceIn(0.02f, 0.98f),
                (player.y + (player.y - smokePoint.y)).coerceIn(0.02f, 0.98f)
            )
            return movementToward(player, target, "escape_storm", 360L)
        }

        // Low health: disengage toward cover (bush) when its location is actually detected,
        // otherwise move away from the nearest confirmed visible threat.
        if (health != null && health.isFinite() && health <= LOW_HEALTH_RETREAT_THRESHOLD) {
            val bush = observation.nearestBush
            if (bush != null) return movementToward(player, bush, "low_hp_seek_cover", 360L)
            val enemy = bestVisibleThreat(observation)
            if (enemy != null) return movementAway(player, enemy.position, "low_hp_retreat", 360L)
            return Action.NoOp
        }

        val enemy = bestVisibleThreat(observation)
        if (enemy == null) {
            // No confirmed enemy: use a detected bush as cover; otherwise stay still rather
            // than wandering toward unobserved hazards. A future map/path planner can patrol.
            val bush = observation.nearestBush
            if (bush != null) return movementToward(player, bush, "no_enemy_seek_cover", 320L)
            if (safeCenter != null && safeRadius != null && distance(player, safeCenter) > safeRadius * 0.70f) {
                return movementToward(player, safeCenter, "stay_inside_safe_zone", 320L)
            }
            return Action.NoOp
        }

        val enemyDistance = distance(player, enemy.position)
        val targetRole = enemy.role
        val ownRole = observation.ownRole
        val ammo = observation.ammoFraction

        // If we are long range, keep distance and only attack a confirmed target when
        // line-of-sight and ammunition are known to be sufficient.
        if (ownRole == CombatRole.LONG_RANGE) {
            if (enemyDistance < LONG_RANGE_KEEP_DISTANCE) {
                return movementAway(player, enemy.position, "long_range_keep_distance", 300L)
            }
            if (observation.hasLineOfSight == true && (ammo == null || ammo > 0.05f)) {
                return Action.Attack(enemy.position, "long_range_shot")
            }
            return Action.NoOp
        }

        // Against a long-range enemy, a close-range fighter should approach only if healthy,
        // preferably via detected cover; avoid blindly crossing open space.
        if (ownRole == CombatRole.CLOSE_RANGE && targetRole == CombatRole.LONG_RANGE) {
            if (health != null && health < APPROACH_HEALTH_THRESHOLD) {
                return movementAway(player, enemy.position, "too_weak_to_approach_ranged_enemy", 320L)
            }
            val bush = observation.nearestBush
            if (bush != null && enemyDistance > CLOSE_RANGE_ENGAGE_DISTANCE) {
                return movementToward(player, bush, "flank_through_cover", 320L)
            }
            if (enemyDistance > CLOSE_RANGE_ENGAGE_DISTANCE) {
                // Approach in short bounded bursts; do not attack until within the close-range
                // threshold and only if line-of-sight is confirmed.
                return movementToward(player, enemy.position, "close_distance_on_ranged_enemy", 220L)
            }
        }

        if (ownRole == CombatRole.CLOSE_RANGE && enemyDistance > CLOSE_RANGE_ENGAGE_DISTANCE) {
            return movementToward(player, enemy.position, "close_range_approach", 220L)
        }

        if (observation.hasLineOfSight == true && (ammo == null || ammo > 0.05f)) {
            return Action.Attack(enemy.position, "confirmed_target_in_range")
        }
        return Action.NoOp
    }

    private fun bestVisibleThreat(observation: Observation): Threat? =
        observation.visibleThreats
            .asSequence()
            .filter { it.visible && it.confidence >= MIN_THREAT_CONFIDENCE }
            .minByOrNull { threat -> observation.player?.let { distance(it, threat.position) } ?: Float.MAX_VALUE }
            ?: observation.nearestThreat?.takeIf { observation.threatConfidence >= MIN_THREAT_CONFIDENCE }
                ?.let { Threat(it, observation.threatConfidence) }

    private fun distance(a: Point, b: Point): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun movementToward(from: Point, to: Point, reason: String, duration: Long): Action {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val norm = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.0001f)
        return Action.Move((dx / norm).coerceIn(-1f, 1f), (dy / norm).coerceIn(-1f, 1f), duration, reason)
    }

    private fun movementAway(from: Point, danger: Point, reason: String, duration: Long): Action {
        val dx = from.x - danger.x
        val dy = from.y - danger.y
        val norm = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.0001f)
        return Action.Retreat((dx / norm).coerceIn(-1f, 1f), (dy / norm).coerceIn(-1f, 1f), duration, reason)
    }

    companion object {
        private const val MIN_PLAYER_CONFIDENCE = 0.18f
        private const val MIN_THREAT_CONFIDENCE = 0.12f
        private const val LOW_HEALTH_RETREAT_THRESHOLD = 0.30f
        private const val CRITICAL_HEALTH_THRESHOLD = 0.15f
        private const val APPROACH_HEALTH_THRESHOLD = 0.45f
        private const val LONG_RANGE_KEEP_DISTANCE = 0.20f
        private const val CLOSE_RANGE_ENGAGE_DISTANCE = 0.12f
        private const val TAG = "RostikGameAI"
    }
}
