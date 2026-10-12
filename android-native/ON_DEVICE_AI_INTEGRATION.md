# RostikAI on-device AI integration assessment

Updated: 2026-10-12

## Decision

The target is a standalone Android APK on a real phone. No desktop Python, ADB host, emulator, cloud inference, or PC GPU may be required after installation.

## What is in the app now

- Native Android UI and overlay.
- Start requests Android MediaProjection permission.
- `ScreenCaptureService` owns the MediaProjection foreground service and receives frames.
- GitHub Actions builds the native Android project.

This is a capture/UI foundation, not a gameplay AI. The current capture service does not run detections or send touch actions to Brawl Stars. A green CI build proves compilation, not gameplay.

## Candidate code and license review

### jjeong-11/brawlstarsRL

Repository: https://github.com/jjeong-11/brawlstarsRL

- License: MIT (retain copyright and license text when redistributing copied code).
- Useful ideas: frame-only perception, game-state estimation, path planning, and an action policy.
- Not drop-in Android code: the project uses Python/Gymnasium/Stable-Baselines3 and ADB-based phone control. Its README scopes gameplay to Solo Showdown; the policy weights are not a ready Android ONNX model. The perception modules and action/control layer would need a deliberate port and device testing.
- Do not copy the Python training/runtime stack into the APK and claim it is on-device.

### PylaAI / Lemon-PYLA

Repository: https://github.com/LemonPepStep/Lemon-PYLA

- The project describes ONNX vision models for entity and tile detection and has queue/lobby automation.
- The README states a no-resale / source-available personal and development-use restriction. The official PylaAI project also identifies CC BY-NC 4.0 restrictions.
- Not selected as a redistributable code/model source for the APK without explicit permission from the rights holders. Its Windows/Python/ADB pipeline is not an Android runtime.

### AngelFireLA/BrawlStarsBotMaking

Repository: https://github.com/AngelFireLA/BrawlStarsBotMaking

- Useful reference catalogue for external-bot perception and model formats.
- The repository is primarily guides and model resources, not a finished on-device agent. Each model's file, license, class map, input tensor shape, output tensor format, and redistribution permission must be checked before bundling it.

## Android implementation path

1. Keep MediaProjection capture on-device and convert each captured frame into a downscaled, correctly oriented bitmap/tensor.
2. Add an Android-compatible inference module (ONNX Runtime Android is already a project dependency) only after choosing a specific model whose license permits redistribution and whose input/output tensors are documented.
3. Implement and test preprocessing and postprocessing against saved screenshots. Validate class labels, coordinate scaling, confidence thresholds, and frame latency on the target phone.
4. Add a deterministic state machine (loading, lobby, match, result, error) and fail-safe pause when state confidence is low.
5. Add a separate action layer using Android Accessibility gestures or an explicitly permitted input method. Require user consent, check coordinates, and provide an immediate Stop control.
6. Train or obtain a compatible action policy. Vision detections alone do not teach the bot how to play. The candidate RL policy currently has Python-side dependencies and is not a plug-and-play Android model.
7. Bundle model files only after verifying provenance, SHA-256, size, license, and a model smoke test. Add automated APK checks that confirm the model assets are present and loadable.
8. Test on a physical device across supported Android versions. CI cannot verify real-game perception, controls, account safety, or gameplay quality.

## Honest current status

- UI / overlay / capture permission wiring: implemented; latest GitHub Actions runs passed on 2026-10-12.
- On-device model inference: not implemented.
- Verified downloadable game AI model: not yet selected or packaged.
- Autonomous gameplay and brawler/trophy queue execution: not implemented.
- Full offline APK operation: not yet achieved.

Do not label the APK as a working autonomous AI until inference, action control, model packaging, and physical-device tests pass.
