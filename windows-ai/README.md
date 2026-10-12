# RostikAI Windows Emulator Prototype

This folder is a separate Windows prototype for Android-emulator capture and platform-independent decision logic. It does not alter the existing Android app.

## License gate

No third-party source or model weights have been copied into this prototype. See [THIRD_PARTY_AUDIT.md](THIRD_PARTY_AUDIT.md). PylaAI identifies CC BY-NC 4.0; Jooi025/BrawlStarsBot's LICENSE is MIT for code covered by that license. Model weights and other assets need their own provenance/license checks.

## Setup

Use Python 3.11 on Windows. From this directory:

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python -m unittest discover -s tests -v
```

## Read-only emulator screenshot smoke test

1. Start an Android emulator and open the game or home screen.
2. Install Android SDK Platform Tools and make sure `adb.exe` is on PATH.
3. In PowerShell, run `adb devices` and confirm the emulator shows as `device`.
4. From `windows-ai`, run:

```powershell
python capture_once.py --serial emulator-5554 --output captures/latest.png
```

Replace `emulator-5554` with the serial shown by `adb devices`. If only one device is connected, omit `--serial`. Open the saved PNG to verify the capture is correct.

## Current limits

This milestone captures frames and tests safety rules, but it does not yet recognize enemies from pixels and does not send game inputs. No model files are bundled. A model must have documented provenance, a license, a tested ONNX input/output contract, and real-frame evaluation before it can be enabled. The deterministic decision core is a scaffold, not a trained or match-validated policy.
