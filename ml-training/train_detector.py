#!/usr/bin/env python3
"""Train, evaluate and export a YOLO detector; never auto-promote to Android."""
from __future__ import annotations

import argparse
import hashlib
import json
import platform
from datetime import datetime, timezone
from pathlib import Path

import yaml
from ultralytics import YOLO


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--data", required=True, help="Dataset data.yaml")
    p.add_argument("--model", default="yolo11n.pt", help="Ultralytics pretrained checkpoint or local .pt")
    p.add_argument("--epochs", type=int, default=100)
    p.add_argument("--imgsz", type=int, default=640)
    p.add_argument("--batch", type=int, default=-1, help="Use -1 for automatic batch sizing")
    p.add_argument("--device", default="", help="0, cpu, or another Ultralytics device value")
    p.add_argument("--project", default="runs/rostikai")
    p.add_argument("--name", default="brawlstars-detector")
    p.add_argument("--workers", type=int, default=8)
    p.add_argument("--patience", type=int, default=25)
    p.add_argument("--export-opset", type=int, default=17)
    args = p.parse_args()

    data_path = Path(args.data).resolve()
    if not data_path.is_file():
        p.error(f"dataset YAML does not exist: {data_path}")
    if args.epochs < 1 or args.imgsz < 32:
        p.error("epochs must be positive and imgsz must be >= 32")
    cfg = yaml.safe_load(data_path.read_text(encoding="utf-8"))
    if not isinstance(cfg, dict) or not cfg.get("train") or not cfg.get("val") or not cfg.get("names"):
        p.error("data.yaml must define train, val and names")
    root = Path(str(cfg.get("path", data_path.parent)))
    if not root.is_absolute():
        root = (data_path.parent / root).resolve()
    test_entry = cfg.get("test")
    if isinstance(test_entry, str):
        test_path = Path(test_entry)
        test_exists = (test_path if test_path.is_absolute() else root / test_path).exists()
    elif isinstance(test_entry, list):
        test_exists = all((Path(x) if Path(x).is_absolute() else root / x).exists() for x in test_entry)
    else:
        test_exists = False

    run_dir = Path(args.project) / args.name
    run_dir.mkdir(parents=True, exist_ok=True)
    print(f"Training detector with {args.model}; dataset={data_path}; device={args.device or 'auto'}")
    model = YOLO(args.model)
    train_args = {
        "data": str(data_path),
        "epochs": args.epochs,
        "imgsz": args.imgsz,
        "batch": args.batch,
        "workers": args.workers,
        "patience": args.patience,
        "project": args.project,
        "name": args.name,
        "exist_ok": True,
        "plots": True,
        "save": True,
        "verbose": True,
    }
    if args.device:
        train_args["device"] = args.device
    train_result = model.train(**train_args)
    best_path = Path(getattr(train_result, "save_dir", run_dir)) / "weights" / "best.pt"
    if not best_path.is_file():
        # Standard Ultralytics output path fallback; do not silently use last.pt.
        best_path = run_dir / "weights" / "best.pt"
    if not best_path.is_file():
        raise SystemExit(f"Training finished but best.pt was not found. Check run output under {run_dir}")

    best = YOLO(str(best_path))
    validation = best.val(data=str(data_path), split="val", imgsz=args.imgsz, plots=True)
    test_result = None
    if test_exists:
        test_result = best.val(data=str(data_path), split="test", imgsz=args.imgsz, plots=True)
    else:
        print("WARNING: no valid test split configured; results are validation-only and are not a final generalization estimate.")

    exported = best.export(format="onnx", imgsz=args.imgsz, opset=args.export_opset, simplify=False)
    onnx_path = Path(str(exported)).resolve()
    if not onnx_path.is_file() or onnx_path.stat().st_size == 0:
        raise SystemExit(f"ONNX export did not produce a non-empty file: {onnx_path}")

    def metric_summary(result):
        if result is None:
            return None
        box = getattr(result, "box", None)
        if box is None:
            return {"summary": str(result)}
        out = {}
        for key in ("mp", "mr", "map50", "map"):
            value = getattr(box, key, None)
            if value is not None:
                try:
                    out[key] = float(value)
                except (TypeError, ValueError):
                    pass
        return out

    report = {
        "status": "TRAINED_AND_EXPORTED_NOT_YET_ANDROID_VALIDATED",
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "python": platform.python_version(),
        "platform": platform.platform(),
        "dataset_yaml": str(data_path),
        "dataset_yaml_sha256": sha256(data_path),
        "class_names": cfg["names"],
        "initial_model": args.model,
        "best_pt": str(best_path),
        "best_pt_sha256": sha256(best_path),
        "onnx": str(onnx_path),
        "onnx_bytes": onnx_path.stat().st_size,
        "onnx_sha256": sha256(onnx_path),
        "imgsz": args.imgsz,
        "validation_metrics": metric_summary(validation),
        "test_metrics": metric_summary(test_result),
        "notes": [
            "These metrics do not establish performance on unseen maps unless the split is match/map independent.",
            "ONNX export success is not Android runtime or semantic validation.",
            "Review Ultralytics license and dataset licenses before redistribution.",
            "Do not copy this model to production Android assets until output parsing and phone latency are verified."
        ]
    }
    report_path = run_dir / "training_report.json"
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(report, indent=2, ensure_ascii=False))
    print(f"Report: {report_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
