# External AI component review — RostikAI

Date: 2026-10-12
Target branch: `feature/on-device-ai-pipeline`
Reference project: https://github.com/jjeong-11/brawlstarsRL
Reference license: MIT (retain the copyright and license notice if code is copied or adapted).

## What the reference project actually provides

The repository documents a Python/OpenCV perception pipeline for Solo Showdown: player anchor, health, ammo, enemies/boxes, gas, terrain, match state, and a live state adapter. It also has a persistent occupancy map and A* waypoint planner. Its README explicitly describes limitations and missing/weak extractors; the project is a research prototype, not a drop-in Android SDK.

## Compatibility decision

Do not copy its Stable-Baselines3 `.zip` policy checkpoints into `app/src/main/assets/models/` and do not pass them to ONNX Runtime. Those are Python/SB3 archives, not ONNX graphs. A compatible model would need an explicit export, a fixed observation/action schema, matching preprocessing, output decoding, and parity tests.

The current RostikAI Android pipeline uses Android `Image` frames and has no OpenCV dependency or equivalent Python runtime. The Python perception modules therefore need a deliberate port or a separate runtime integration; copying the files alone will not connect them to MediaProjection.

## Recommended staged integration

1. **Capture/fixture contract:** save representative, permission-safe game frames at multiple resolutions and map themes; document pixel format, row stride, crop and coordinate conventions.
2. **Port gas detection first:** implement HSV mask and cube-glow exclusion in a small isolated Kotlin component. Compare it against labelled frames; keep actions disabled until false-positive/false-negative results are acceptable. The Python constants are calibrated to the reference project's fixtures and must not be assumed calibrated for every device/theme.
3. **Player/HUD extraction:** port or replace the anchor and HP/ammo readers, with explicit unknown results when confidence is low. Do not convert missing reads into zero health or empty ammo.
4. **Threat and terrain perception:** port enemy/box extraction and the three-state terrain grid (free/blocked/unknown). Unknown pixels must not automatically become walls.
5. **Tracking and navigation:** only after the preceding components pass tests, add temporal smoothing, camera-scroll tracking, persistent map and bounded waypoint planning.
6. **Policy and actions:** map verified state to the Kotlin policy. Keep execution disabled by default and require explicit arm/pause/stop controls. Every action must be bounded and interrupted on stale frames, low confidence, gas, pause or stop.
7. **Validation gates:** unit tests on labelled fixtures; confidence/coverage report; Android build; then real-device observation-only tests. A successful Gradle build is not gameplay validation.

## Current status

This review is research and an integration plan, not a claim that the Python detector/planner is already connected. The current Android color-centroid detector still does not reliably identify player, enemies, health, ammo, bushes, or gas. Automatic gestures must remain disabled until those gaps are closed and measured.

## Attribution

Reference project: Copyright (c) 2026 jjeong-11, MIT License.
If code from that repository is copied or adapted, preserve the required copyright and license notice in the distributed source.
