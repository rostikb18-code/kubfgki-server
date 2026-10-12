# Install RostikAI Android APK

## What is bundled

The GitHub Actions APK build packages the Android ONNX Runtime library, one pinned YOLOv8 detector exported to ONNX, its source checkpoint and license notices, and the offline brawler catalog/portraits. Python and PyTorch are build-time tools; they are not required on the phone.

The detector is **observe-only**: it analyzes sampled screen frames and writes detections to Android logs. This build does not yet make autonomous movement/attack decisions. Do not interpret “APK build passed” as “the bot wins matches”.

## Download the APK

1. Open the [RostikAI Android build workflow](https://github.com/rostikb18-code/kubfgki-server/actions/workflows/build.yml).
2. Open the newest run whose status is successful.
3. Under **Artifacts**, download **RostikAI-native-debug-apk**.
4. Extract the downloaded ZIP once. Install `app-debug.apk`. The ZIP also contains `SHA256.txt` and the AI readiness report.

The artifact is retained for 14 days by GitHub Actions. A newer run may create a newer APK.

## First launch

1. Install the APK. If Android blocks installation, allow “Install unknown apps” for the app you used to download it, then retry.
2. Open RostikAI and press **ПРОВЕРИТЬ ONNX-МОДЕЛИ**. The app should report that the bundled detector opens and show its input/output counts. If it reports no model, use an APK from a successful build artifact and send the Android log error.
3. Press **START** and grant Android's system screen-capture prompt. Android requires this explicit permission; an APK cannot silently grant it.
4. Allow display-over-other-apps when prompted so the floating AI button can appear.
5. During capture, the detector analyzes frames in observe-only mode. You can inspect Android logs using Android Studio Logcat or `adb logcat -s RostikCapture RostikModelRegistry`.

## Safety and limitations

- The app does not need Python, PyTorch, or a separate model download on the phone.
- The user must still grant Android system permissions, including screen capture and overlay. These cannot be silently bundled/granted by an APK.
- The model was trained for a desktop capture domain. Its detection quality on current phone screenshots, skins, resolutions and maps has not been validated.
- Automatic taps/swipes are deliberately not connected to the detector until live-frame accuracy and match-state detection are tested.
- The model metadata declares AGPL-3.0. Review [MODEL_AND_LICENSE_NOTICES.md](MODEL_AND_LICENSE_NOTICES.md) before redistributing modified builds.
