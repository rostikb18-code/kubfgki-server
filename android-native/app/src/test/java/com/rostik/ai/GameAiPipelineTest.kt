package com.rostik.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameAiPipelineTest {
    private val pipeline = GameAiPipeline()
    private val player = GameAiPipeline.Point(0.5f, 0.5f)

    private fun observation(
        health: Float? = null,
        threat: GameAiPipeline.Threat? = null,
        role: GameAiPipeline.CombatRole = GameAiPipeline.CombatRole.UNKNOWN,
        lineOfSight: Boolean? = null,
        ammo: Float? = null,
        bush: GameAiPipeline.Point? = null
    ) = GameAiPipeline.Observation(
        frameWidth = 1000,
        frameHeight = 1000,
        player = player,
        nearestThreat = threat?.position,
        playerConfidence = 0.9f,
        threatConfidence = threat?.confidence ?: 0f,
        playerHealthFraction = health,
        visibleThreats = threat?.let { listOf(it) } ?: emptyList(),
        nearestBush = bush,
        ownRole = role,
        ammoFraction = ammo,
        hasLineOfSight = lineOfSight
    )

    @Test
    fun lowHealthRetreatsFromVisibleThreat() {
        val enemy = GameAiPipeline.Threat(GameAiPipeline.Point(0.8f, 0.5f), 0.9f)
        val action = pipeline.decide(observation(health = 0.2f, threat = enemy))
        assertTrue(action is GameAiPipeline.Action.Retreat)
        action as GameAiPipeline.Action.Retreat
        assertTrue("Retreat should move away on X", action.dx < 0f)
        assertEquals("low_hp_retreat", action.reason)
    }

    @Test
    fun lowHealthPrefersDetectedBush() {
        val bush = GameAiPipeline.Point(0.3f, 0.7f)
        val action = pipeline.decide(observation(health = 0.2f, bush = bush))
        assertTrue(action is GameAiPipeline.Action.Move)
        action as GameAiPipeline.Action.Move
        assertEquals("low_hp_seek_cover", action.reason)
        assertTrue(action.dx < 0f && action.dy > 0f)
    }

    @Test
    fun longRangeFighterKeepsDistanceFromNearbyEnemy() {
        val enemy = GameAiPipeline.Threat(GameAiPipeline.Point(0.55f, 0.5f), 0.9f)
        val action = pipeline.decide(
            observation(threat = enemy, role = GameAiPipeline.CombatRole.LONG_RANGE)
        )
        assertTrue(action is GameAiPipeline.Action.Retreat)
        assertEquals("long_range_keep_distance", (action as GameAiPipeline.Action.Retreat).reason)
    }

    @Test
    fun closeRangeFighterApproachesRangedEnemyWhenHealthy() {
        val enemy = GameAiPipeline.Threat(
            GameAiPipeline.Point(0.8f, 0.5f), 0.9f,
            role = GameAiPipeline.CombatRole.LONG_RANGE
        )
        val action = pipeline.decide(
            observation(health = 0.8f, threat = enemy, role = GameAiPipeline.CombatRole.CLOSE_RANGE)
        )
        assertTrue(action is GameAiPipeline.Action.Move)
        assertEquals("close_distance_on_ranged_enemy", (action as GameAiPipeline.Action.Move).reason)
    }

    @Test
    fun doesNotAttackWithoutConfirmedLineOfSight() {
        val enemy = GameAiPipeline.Threat(GameAiPipeline.Point(0.75f, 0.5f), 0.9f)
        val action = pipeline.decide(
            observation(threat = enemy, role = GameAiPipeline.CombatRole.LONG_RANGE, lineOfSight = null, ammo = 1f)
        )
        assertEquals(GameAiPipeline.Action.NoOp, action)
    }

    @Test
    fun lowConfidencePlayerProducesNoAction() {
        val action = pipeline.decide(
            observation().copy(playerConfidence = 0.05f)
        )
        assertEquals(GameAiPipeline.Action.NoOp, action)
    }
}
