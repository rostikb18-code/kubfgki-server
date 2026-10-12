# RostikAI bundled detector and license notices

The Android APK build downloads the following pinned checkpoint and converts it to ONNX for on-device inference:

- Project: [Jooi025/BrawlStarsBot](https://github.com/Jooi025/BrawlStarsBot)
- Source revision: `a5a797b28f1aa662db8364515bfd8342e66557a6`
- Checkpoint path: `yolov8_model/yolov8.pt`
- Expected Git blob SHA-1: `63260ffc402386ecc9e23affa1f05e6e27ca3705`
- Upstream OpenVINO metadata declares the trained model as **AGPL-3.0** and lists classes `Player`, `Bush`, `Enemy`, `Cubebox`.
- The repository code's LICENSE is MIT, copyright Joseph (2023). The model license is treated separately and is not assumed to be MIT.

The APK build retains the upstream checkpoint, the upstream MIT license notice, the AGPL-3.0 license text, metadata, and a generated manifest with actual SHA-256 and tensor metadata. It refuses to package a checkpoint whose Git blob hash, declared model license, class names, input tensor, ONNX graph, or CPU zero-input inference smoke test does not match expectations.

**License note:** AGPL-3.0 has source and redistribution conditions. This document is not legal advice. Review the license and ensure the complete app distribution complies before redistributing the APK, especially if you intend to distribute a modified or closed-source version.

## What this model does not provide

The model detects four visual classes. It is not an action-policy model and does not decide a full strategy. The current Android integration logs detections from captured frames; it does not automatically tap, move, or attack. The smoke test verifies that ONNX Runtime can execute the graph, not that the detector is accurate on a live match.
