#!/usr/bin/env python3
"""Audit ONNX model files and run a CPU inference smoke test.

This script deliberately does not count empty files, random placeholders, or files
that fail to load as usable models. It validates file integrity, model metadata,
input/output tensor shapes and a zero-input CPU inference where tensor types and
dimensions are supported. It does NOT measure gameplay accuracy or prove that a
model recognizes Brawl Stars.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MODEL_DIR = ROOT / "app" / "src" / "main" / "assets" / "models"
SUPPORTED = {
    "tensor(float)": np.float32,
    "tensor(double)": np.float64,
    "tensor(float16)": np.float16,
    "tensor(int8)": np.int8,
    "tensor(uint8)": np.uint8,
    "tensor(int16)": np.int16,
    "tensor(uint16)": np.uint16,
    "tensor(int32)": np.int32,
    "tensor(uint32)": np.uint32,
    "tensor(int64)": np.int64,
    "tensor(uint64)": np.uint64,
    "tensor(bool)": np.bool_,
}


def shape_for(meta):
    dims = []
    for dim in meta.shape:
        if isinstance(dim, int) and dim > 0:
            dims.append(dim)
        else:
            # A batch dimension or other symbolic dimension is made concrete as 1.
            dims.append(1)
    return dims


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, default=DEFAULT_MODEL_DIR)
    parser.add_argument("--require-five", action="store_true",
                        help="fail unless exactly five valid ONNX models are present")
    args = parser.parse_args()

    paths = sorted(args.model_dir.glob("*.onnx")) if args.model_dir.is_dir() else []
    print(f"Model directory: {args.model_dir}")
    print(f"ONNX files discovered: {len(paths)}")
    if not paths:
        print("MODEL READINESS: FAIL — no ONNX model files found.")
        return 2 if args.require_five else 0

    valid = 0
    failures = []
    for path in paths:
        try:
            size = path.stat().st_size
            if size < 16:
                raise ValueError(f"file too small ({size} bytes)")
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
            inputs = session.get_inputs()
            outputs = session.get_outputs()
            if not inputs or not outputs:
                raise ValueError("model must declare at least one input and one output")

            feed = {}
            for meta in inputs:
                if meta.type not in SUPPORTED:
                    raise ValueError(f"unsupported smoke-test input type {meta.type!r} for {meta.name}")
                feed[meta.name] = np.zeros(shape_for(meta), dtype=SUPPORTED[meta.type])
            result = session.run(None, feed)
            if len(result) != len(outputs):
                raise ValueError("runtime output count does not match declared outputs")
            print(json.dumps({
                "file": path.name,
                "bytes": size,
                "sha256": digest,
                "inputs": [{"name": x.name, "type": x.type, "shape": x.shape} for x in inputs],
                "outputs": [{"name": x.name, "type": x.type, "shape": x.shape} for x in outputs],
                "cpu_zero_input_smoke_test": "PASS",
            }, ensure_ascii=False))
            valid += 1
        except Exception as exc:
            failures.append(f"{path.name}: {exc}")
            print(f"INVALID: {path.name}: {exc}", file=sys.stderr)

    print(f"Valid models with CPU smoke-test pass: {valid}/{len(paths)}")
    for failure in failures:
        print(f"FAILURE: {failure}", file=sys.stderr)

    if args.require_five and (len(paths) != 5 or valid != 5):
        print("MODEL READINESS: FAIL — exactly five valid, loadable models are required.")
        return 1
    if valid != len(paths):
        print("MODEL READINESS: FAIL — one or more discovered files are not usable ONNX models.")
        return 1
    print("MODEL FILE/CPU RUNTIME CHECK: PASS")
    print("This is not evidence of gameplay accuracy, compatible labels, or correct action decisions.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
