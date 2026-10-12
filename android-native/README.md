# RostikAI Native Android — clean project

The old five-part ZIP assembly has been removed from the active build workflow. The Android project is under `android-native/`.

## Build an APK on GitHub

1. Open the repository **Actions** tab.
2. Select **RostikAI Android APK** (the workflow in `.github/workflows/build.yml`).
3. Use **Run workflow** on the default branch, or wait for the push/pull-request build.
4. After a successful run, download the `RostikAI-native-debug-apk` artifact and extract `app-debug.apk`.

Build configuration: JDK 17, Android SDK 35/build-tools 35.0.0, Gradle 8.10.2. The workflow checks required project files, runs Gradle compilation, verifies that the APK is a non-empty valid ZIP, calculates SHA-256, and uploads the APK. It uploads logs if the build fails.

## Honest implementation status

This is currently a **native Android UI foundation**, not a finished autonomous gameplay bot. The current screen has brawler text search/selection for a starter subset, trophy inputs, a queue saved on-device, separate width/height sliders, and a draggable floating AI bubble that requests overlay permission and can be started/paused/stopped. It does **not** yet include the full 108 icon assets, MediaProjection capture flow, Accessibility-based game control, ONNX Runtime, five verified trained models, live match perception, or autonomous match control. START intentionally reports that the gameplay controller is not connected.

No APK should be considered built until GitHub Actions reports a successful run and provides the artifact. An Actions build verifies compilation and packaging, not gameplay on a physical phone; device installation and gameplay still require separate testing.
