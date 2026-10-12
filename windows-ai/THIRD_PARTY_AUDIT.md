# Third-party integration and license audit

Audit date: 2026-10-12. This is a source-selection record, not legal advice.

## Selected references

| Project | License / evidence | Decision |
|---|---|---|
| [PylaAI/PylaAI](https://github.com/PylaAI/PylaAI) | Repository LICENSE is CC BY-NC 4.0; README says it cannot be sold or monetized. | Use as an architectural reference for ONNX perception, match-state handling and emulator adapters. Do not copy files into this repo without preserving attribution and confirming the intended non-commercial use. |
| [Jooi025/BrawlStarsBot](https://github.com/Jooi025/BrawlStarsBot) | Repository LICENSE is MIT (copyright Joseph, 2023). | Potential code candidate for later isolated porting with copyright and license notice retained. Its README explicitly identifies weak detection and map/brawler limitations. Its model weights and other assets still require separate provenance/license checks. |
| [rarebinary/IrisAI](https://github.com/rarebinary/IrisAI) | README identifies CC BY-NC 4.0 and macOS-only implementation. | Use only for architectural ideas; no Swift/macOS source imported into this Windows/Android prototype. |
| [jjeong-11/brawlstarsRL](https://github.com/jjeong-11/brawlstarsRL) | License and weights/data provenance must be checked at the exact revision before reuse. | Research candidate only; no weights or code imported. |

## What is actually integrated in this commit

- Original, platform-independent decision contract with conservative fail-closed behavior.
- Read-only ADB screen capture adapter, isolated from any input actions.
- Unit tests for stale frames, low confidence, invalid values, screen state, range-based decisions, low health, controller pause, and ADB errors.
- Windows CI workflow to run compile and unit tests under Python 3.11.

No third-party source files or ONNX weights were copied. This avoids silently mixing incompatible code or redistributing assets without confirmed rights.

## Compatibility and limitations

- PylaAI's Windows source targets Python 3.11.x and an Android emulator; this is the closest architecture match for the first Windows prototype.
- ADB screenshot capture is generic, but detection models need their exact preprocessing, input/output tensor shapes, output decoder, labels, thresholds, and compatible runtime.
- The MIT license on BrawlStarsBot applies to its code under that license, not automatically to bundled model weights, screenshots, brawler artwork, or other third-party assets.
- The current decision core is a testable scaffold, not a trained AI policy. It does not claim to recognize enemies from pixels or win matches.
- Android native integration remains separate until Windows replay tests and model provenance/accuracy gates pass.
