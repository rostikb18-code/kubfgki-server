# RostikAI Native Android — clean project

The old five-part ZIP assembly has been removed from the active build workflow. The clean Android project lives under `android-native/`.

## Build an APK on GitHub

1. Open the repository **Actions** tab.
2. Select **RostikAI Android APK** (the workflow in `.github/workflows/build.yml`).
3. Use **Run workflow** on the default branch, or wait for the push/pull-request build.
4. After a successful run, download the `RostikAI-native-debug-apk` artifact and extract `app-debug.apk`.

Build configuration: JDK 17, Android SDK 35/build-tools 35.0.0, Gradle 8.10.2. CI checks the Android project structure, compiles the APK, checks ZIP integrity and package ID, calculates SHA-256, and uploads the APK. It also reports whether model/icon assets are actually present; missing AI assets are not silently represented as complete.

## Honest implementation status

Implemented UI foundation:
- Brawler text search/selection for a starter subset (not all 108).
- Current/target trophy fields and persistent queue.
- Independent width/height sliders for the floating AI button.
- Draggable floating AI bubble with overlay permission and Start/Pause/Stop controls.
- ONNX Runtime Android dependency is included, so the inference engine can be used by a future model runner.

Still missing and not to be claimed as working:
- Complete set of 108 brawler portraits/icons.
- Five genuine, provenance-documented, compatible trained ONNX models.
- A model runner wired to validated model input/output tensors.
- MediaProjection screen capture and match-state pipeline.
- Verified game-state detector, safe action policy, and Accessibility/gesture control.
- End-to-end autonomous match play and real-device validation.

An ONNX Runtime dependency is **not** itself an AI model. Empty, fabricated, randomly generated, or incompatible model files must not be counted as models. The current START action only launches the floating bubble and explicitly says gameplay AI is not connected.

No APK should be considered built until GitHub Actions reports success and provides the artifact. CI compilation/package checks do not prove runtime or gameplay works on a physical phone; that requires separate device testing.
