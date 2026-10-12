import unittest

from rostikai_core import (
    ActionKind,
    Observation,
    SafetyConfig,
    ScreenState,
    decide,
)


class DecisionSafetyTests(unittest.TestCase):
    def obs(self, **overrides):
        values = dict(
            state=ScreenState.MATCH,
            captured_at=10.0,
            confidence=0.95,
            player_visible=True,
            enemy_distance=0.8,
            health_fraction=0.9,
        )
        values.update(overrides)
        return Observation(**values)

    def test_stale_frame_never_sends_action(self):
        result = decide(self.obs(), now=12.0)
        self.assertEqual(result.action, ActionKind.NOOP)
        self.assertIn("stale", result.reason)

    def test_low_confidence_never_sends_action(self):
        result = decide(self.obs(confidence=0.3), now=10.1)
        self.assertEqual(result.action, ActionKind.NOOP)

    def test_unknown_or_lobby_state_never_moves(self):
        result = decide(self.obs(state=ScreenState.LOBBY), now=10.1)
        self.assertEqual(result.action, ActionKind.NOOP)

    def test_attack_only_when_enemy_inside_range(self):
        close = decide(self.obs(enemy_distance=0.1), now=10.1)
        far = decide(self.obs(enemy_distance=0.7), now=10.1)
        self.assertEqual(close.action, ActionKind.ATTACK)
        self.assertEqual(far.action, ActionKind.MOVE)

    def test_low_health_prefers_disengaging(self):
        result = decide(self.obs(health_fraction=0.1, enemy_distance=0.1), now=10.1)
        self.assertEqual(result.action, ActionKind.MOVE)
        self.assertIn("low health", result.reason)

    def test_disabled_controller_pauses(self):
        result = decide(self.obs(), now=10.1, enabled=False)
        self.assertEqual(result.action, ActionKind.PAUSE)

    def test_future_timestamp_and_invalid_values_fail_closed(self):
        future = decide(self.obs(captured_at=11.0), now=10.1)
        invalid = decide(self.obs(confidence=1.5), now=10.1)
        self.assertEqual(future.action, ActionKind.NOOP)
        self.assertEqual(invalid.action, ActionKind.NOOP)

    def test_custom_safety_threshold(self):
        cfg = SafetyConfig(minimum_confidence=0.99)
        result = decide(self.obs(), now=10.1, config=cfg)
        self.assertEqual(result.action, ActionKind.NOOP)


if __name__ == "__main__":
    unittest.main()
