# RostikAI Native Android — clean project

The native Android project is in `android-native/`.

## Build a standalone APK

1. Open the repository **Actions** tab.
2. Select **RostikAI Android APK**.
3. Run the workflow on the default branch.
4. Download the `RostikAI-native-debug-apk` artifact after a successful run and extract `app-debug.apk`.

The build uses JDK 17, Android SDK 35/build-tools 35.0.0, and Gradle 8.10.2. It downloads and converts one pinned YOLOv8 model during CI, checks its license and source hash, runs ONNX graph and CPU inference smoke tests, builds the APK, and verifies that the APK contains the model, original checkpoint, license text, and ONNX Runtime Android native library. The phone does not need Python, PyTorch, or a separate model download.

## What is implemented

- Native Android UI, brawler queue and trophy inputs.
- Floating AI button and width/height controls.
- MediaProjection screen capture.
- ONNX Runtime Android dependency.
- One YOLOv8 detector for Player, Bush, Enemy, and Cubebox, generated from a pinned upstream checkpoint at build time.
- Android-side preprocessing, YOLO output decoding, per-class non-maximum suppression, and observe-only detection logs.

## Honest limits

The detector is a perception component, not a trained action policy. It is wired to captured frames but **does not automatically move or attack**. The build's zero-input inference test proves the model can run, not that it correctly recognizes every object in a live match. Detection quality on current phone resolutions/game visuals and full autonomous match play still require real-device validation.

The model metadata declares AGPL-3.0 for the trained weights. The build includes its source checkpoint and license notice. Review license obligations before redistributing a modified or closed-source app. See [ON_DEVICE_AI_INTEGRATION.md](ON_DEVICE_AI_INTEGRATION.md).
