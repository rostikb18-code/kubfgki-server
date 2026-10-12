# RostikAI on-device model bundle

This directory is intentionally documented but does **not** currently contain verified trained ONNX weights. Do not rename arbitrary files to the names below: model role names are contracts, not proof of compatibility.

## Required model roles

| Asset | Responsibility | Required validation before use |
|---|---|---|
| `scene_detector.onnx` | Player, enemy, crate and pickup detections | Label set, box format, score threshold, NMS, held-out match precision/recall |
| `hud_reader.onnx` | Health, ammo and super/HUD state | ROI and scale contract, per-field accuracy, temporal consistency |
| `terrain_segmenter.onnx` | Walkable ground, walls and bushes | Per-class IoU across different map themes and effects |
| `gas_segmenter.onnx` | Poison cloud / safe-zone perception | Precision/recall on labelled gas and bush frames; false-positive rate |
| `tactical_policy.onnx` | Action scores from a typed state vector | Exact feature order/normalization, action mapping, offline rollout and safety-gate tests |

## Required model card for each file

Before an asset can be marked validated, add a model card recording:
- upstream source, license, training-data provenance and model version;
- SHA-256 of the exact ONNX file;
- input names, tensor types, dimensions and preprocessing;
- output names, shapes, label map and postprocessing;
- export command and source framework/version;
- evaluation dataset split and metrics (including per-map results);
- supported Android/ONNX Runtime versions and measured latency.

## Pipeline safety

1. A successfully opened ONNX graph is only a syntactic/runtime check. It is not evidence of accuracy.
2. A role with missing weights or an unvalidated contract must not produce gameplay actions.
3. Perception must be evaluated on labelled, held-out frames before any action execution.
4. Run in observe-only mode first and save frame/state/prediction traces for review.
5. Enable gesture execution only after the detection and policy gates pass. Keep a manual stop control.

## Research note

The public `jjeong-11/brawlstarsRL` project is useful architectural reference for screen perception, terrain/path planning, rewards and PPO training. Its own `models/README.md` says per-brawler weights are not trained yet; its Stable-Baselines3 `.zip` checkpoints are not interchangeable with Android ONNX models. Any reuse must preserve the upstream MIT license/attribution and independently validate model semantics.
