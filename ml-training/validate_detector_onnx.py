#!/usr/bin/env python3
"""Strict graph and CPU-inference smoke test for the RostikAI scene detector.

This validates the exported tensor contract and executable graph, not real-game
accuracy. Accuracy requires a separately labelled, held-out gameplay dataset.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort


EXPECTED_INPUT_SHAPE = (1, 3, 640, 640)
EXPECTED_OUTPUT_SHAPE = (1, 12, 8400)


def shape_of(value_info) -> tuple[int | str | None, ...]:
    dims = []
    for dim in value_info.type.tensor_type.shape.dim:
        if dim.HasField("dim_value"):
            dims.append(int(dim.dim_value))
        elif dim.HasField("dim_param"):
            dims.append(dim.dim_param)
        else:
            dims.append(None)
    return tuple(dims)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    if not args.model.is_file() or args.model.stat().st_size == 0:
        raise SystemExit(f"Model missing or empty: {args.model}")

    graph = onnx.load(str(args.model), load_external_data=True)
    onnx.checker.check_model(graph)

    session = ort.InferenceSession(
        str(args.model),
        providers=["CPUExecutionProvider"],
    )
    inputs = session.get_inputs()
    outputs = session.get_outputs()
    if len(inputs) != 1 or len(outputs) != 1:
        raise SystemExit(
            f"Unexpected IO count: inputs={len(inputs)}, outputs={len(outputs)}"
        )

    input_meta, output_meta = inputs[0], outputs[0]
    input_shape = tuple(input_meta.shape)
    output_shape = tuple(output_meta.shape)
    if input_meta.type != "tensor(float)":
        raise SystemExit(f"Expected float32 input, got {input_meta.type}")
    if input_shape != EXPECTED_INPUT_SHAPE:
        raise SystemExit(
            f"Input contract mismatch: expected {EXPECTED_INPUT_SHAPE}, got {input_shape}"
        )
    if output_meta.type != "tensor(float)":
        raise SystemExit(f"Expected float32 output, got {output_meta.type}")
    if output_shape != EXPECTED_OUTPUT_SHAPE:
        raise SystemExit(
            f"Output contract mismatch: expected {EXPECTED_OUTPUT_SHAPE}, got {output_shape}"
        )

    # A deterministic, correctly shaped input proves the graph executes on CPU.
    # It is not a gameplay accuracy test.
    sample = np.zeros(EXPECTED_INPUT_SHAPE, dtype=np.float32)
    result = session.run([output_meta.name], {input_meta.name: sample})[0]
    if tuple(result.shape) != EXPECTED_OUTPUT_SHAPE:
        raise SystemExit(
            f"Runtime output mismatch: expected {EXPECTED_OUTPUT_SHAPE}, got {result.shape}"
        )
    if not np.isfinite(result).all():
        raise SystemExit("Runtime output contains NaN or infinity")

    report = {
        "status": "PASS_GRAPH_AND_CPU_SMOKE_TEST_ONLY",
        "model": args.model.name,
        "bytes": args.model.stat().st_size,
        "sha256": __import__("hashlib").sha256(args.model.read_bytes()).hexdigest(),
        "onnxruntime_version": ort.__version__,
        "provider": "CPUExecutionProvider",
        "inputs": [{
            "name": input_meta.name,
            "type": input_meta.type,
            "shape": list(input_shape),
        }],
        "outputs": [{
            "name": output_meta.name,
            "type": output_meta.type,
            "shape": list(output_shape),
            "runtime_shape": list(result.shape),
            "all_finite": bool(np.isfinite(result).all()),
        }],
        "warning": (
            "Synthetic CPU inference is not a real-game accuracy evaluation. "
            "Do not enable automated actions based on this result."
        ),
    }
    rendered = json.dumps(report, indent=2)
    print(rendered)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(rendered + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
