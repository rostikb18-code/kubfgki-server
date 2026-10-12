"""Platform-independent, conservative decision core for RostikAI.

This module deliberately does not capture screens or send input. Adapters must
supply observations with timestamps and confidence values.
"""
from dataclasses import dataclass
from enum import Enum
from typing import Optional


class ScreenState(str, Enum):
    UNKNOWN = "unknown"
    LOBBY = "lobby"
    LOADING = "loading"
    MATCH = "match"
    DEFEAT = "defeat"
    RESULTS = "results"


class ActionKind(str, Enum):
    NOOP = "noop"
    MOVE = "move"
    ATTACK = "attack"
    PAUSE = "pause"


@dataclass(frozen=True)
class Observation:
    state: ScreenState
    captured_at: float
    confidence: float
    player_visible: bool = False
    enemy_distance: Optional[float] = None
    health_fraction: Optional[float] = None


@dataclass(frozen=True)
class Decision:
    action: ActionKind
    reason: str


@dataclass(frozen=True)
class SafetyConfig:
    max_frame_age_seconds: float = 1.0
    minimum_confidence: float = 0.80
    attack_distance: float = 0.22
    low_health_fraction: float = 0.20


def decide(
    observation: Observation,
    now: float,
    config: SafetyConfig = SafetyConfig(),
    enabled: bool = True,
) -> Decision:
    """Return a safe, deterministic action; no action on uncertain/stale data."""
    if not enabled:
        return Decision(ActionKind.PAUSE, "controller disabled")
    age = now - observation.captured_at
    if age < 0:
        return Decision(ActionKind.NOOP, "observation timestamp is in the future")
    if age > config.max_frame_age_seconds:
        return Decision(ActionKind.NOOP, "stale frame")
    if not 0.0 <= observation.confidence <= 1.0:
        return Decision(ActionKind.NOOP, "invalid confidence")
    if observation.confidence < config.minimum_confidence:
        return Decision(ActionKind.NOOP, "confidence below safety threshold")
    if observation.state is not ScreenState.MATCH:
        return Decision(ActionKind.NOOP, f"not in active match: {observation.state.value}")
    if not observation.player_visible:
        return Decision(ActionKind.NOOP, "player not confidently detected")
    if observation.health_fraction is not None:
        if not 0.0 <= observation.health_fraction <= 1.0:
            return Decision(ActionKind.NOOP, "invalid health fraction")
        if observation.health_fraction <= config.low_health_fraction:
            return Decision(ActionKind.MOVE, "low health: prefer disengaging")
    if observation.enemy_distance is not None:
        if observation.enemy_distance < 0:
            return Decision(ActionKind.NOOP, "invalid enemy distance")
        if observation.enemy_distance <= config.attack_distance:
            return Decision(ActionKind.ATTACK, "enemy inside configured attack range")
    return Decision(ActionKind.MOVE, "no close enemy detected; movement policy is placeholder")
