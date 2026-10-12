# RostikAI Native Android — clean build path

This is a **new standalone Android project**. It does not extract or depend on the five legacy ZIP parts.

## Build APK on GitHub

1. Open the repository's **Actions** tab.
2. Select **RostikAI Native APK (no ZIP parts)**.
3. Press **Run workflow** and wait for the job to finish.
4. Open the successful run, download artifact **RostikAI-native-debug-apk**, extract it, and install `app-debug.apk` on the phone.

Requirements for a local build: JDK 17, Android SDK 35/build-tools 35.0.0, Gradle 8.10.2.

## Honest implementation status

The project currently provides a native Android shell with brawler search/selection and editable trophy fields. It is a buildable foundation, **not yet an autonomous gameplay bot**. The listed open-source projects use different host/emulator pipelines and model formats; their gameplay code and model files have not been ported into this project. START deliberately reports this limitation instead of pretending a match is being controlled.

The displayed brawler list is a starter subset and uses text labels, not the complete 108 icon assets. Queue persistence, live screen perception, ONNX model integration, action execution, MediaProjection consent flow, and match/trophy result verification remain implementation work.
