# RostikAI Windows Emulator Prototype

This folder is a separate, test-first Windows prototype for Android-emulator capture and decision logic. It does not alter the existing Android app.

## License gate

Do not vendor third-party source code or model weights until each repository's LICENSE and the individual model/asset provenance are checked. PylaAI and IrisAI identify CC BY-NC 4.0 terms; non-commercial only. Jooi025/BrawlStarsBot has a LICENSE file; inspect its exact terms before copying any code or weights. This prototype initially contains original glue code only.

## Status

Prototype scaffold only. It is not yet a capable player and does not send game inputs. The first milestone is deterministic replay/state tests; emulator capture and ADB input are separate opt-in adapters.

## Setup

Use Python 3.11 on Windows. From this directory:

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python -m unittest discover -s tests -v
```

No model files are bundled. A model must have documented provenance, a license, a tested ONNX input/output contract, and real-frame evaluation before it can be enabled.