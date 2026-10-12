# RostikAI — critical feature audit

Audit date: 2026-10-12

## Scope and evidence

This audit is based on the Android-native source and GitHub Actions workflow. A green CI build proves that the source compiles and the APK package passes the workflow's package checks; it does not prove correct behavior on a real phone or inside Brawl Stars.

## Feature status

| Feature | Status | Critical finding |
|---|---|---|
| Native Android APK build | CI-verified on prior commit; latest catalog change is being rebuilt | Package build is not a gameplay test. |
| Brawler roster | Changed to fetch the current BrawlAPI list; offline fallback still contains 37 names | Requires network for the full roster; actual count and names need runtime verification. |
| Brawler portraits | Asynchronous URL loading and in-memory bitmap cache added | Requires network; failures fall back to a generic icon. Needs device/performance testing. |
| Search | Text filtering is local and multi-character input is supported | Needs real-device keyboard test and verification that filtering stays responsive while portraits load. |
| Current/target trophies | Numeric keyboard inputs exist | Validation only rejects missing/negative values; target-vs-current logic and actual trophy reading are not implemented. |
| Queue | Ordered map is persisted in SharedPreferences; remove/clear controls exist | One queue entry per brawler; no reordering, no automatic progression, and no match-result reconciliation. |
| Overlay bubble | Service and size controls exist | Must test overlay permission, positioning, app backgrounding, and device-specific behavior. |
| Start / Pause / Stop | UI sends service start/stop requests | Not a complete state machine; pause stops capture and hides the bubble rather than pausing an AI loop. No AI loop exists. |
| MediaProjection | Consent flow and foreground capture service exist | Current capture service drains frames; it does not identify game state, buttons, targets, health, or super readiness. |
| Frame analysis | Not implemented as semantic vision | No frame classifier or calibrated HUD detector is connected. Average colors alone would not establish button readiness. |
| ONNX Runtime | Dependency and model-discovery class exist | No verified ONNX model files are bundled; no preprocessing, output decoder, labels, inference policy, or accuracy benchmark exists. |
| Accessibility gestures | AccessibilityService can dispatch gestures | It is not connected to decisions; enabling the service does not create a working bot. Coordinates and game states are not calibrated. |
| Detect/select a brawler in game | Not implemented | No menu recognition, brawler-card detection, or safe selection confirmation. |
| Aim, shoot, move, super | Not implemented as an autonomous policy | No validated detector/policy, target tracking, cooldown logic, or safety gate. Do not use this build as an autonomous player. |
| Device compatibility | Not verified | No recorded emulator/physical-device smoke tests for permission denial/regrant, rotation, process death, or repeated start/stop. |
| Release/signing | Debug artifact workflow only | No production signing/release pipeline is established by this workflow. |

## Immediate risks

1. The remote catalog depends on network availability and a third-party data host. A fully offline, bundled roster and image pack is not yet present in this Android module.
2. Loading many portraits can create queued network work and memory pressure; test on a real device and add bounded loading/caching if needed.
3. The capture listener is not a game-understanding system. Fixed screen regions vary with aspect ratio, UI scale, controls layout, and game updates.
4. A trained model cannot be inferred from the presence of ONNX Runtime. The repository currently lacks the verified five-model set and matching preprocessing/output specifications.
5. Accessibility gestures can tap the wrong control if the screen state is stale or coordinates are wrong. Autonomous input must remain disabled until perception and stop/cancel behavior are validated.
6. A green GitHub Actions check only verifies the workflow's defined checks; it cannot certify runtime correctness.

## Acceptance gates before claiming it works

- CI succeeds for the exact commit containing the change, and the artifact is downloadable.
- Install and launch on a physical Android device.
- Verify catalog count, portraits, multi-character search, keyboard behavior, queue save/reload, and trophy editing.
- Test overlay permission grant/deny/revoke, capture consent grant/deny, repeated Start/Pause/Stop, and recovery after process recreation.
- Confirm captured frames are actually sampled without blocking the UI or leaking ImageReader buffers.
- Add labeled gameplay screenshots and a reproducible evaluation set before claiming super/attack/movement detection.
- Add model provenance, hashes, tensor shapes, preprocessing, output interpretation, and accuracy results before enabling inference-driven actions.
- Keep gameplay actions disabled when the screen state is unknown, the frame is stale, permissions are lost, or Pause/Stop is requested.

## Conclusion

The project is a buildable Android control-panel/capture foundation with a brawler catalog integration in progress. It is NOT yet a working autonomous Brawl Stars AI. No claim of reliable brawler selection, super detection, aiming, shooting, movement, or trophy pushing is supported by current source evidence.