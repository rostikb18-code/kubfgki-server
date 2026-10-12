# RostikAI — critical AI readiness audit

Audit date: 2026-10-12  
Scope: `android-native/` and the current feature branch. This is a source/build review, not a real-device gameplay test.

## Current verified status

- The native Android project has a Gradle build workflow and an ONNX Runtime dependency.
- MediaProjection capture is implemented.
- An AccessibilityService can dispatch validated Android gestures when explicitly armed.
- The current experimental pipeline scans sparse screen pixels for green/red color cues.
- Frame diagnostics now run on a dedicated HandlerThread rather than the main looper.
- Gesture execution remains disabled in the capture loop. This is intentional because color cues are not reliable game-state detections.

## Critical blockers

1. **No verified trained detector is bundled.** The ONNX registry discovers model files if present, but does not itself provide weights. The build workflow currently reports model counts as a warning and can still create an APK with zero models.
2. **No detector preprocessing/output decoder exists.** Input dimensions, color ordering, normalization, labels, output tensor decoding, NMS, and confidence calibration are not implemented for a known model.
3. **Color heuristics do not identify all brawlers.** Green/red pixel clusters can be HUD, terrain, effects, skins, or UI. The resulting centroids are not dependable player/enemy positions.
4. **No game-state model exists.** Health, ammo, reload, super/gadget readiness, walls, objectives, safe zones, match start/end, death, and respawn are not robustly tracked.
5. **No tested decision policy exists.** The baseline's Move/Attack proposal is not connected to gestures and is not a trained RL policy. It should not be marketed as a working game bot.
6. **No feedback/control loop exists.** There is no verified action execution, cooldown/latency handling, post-action observation, or recovery strategy.
7. **CI is not gameplay validation.** A successful `assembleDebug` only proves compilation and packaging. It does not prove the app opens on a phone, permissions behave on a specific Android version, or gameplay works.
8. **Portrait counts are not proof of coverage.** Downloading a catalog during CI can fail or change over time; the current threshold allows partial icon coverage. Asset count alone does not validate that each portrait matches its brawler ID.

## Safe improvements already made on this branch

- Added an experimental perception/policy seam for future detector integration.
- Connected it to capture for diagnostics only.
- Moved frame analysis off the main looper to reduce UI-freeze risk.
- Kept automatic gestures disabled until perception and policy are verified.

## Release gate before calling this a working AI

- Pin and document a model with compatible license and known provenance.
- Bundle the model and verify its SHA-256, ONNX input/output metadata, and successful inference on representative frames.
- Implement and test exact preprocessing and postprocessing against labeled screenshots.
- Evaluate precision/recall for player, enemies, projectiles, walls, and objectives across maps, skins, effects, and resolutions.
- Add explicit game-state tracking and a conservative policy with bounded action durations.
- Integrate gestures behind an obvious user arm/pause/stop control; test that STOP reliably halts both capture and actions.
- Run instrumentation/manual device tests and save logs/screenshots for permission flow, lifecycle, latency, crashes, and match outcomes.
- Only then enable automatic gameplay and describe it as functional.

## Honest conclusion

This branch is an Android capture/overlay/gesture foundation plus experimental color diagnostics. It is **not yet a complete or validated Brawl Stars AI**, does not reliably recognize all brawlers, and should not autonomously control matches. A compiled APK may be useful for testing the shell and permissions, but not as proof of functioning gameplay AI.
