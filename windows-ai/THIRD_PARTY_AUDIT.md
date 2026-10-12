# Third-party integration and license audit

Audit date: 2026-10-12. This is a source-selection record, not legal advice.

## Selected projects

| Project | License / evidence | Decision |
|---|---|---|
| [Jooi025/BrawlStarsBot](https://github.com/Jooi025/BrawlStarsBot) | Repository LICENSE states MIT, copyright Joseph, 2023. The pinned source revision is `a5a797b28f1aa662db8364515bfd8342e66557a6`. | Added an isolated Windows bootstrap/launcher. It fetches that exact revision at runtime and preserves the upstream repository and license notice. It is not merged into the Android APK. |
| [PylaAI/PylaAI](https://github.com/PylaAI/PylaAI) | Repository LICENSE is CC BY-NC 4.0; README says it cannot be sold or monetized. | Architectural reference only; no source copied. |
| [rarebinary/IrisAI](https://github.com/rarebinary/IrisAI) | README identifies CC BY-NC 4.0 and macOS-only implementation. | Architectural reference only; no source copied. |
| [jjeong-11/brawlstarsRL](https://github.com/jjeong-11/brawlstarsRL) | Exact license and weights/data provenance not established here. | No source or weights imported. |

## What is actually integrated

- Original platform-independent decision contract with conservative fail-closed behavior.
- Read-only ADB screenshot adapter and its tests.
- Windows CI workflow for compile and unit tests.
- `run_upstream_bot.py`: a launcher that checks out the MIT-licensed BrawlStarsBot at a pinned revision into an isolated vendor directory, checks the license notice and required OpenVINO model files, and runs the upstream controller on request.

The third-party bot source and weights are downloaded at runtime rather than copied into this repository. The bootstrap verifies that files exist; it does not certify model accuracy, a distinct model license, or successful gameplay on the user's setup.

## Compatibility limitations

- BrawlStarsBot's current architecture uses Windows Win32 window capture and desktop mouse/keyboard controls. It targets a visible BlueStacks window; it is not a generic ADB controller and cannot be embedded directly into the Android APK.
- Its README explicitly lists weaknesses in enemy detection, box collection, storm direction, defeat-screen detection, and performance.
- The native Android project still has no verified ONNX model files under `android-native/app/src/main/assets/models/`; this integration does not change that.
- RostikAI's own decision core remains a testable scaffold, not a trained policy. A successful Python unit-test run is not evidence of winning matches.
