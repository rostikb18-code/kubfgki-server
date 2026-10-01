package com.rostik.touchbot

import kotlin.math.hypot
import kotlin.math.max

/**
 * Deterministic, perception-driven Brawl Ball controller.
 *
 * It uses only current/temporally tracked detections. It does not invent an
 * enemy when the vision system is uncertain. When an opponent is visible the
 * attack vector is calculated explicitly and carried in ControlFrame. The
 * Accessibility layer turns that vector into a right-stick aim gesture.
 */
class ReactiveBrawlBallController(private val mapPlanner: MapPlanner) {
    private var lastSuperAt = 0L
    private var lastDodgeAt = 0L
    private var activeMapName: String? = null

    fun setMap(name: String?) { activeMapName = name; mapPlanner.setMap(name) }

    fun next(scene: MatchScene, now: Long, matchElapsedMs: Long): ControlFrame {
        val p = scene.player ?: return ControlFrame()
        val enemy = scene.enemies.asSequence()
            .filter { it.kind == "ENEMY" || it.kind == "PREDICTED_ENEMY" }
            .filter { it.confidence >= ENEMY_CONFIDENCE }
            .minByOrNull { distance(it.point, p.point) }

        val enemyDistance = enemy?.let { distance(it.point, p.point) } ?: Float.POSITIVE_INFINITY
        val attackAvailable = scene.rightJoystick != null && scene.attackConfidence >= ATTACK_CONTROL_CONFIDENCE

        // Directional aim is computed even before firing so the right-stick path
        // is stable when the enemy moves slightly between frames.
        val aim = enemy?.let {
            val predicted = Point(
                (it.point.x + it.velocity.x * 0.10f).coerceIn(.02f, .98f),
                (it.point.y + it.velocity.y * 0.10f).coerceIn(.08f, .94f)
            )
            aimVector(p.point, predicted)
        } ?: Point(0f, 0f)
        val enemyNear = enemy != null && enemyDistance <= ENEMY_NEAR_DISTANCE
        val ball = scene.ball
        val ballDistance = ball?.let { distance(it.point, p.point) } ?: Float.POSITIVE_INFINITY
        val ballNear = ball != null && ballDistance <= BALL_CONTROL_DISTANCE
        // In Brawl Ball the attack button is also the kick/clear action.
        // When the ball is at the player's position, prioritize a directional
        // attack toward the opponent goal even if no enemy is currently visible.
        if (!enemyNear && ballNear && attackAvailable) {
            val playerIsBottom = p.point.y >= .50f
            val goal = Point(.50f, if (playerIsBottom) .08f else .92f)
            val kickAim = aimVector(p.point, goal)
            return ControlFrame(aimX = kickAim.x, aimY = kickAim.y, fire = true)
        }
        if (enemyNear && attackAvailable) {
            val useSuper = shouldSuper(scene, enemyDistance <= SUPER_ENEMY_DISTANCE, now, matchElapsedMs)
            if (useSuper) {
                return ControlFrame(aimX = aim.x, aimY = aim.y, useSuper = true, superPoint = scene.superPoint)
            }
            return ControlFrame(aimX = aim.x, aimY = aim.y, fire = true)
        }

        // Dodge sideways when a close opponent is detected, but throttle it so
        // movement remains stable instead of oscillating every frame.
        if (enemy != null && enemyDistance < .18f && now - lastDodgeAt > 700L) {
            lastDodgeAt = now
            val ex = enemy.point.x - p.point.x
            val ey = enemy.point.y - p.point.y
            val sideX = -ey
            val sideY = ex
            val mag = max(.001f, hypot(sideX.toDouble(), sideY.toDouble()).toFloat())
            val sign = if (p.point.x < .5f) 1f else -1f
            return ControlFrame(
                moveX = (sideX / mag * sign).coerceIn(-1f, 1f),
                moveY = (sideY / mag * sign).coerceIn(-1f, 1f)
            )
        }

        // Brawl Ball can present the team from either vertical side depending
        // on the camera/spawn orientation. Infer the friendly side from the
        // player's current position instead of assuming blue is always bottom.
        val playerIsBottom = p.point.y >= .50f
        val attackGoal = Point(.50f, if (playerIsBottom) .08f else .92f)
        val ownGoal = Point(.50f, if (playerIsBottom) .92f else .08f)
        val ballDistance = ball?.let { distance(it.point, p.point) } ?: Float.POSITIVE_INFINITY
        val ballThreat = ball != null && if (playerIsBottom) ball.point.y > .68f else ball.point.y < .32f
        val retreatY = if (playerIsBottom) .72f else .28f
        val moveTarget = when {
            ball != null && ballDistance < .18f -> attackGoal
            ball != null && !ballThreat -> ball.point
            ball != null -> Point(ball.point.x, retreatY)
            else -> ownGoal
        }

        // Keep a small map-dependent pathing hook. MapPlanner is installed by
        // MatchSupervisor through setMap; unknown maps use direct movement.
        val target = mapPlanner.waypoint(p.point, moveTarget)
        return ControlFrame(
            moveX = (target.x - p.point.x).coerceIn(-1f, 1f),
            moveY = (target.y - p.point.y).coerceIn(-1f, 1f),
            aimX = aim.x,
            aimY = aim.y
        )
    }

    private fun aimVector(from: Point, to: Point): Point {
        val dx = (to.x - from.x)
        val dy = (to.y - from.y)
        val mag = max(.001f, hypot(dx.toDouble(), dy.toDouble()).toFloat())
        return Point((dx / mag).coerceIn(-1f, 1f), (dy / mag).coerceIn(-1f, 1f))
    }

    private fun shouldSuper(scene: MatchScene, enemyNear: Boolean, now: Long, elapsed: Long): Boolean {
        if (!scene.superReady || !enemyNear) return false
        if (scene.superPoint == null || scene.superConfidence < SUPER_CONTROL_CONFIDENCE) return false
        if (elapsed < 8_000L || now - lastSuperAt < 2_800L) return false
        lastSuperAt = now
        return true
    }

    private fun distance(a: Point, b: Point): Float = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    companion object {
        private const val ENEMY_NEAR_DISTANCE = .34f
        private const val SUPER_ENEMY_DISTANCE = .27f
        private const val ENEMY_CONFIDENCE = .72f
        private const val ATTACK_CONTROL_CONFIDENCE = .55f
        private const val SUPER_CONTROL_CONFIDENCE = .55f
        private const val BALL_CONTROL_DISTANCE = .16f
    }
}
