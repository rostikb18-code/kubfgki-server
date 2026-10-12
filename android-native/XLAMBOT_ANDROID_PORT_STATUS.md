# xlamBOT → RostikAI Android port status

Last reviewed: 2026-10-12

## Upstream reviewed

- Public app/release repository: https://github.com/Olegu621/xlamBOT
- Source repository: https://github.com/Olegu621/xlamBOT-source
- Upstream source snapshot recorded by the public project: revision 143, commit `0bc27cb7730d9e67ccba4f56be20931ee2a75973` (see upstream `source-provenance.json`).
- Upstream license: Creative Commons Attribution-NonCommercial 4.0 International (CC BY-NC 4.0). Adapted code must retain attribution and comply with the non-commercial restriction. Check individual assets/model licenses separately; this license notice is not proof that every bundled third-party model or image is redistributable.

## Port mapping

| xlamBOT capability | Android target | Status in RostikAI |
|---|---|---|
| Frame-based battle perception | MediaProjection frame pipeline + Android-compatible vision | NOT PORTED; capture currently samples HUD colors only |
| Positive battle/respawn classification | Timestamped screen-state classifier | NOT PORTED |
| Projectile tracking and dodge planning | Kotlin/Android vision module; reject uncertain/stale tracks | NOT PORTED |
| Ability-button recognition | Android vision module with confirmed multi-frame observations | NOT PORTED |
| Action scheduler and cancellation | One serialized Accessibility gesture controller | Gesture capability exists; no vision-driven game policy is connected |
| Match/result/trophy bookkeeping | Existing queue/trophy UI and a verified result reader | UI exists; automatic result reading is not verified |
| ONNX perception models | Real model files + license/provenance + input preprocessing + output decoder | NOT READY; model audit found zero ONNX assets |

## Why screenshots/videos do not automatically train the bot

A screenshot or video is raw observation data, not a trained model. Supervised training also needs accurate labels (player, allies, enemies, projectiles, walls, UI/game state, and desired action), a train/validation split, a training run, evaluation on unseen matches, and exported weights. Existing brawler portraits are not a substitute for this dataset. No verified labeled gameplay dataset was found in the current Android source tree during this audit.

## Required gates before enabling autonomous input

1. Obtain permission-cleared training/evaluation frames and labels, or use a real pretrained model with a confirmed redistribution license.
2. Record every model's SHA-256, license/source, input tensor shape/type, output tensor shape/type, preprocessing, labels, and decoder.
3. Test model loading and real inference on Android; an ONNX session opening or a zero-input run does not establish useful detections.
4. Verify screen-state detection and player localization on unseen gameplay videos at the target device resolution.
5. Connect decisions to the Accessibility service through a single cancellable scheduler. Unknown screen, stale frame, lost capture, pause, stop, or uncertain player location must result in no new gesture.
6. Test Start/Pause/Resume/Stop and permission denial/regrant on a real device, then test actual matches and result/trophy reading.
7. Keep the APK build result and gameplay-readiness result separate. A successful APK build is not evidence of autonomous play.

## Current honest result

The native Android project builds a debug APK, but it is not yet an autonomous Brawl Stars player. The existing capture service explicitly runs in observe-only mode, the ONNX asset audit found zero models, and no verified xlamBOT perception/action loop has been ported into the Android app. Do not enable automatic gameplay until the gates above pass.
