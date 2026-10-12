#!/usr/bin/env python3
"""Fetch and export the pinned, documented BrawlStarsBot YOLOv8 detector for Android.

The upstream OpenVINO metadata explicitly marks the trained model AGPL-3.0.
This script preserves the source checkpoint, attribution and license in the APK,
exports a fixed-shape ONNX copy, and refuses to accept an unexpected source blob
or incompatible tensor contract. It does not prove detection accuracy.
"""
from __future__ import annotations

import hashlib
import json
import os
import pathlib
import subprocess
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSET_DIR = ROOT / "app" / "src" / "main" / "assets" / "models" / "brawler_detector"
SOURCE_COMMIT = "a5a797b28f1aa662db8364515bfd8342e66557a6"
SOURCE_REPO = "https://github.com/Jooi025/BrawlStarsBot"
WEIGHTS_URL = (
    "https://raw.githubusercontent.com/Jooi025/BrawlStarsBot/"
    + SOURCE_COMMIT + "/yolov8_model/yolov8.pt"
)
METADATA_URL = (
    "https://raw.githubusercontent.com/Jooi025/BrawlStarsBot/"
    + SOURCE_COMMIT + "/yolov8_model/yolov8_openvino_model/metadata.yaml"
)
LICENSE_URL = (
    "https://raw.githubusercontent.com/Jooi025/BrawlStarsBot/"
    + SOURCE_COMMIT + "/LICENSE"
)
EXPECTED_GIT_BLOB_SHA = "63260ffc402386ecc9e23affa1f05e6e27ca3705"
EXPECTED_CLASSES = ["Player", "Bush", "Enemy", "Cubebox"]
IMGSZ = (384, 640)


