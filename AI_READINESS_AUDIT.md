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

## Latest CI evidence (2026-10-12)

- APK build workflow: **success** on commit `cf0b16c3c7c6f1724b1c0b4b254b7a76e52a9214`.
- Model acquisition workflow: **success**, but the HP OCR architecture source URL returned HTTP 404; no HP OCR ONNX export was performed.
- APK catalog currently contains 107/109 roster entries and 107 portrait files. Vince and Cosmo portraits failed to download from all configured URLs; do not assume complete roster coverage.
- The model acquisition workflow has one exported detector role. `hud_reader`, `terrain_segmenter`, `gas_segmenter`, and `tactical_policy` are still missing as validated trained ONNX models.
- The detector's ONNX graph/CPU smoke test uses synthetic input. It does not measure precision/recall, Android-device latency, or real gameplay performance.
- The successful APK build proves compilation and packaging only. It does not prove installation, permission flow, overlay behavior, or match control on a physical device.

## Safe improvements already made on this branch

- Added an experimental perception/policy seam for future detector integration.
- Added explicit five-role ONNX inventory and per-role status reporting; graph-load success is clearly marked as unvalidated, and gameplay readiness stays false until model-specific validation is implemented.
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

This branch is an Android capture/overlay/gesture foundation with one bundled experimental detector and color-based diagnostics. It is **not yet a complete or validated Brawl Stars AI**: four model roles are still missing, the detector has not been validated on labeled gameplay, and reliable autonomous match control is not established. You can install the debug APK to test app startup, permissions, overlay, and capture on your own device; treat those as manual tests and do not expect the AI to play matches reliably.
