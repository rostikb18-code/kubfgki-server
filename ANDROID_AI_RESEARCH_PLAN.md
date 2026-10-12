# RostikAI — Android AI research and port plan

Status: research/design only. This document does not claim that any upstream gameplay code has been ported or that the Android app can yet play a match autonomously.

## Repositories reviewed

- [Pyla-RL](https://github.com/CodeBanana69/Pyla-RL): Windows + Android emulator workflow (scrcpy/ADB), ONNX vision, predicted aiming, dodge strafing, analog movement, queue/trophy tracking and recovery. Useful as a behavior and UX reference; its host-side input pipeline is not directly usable from an on-device Android app. The repository states CC BY-NC 4.0: retain attribution and do not use commercially unless separately permitted.
- [BrawlStarsBot](https://github.com/Jooi025/BrawlStarsBot): Python/OpenCV/YOLO running against BlueStacks, finds bushes/enemies, attacks in range, uses gadget and requeues after defeat. Useful for simple state-driven combat and match-end recognition. It also explicitly warns trophies can be lost. Review its current LICENSE before copying any source.
- [BrawlStars-ComputerVision](https://github.com/eforce67/BrawlStars-ComputerVision): YOLOv8 detections feed a neuroevolution/continuous-time recurrent controller. Example observations include player/enemy positions, health bars, walls, ammo, gadget/super/hypercharge and match status; actions include movement and combat abilities. Apache-2.0; preserve notices and license when reusing code.
- [brawlstarsRL](https://github.com/jjeong-11/brawlstarsRL): screen-only perception, terrain/map representation, path planning and reinforcement learning. Useful reference for separating perception, navigation and policy; verify its exact license and model provenance before reuse.
- [IrisAI](https://github.com/rarebinary/IrisAI): additional comparison for local-first vision automation, queues, match history and recovery; macOS/host-driven, not an Android drop-in. Its project states CC BY-NC 4.0.

## Architecture to implement in RostikAI

Keep the Android app as the host and use Android-native capture/input:
1. MediaProjection capture → downscale/normalize frames on a bounded worker; never retain stale frames as current.
2. Perception interface returns timestamp/frame sequence plus confidence-bounded detections: player, enemies, health bars, walls/obstacles, bushes, ammo, ability readiness, fog, and match state.
3. State estimator smooths detections over multiple frames; explicit states: UNKNOWN, LOADING, IN_MATCH, RESPAWNING, VICTORY, DEFEAT, RESULTS. Unknown must not trigger menu taps or combat actions.
4. Policy produces a typed action proposal: move vector, aim vector, attack, super, gadget, wait, or no-op. Start with conservative rule-based policy; model inference is an interchangeable implementation, not assumed to exist.
5. One serialized input executor maps actions to permitted Android accessibility gestures/overlay controls. Every action has a timeout; cancellation/Stop releases active gestures and clears queued work.
6. Supervisor gates actions on fresh observations, confidence thresholds, foreground/capture health and user-selected mode. If the screen freezes, state is unknown, or capture permission is lost, stop acting and show a visible error.
7. Match result reader updates trophy statistics only when a result and value are actually detected with sufficient confidence. Never fabricate a trophy count.
8. UI remains separate from the policy: brawler search must support multi-character input, trophy fields use normal keyboard editing, queue is scrollable, width and height are independently adjustable, and Start/Pause/Stop reflect actual runtime state.

## Integration phases and acceptance checks

### Phase A — Buildable Android foundation
- Build the existing project reproducibly on CI.
- Verify manifest, Accessibility service, MediaProjection flow, packaged model/assets, and ONNX Runtime ABI.
- Verify Start/Pause/Stop lifecycle and no delayed start after Stop.
- Do not describe a successful compile as proof of autonomous gameplay.

### Phase B — Instrumented perception
- Add frame sequence/time and structured detections.
- Create replay tests from consented sample screenshots/video; measure false positives and missed detections.
- Unknown/low-confidence state must produce no gameplay input.

### Phase C — Safe control loop
- Serialize all gestures, clamp joystick vectors, enforce action duration/rate limits, cancel on Stop/service teardown.
- Unit-test state transitions, stale-frame rejection, respawn gating, wall/line-of-sight checks and result-screen detection.

### Phase D — Match trials
- Validate manually on a test account/device in a low-risk mode.
- Record detection confidence, frame rate, actions and result recognition.
- Only claim functionality demonstrated by actual device trials; automated play can lose trophies and may violate game rules.

## Android port constraints

- Desktop bots commonly rely on ADB, emulator window capture, Windows input APIs or BlueStacks controls. Those must be replaced, not merely copied, for an on-device app.
- Android screen capture requires user-granted MediaProjection consent; accessibility input requires the user to enable the service. Neither gives reliable semantic game state by itself.
- Do not package or claim five trained ONNX models unless their actual files, licenses, input shapes, output labels and runtime compatibility have been verified.
- Do not copy upstream code/assets until each repository's license and any model/dataset terms have been checked. Pyla-RL/IrisAI's CC BY-NC terms prohibit commercial use without separate permission.
- This is an unofficial project. Avoid promises of trophy gains; the referenced projects warn automation can lose trophies.