def fetch(url: str, destination: pathlib.Path) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "RostikAI-Model-Build/1.0"})
    with urllib.request.urlopen(request, timeout=90) as response:
        data = response.read()
    if not data:
        raise RuntimeError(f"Empty download: {url}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(data)
    return data


def git_blob_sha(data: bytes) -> str:
    header = f"blob {len(data)}\\0".encode("ascii")
    return hashlib.sha1(header + data).hexdigest()


def main() -> int:
    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    weights_path = ASSET_DIR / "yolov8_brawlstars_source.pt"
    onnx_path = ASSET_DIR / "brawler_detector_yolov8.onnx"
    metadata_path = ASSET_DIR / "metadata.yaml"
    license_path = ASSET_DIR / "LICENSE-MIT-UPSTREAM-CODE.txt"
    agpl_path = ASSET_DIR / "LICENSE-AGPL-3.0-MODEL.txt"

    weights = fetch(WEIGHTS_URL, weights_path)
    actual_blob_sha = git_blob_sha(weights)
    if actual_blob_sha != EXPECTED_GIT_BLOB_SHA:
        raise RuntimeError(
            f"Upstream checkpoint changed: expected git blob {EXPECTED_GIT_BLOB_SHA}, "
            f"received {actual_blob_sha}"
        )

    metadata = fetch(METADATA_URL, metadata_path).decode("utf-8", errors="replace")
    if "license: AGPL-3.0" not in metadata or "task: detect" not in metadata:
        raise RuntimeError("The upstream model metadata does not match the audited license/task.")
    if not all(f"{i}: {name}" in metadata for i, name in enumerate(EXPECTED_CLASSES)):
        raise RuntimeError("Unexpected detector class labels; refusing to package an incompatible decoder.")

    upstream_license = fetch(LICENSE_URL, license_path).decode("utf-8", errors="replace")
    if "MIT License" not in upstream_license:
        raise RuntimeError("Upstream repository code license changed or is missing.")
    agpl_license = fetch(
        "https://www.gnu.org/licenses/agpl-3.0.txt", agpl_path
    ).decode("utf-8", errors="replace")
    if "GNU AFFERO GENERAL PUBLIC LICENSE" not in agpl_license:
        raise RuntimeError("Could not verify/download the AGPL-3.0 license text.")

    try:
        from ultralytics import YOLO
        import onnx
    except ImportError as exc:
        raise RuntimeError(
            "Missing export dependencies. Install the pinned ultralytics/onnx requirements first."
        ) from exc

    model = YOLO(str(weights_path), task="detect")
    names = model.names
    names_list = [names[i] for i in range(len(names))] if isinstance(names, dict) else list(names)
    if names_list != EXPECTED_CLASSES:
        raise RuntimeError(f"Checkpoint class names mismatch: {names_list!r}")

    exported = model.export(
        format="onnx",
        imgsz=list(IMGSZ),
        batch=1,
        dynamic=False,
        simplify=False,
        opset=12,
        nms=False,
        device="cpu",
    )
    exported_path = pathlib.Path(str(exported))
    if exported_path.resolve() != onnx_path.resolve():
        onnx_path.write_bytes(exported_path.read_bytes())

    graph = onnx.load(str(onnx_path))
    onnx.checker.check_model(graph)
    inputs = graph.graph.input
    outputs = graph.graph.output
    if len(inputs) != 1 or len(outputs) != 1:
        raise RuntimeError(f"Expected one input/output, got {len(inputs)}/{len(outputs)}")
    dims = [d.dim_value for d in inputs[0].type.tensor_type.shape.dim]
    expected_dims = [1, 3, IMGSZ[0], IMGSZ[1]]
    if dims != expected_dims:
        raise RuntimeError(f"Unexpected ONNX input shape {dims}; expected {expected_dims}")

    import onnxruntime as ort
    import numpy as np
    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    output = session.run(None, {session.get_inputs()[0].name:
                                np.zeros(expected_dims, dtype=np.float32)})
    if len(output) != 1 or output[0].ndim != 3:
        raise RuntimeError(f"Unexpected YOLO output tensor shape: {[x.shape for x in output]}")
    if 4 + len(EXPECTED_CLASSES) not in output[0].shape:
        raise RuntimeError(f"YOLO output does not include four box values and four class scores: {output[0].shape}")

    manifest = {
        "schema_version": 1,
        "id": "brawler_detector",
        "status": "bundled_candidate_perception_only",
        "source_repository": SOURCE_REPO,
        "source_commit": SOURCE_COMMIT,
        "source_model_git_blob_sha1": actual_blob_sha,
        "source_model_sha256": hashlib.sha256(weights).hexdigest(),
        "onnx_file": onnx_path.name,
        "onnx_sha256": hashlib.sha256(onnx_path.read_bytes()).hexdigest(),
        "model_license": "AGPL-3.0 (as declared by upstream OpenVINO metadata)",
        "upstream_code_license": "MIT",
        "input": {"name": session.get_inputs()[0].name, "type": session.get_inputs()[0].type,
                  "shape": session.get_inputs()[0].shape, "layout": "NCHW", "color": "RGB",
                  "normalization": "uint8 / 255.0", "imgsz": list(IMGSZ)},
        "output": [{"name": m.name, "type": m.type, "shape": m.shape} for m in session.get_outputs()],
        "classes": EXPECTED_CLASSES,
        "thresholds": [0.37, 0.47, 0.57, 0.65],
        "cpu_zero_input_smoke_test": "PASS",
        "limitations": [
            "Perception model only; not a tactical/action policy.",
            "Accuracy on current game version, skins, maps and device resolutions is not validated.",
            "Do not send automated gestures until screenshot-based detections are manually evaluated."
        ]
    }
    (ASSET_DIR / "MODEL_MANIFEST.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\\n", encoding="utf-8"
    )
    print(json.dumps({
        "onnx": str(onnx_path),
        "bytes": onnx_path.stat().st_size,
        "sha256": manifest["onnx_sha256"],
        "input": manifest["input"],
        "output": manifest["output"],
        "classes": EXPECTED_CLASSES,
        "cpu_zero_input_smoke_test": "PASS"
    }, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"MODEL EXPORT FAILED: {exc}", file=sys.stderr)
        raise SystemExit(1)
