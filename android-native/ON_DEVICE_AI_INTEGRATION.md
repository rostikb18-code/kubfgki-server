# RostikAI on-device AI integration status

Updated: 2026-10-12

## Standalone APK goal

The build is intended to package everything needed for local inference into the APK: ONNX Runtime Android, one trained detector exported to ONNX, the original checkpoint, model metadata, and license notices. Python/PyTorch are build-time tools only and are not required on the phone.

## Detector integrated in the build

The Android workflow fetches the pinned source checkpoint from [Jooi025/BrawlStarsBot](https://github.com/Jooi025/BrawlStarsBot) at commit `a5a797b28f1aa662db8364515bfd8342e66557a6`. It verifies the Git blob hash, verifies the upstream metadata and class labels, exports a fixed-shape ONNX model, checks the ONNX graph, and runs a CPU zero-input inference test before packaging. The APK verification checks that the ONNX file, source checkpoint, AGPL model license, and ONNX Runtime native library are present.

The model metadata marks the trained weights AGPL-3.0. The APK build retains the upstream MIT code license notice and includes the AGPL license text and source checkpoint. This is a meaningful redistribution/license choice; review the terms before redistributing a modified or closed-source APK.

## Android runtime path

- `OnDeviceBrawlerDetector.kt` loads the bundled ONNX file, validates the input tensor, applies RGB normalization and letterbox resizing, decodes YOLO boxes, and performs per-class NMS.
- `ScreenCaptureService.kt` passes sampled MediaProjection frames to the detector on a background executor and logs detections for diagnostics.
- The current runtime is **observe-only**: detections are logged, not used to trigger touch gestures.

## What is still not done

- No tested action policy for deciding when/how to move, attack, use a super, or handle results.
- No OCR/state classifier for lobby/loading/match/defeat screens.
- No verified end-to-end match play or physical-phone evaluation of detector accuracy.
- No five-model stack. One detector is bundled by the Android CI build; other models should not be added just to reach a count.
- The upstream model was trained for a desktop capture domain. Its accuracy on phone screenshots and current game visuals must be evaluated from real captures.

Do not call this an autonomous player yet. The current milestone is a packaged on-device detector with inference wired to screen capture, with game actions deliberately disabled until detections are validated.
