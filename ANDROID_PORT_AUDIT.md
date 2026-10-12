# RostikAI Android Port — verified source audit

This branch is an isolated engineering branch. It does not replace `main` and does not claim that an Android gameplay port is complete.

## Verified repository facts

- RostikAI's current workflow assembles the Android project from five ZIP parts and then overwrites the assembled `OverlayService.kt` with `overrides/OverlayService.kt`.
- The current canonical overlay is still a `WindowManager` floating panel. It includes brawler selection, queue, trophy editor, size controls, and Start/Pause/Stop. It is not the requested in-app-only configuration screen.
- The project source files other than the overlay are largely inside the ZIP parts, so a safe UI replacement must be tested against the assembled project, not guessed from the overlay file alone.
- xlamBOT has a public source repository, `Olegu621/xlamBOT-source`, referenced by its `source-provenance.json`. This is a better source for an engineering port than copying its Windows installer or opaque runtime parts.
- The xlamBOT source README specifies Windows and Python 3.13, with dependencies/assets copied from the installed runtime. Its device-control code uses ADB; ability detection uses OpenCV/NumPy. This is not a drop-in Android library.
- The public xlamBOT license is CC BY-NC 4.0. Any redistributed adapted code must preserve attribution and comply with the non-commercial restriction; do not represent the result as wholly original or commercially unrestricted.

## Porting strategy

### Stage A — stable Android configuration UI

Build a native Activity as the primary configuration surface:
- searchable brawler catalog with multi-character typing and scroll position preservation;
- per-brawler current trophies and target trophies;
- queue reorder/remove controls and total remaining trophies;
- persistent settings stored locally and validated on load;
- visible permission/readiness state and explicit recovery actions;
- Start/Pause/Resume/Stop state machine with duplicate-start protection.

No floating overlay is required for normal configuration. The existing MediaProjection and Accessibility permission flows should be retained and invoked through the app's normal Activity/service lifecycle, not replaced with dummy success flags.

### Stage B — extract engine boundaries from xlamBOT

Port and test small platform-neutral components first:
- immutable, timestamped frame/observation objects;
- screen-state classification and stale-frame rejection;
- brawler/target selection policy;
- safe action scheduler with cancellation and release-on-stop;
- trophy/result bookkeeping with unknown values preserved.

Do not copy Windows-only ADB transport, LDPlayer/emulator management, Windows installer/update code, Python packaging, or desktop web-panel code into the APK.

### Stage C — Android-specific perception and input

- Capture frames with MediaProjection only after the user grants consent for that capture session.
- Feed frames to an Android-compatible vision implementation; benchmark ONNX/OpenCV support and memory before selecting runtime.
- Send gestures only through a properly enabled AccessibilityService, with one serialized controller and cancellation checks.
- On stale frames, capture loss, unknown screen, permission loss, pause, or stop: release active gestures and do not send new gameplay actions.
- Confirm the app launches, survives screen rotation/backgrounding, and handles permission denial/revocation on real devices.

## Required CI gates before calling this usable

1. Assemble the five project parts and apply source overrides.
2. Compile debug and release APKs.
3. Verify manifest permissions, service declarations, packaged assets/models, and native ABI files.
4. Unit-test queue persistence, trophy validation, search filtering, and Start/Pause/Stop state transitions.
5. Run device/emulator smoke tests for permission grant/deny/regrant, keyboard multi-character entry, process recreation, and repeated Start/Stop.
6. Report separately what passed in CI and what has actually been verified in a running game. A successful APK build is not proof of working gameplay.

## Current status

Audit only. No Android UI has been replaced in this commit, no xlamBOT gameplay code has been ported, and no new APK has been built from this branch yet. These are explicit remaining tasks, not implied successes.
