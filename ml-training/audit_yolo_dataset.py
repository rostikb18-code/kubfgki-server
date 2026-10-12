#!/usr/bin/env python3
"""Audit an Ultralytics/YOLO detection dataset before training."""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
from typing import Any

import yaml
from PIL import Image

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def resolve(root: Path, value: str) -> Path:
    p = Path(value)
    return p if p.is_absolute() else (root / p).resolve()


def read_yaml(path: Path) -> dict[str, Any]:
    if not path.is_file():
        fail(f"dataset YAML not found: {path}")
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
    except Exception as exc:
        fail(f"cannot parse YAML: {exc}")
    if not isinstance(data, dict):
        fail("dataset YAML root must be a mapping")
    return data


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def paired_label_path(image_path: Path, split_path: Path) -> Path:
    """Resolve common YOLO layouts: train/images/x.jpg and images/train/x.jpg."""
    parts = list(image_path.parts)
    image_indexes = [i for i, part in enumerate(parts[:-1]) if part.lower() == "images"]
    if image_indexes:
        # Use the last 'images' directory in the path so parent dataset names are not changed.
        index = image_indexes[-1]
        parts[index] = "labels"
        return Path(*parts).with_suffix(".txt")

    # Layout where each split contains sibling images/ and labels/ directories.
    try:
        relative = image_path.relative_to(split_path)
        if relative.parts and relative.parts[0].lower() == "images":
            return split_path / "labels" / Path(*relative.parts[1:]).with_suffix(".txt")
    except ValueError:
        pass
    # Fallback for split_path itself being the images directory.
    return image_path.parent.parent / "labels" / (image_path.stem + ".txt")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", required=True, help="Path to dataset data.yaml")
    args = parser.parse_args()
    yaml_path = Path(args.data).resolve()
    cfg = read_yaml(yaml_path)
    root_value = cfg.get("path", ".")
    root = resolve(yaml_path.parent, str(root_value))
    names = cfg.get("names")
    if isinstance(names, list):
        class_names = names
    elif isinstance(names, dict):
        try:
            keys = sorted(int(k) for k in names.keys())
            if keys != list(range(len(keys))):
                fail(f"class IDs in names must be contiguous from 0; found {keys}")
            class_names = [names.get(k, names.get(str(k))) for k in keys]
        except (TypeError, ValueError):
            fail("names must be a list or mapping of integer class IDs to names")
    else:
        fail("data.yaml must define names as a list or mapping")
    if not class_names or any(not str(name).strip() for name in class_names):
        fail("class list is empty or contains blank names")
    print(f"Dataset: {root}")
    print(f"Classes ({len(class_names)}): " + ", ".join(map(str, class_names)))

    all_hashes: dict[str, str] = {}
    errors = 0
    warnings = 0
    split_images: dict[str, set[str]] = {}
    for split in ("train", "val", "test"):
        entry = cfg.get(split)
        if entry is None:
            if split in ("train", "val"):
                print(f"ERROR: missing required '{split}' split")
                errors += 1
            else:
                print("WARN: no test split configured; final unbiased evaluation is unavailable")
                warnings += 1
            continue
        entries = entry if isinstance(entry, list) else [entry]
        images: list[Path] = []
        for item in entries:
            split_path = resolve(root, str(item))
            if split_path.is_dir():
                images.extend(p for p in split_path.rglob("*") if p.suffix.lower() in IMAGE_SUFFIXES)
            elif split_path.is_file() and split_path.suffix.lower() == ".txt":
                for line in split_path.read_text(encoding="utf-8").splitlines():
                    if line.strip():
                        p = Path(line.strip())
                        images.append(p if p.is_absolute() else (root / p).resolve())
            else:
                print(f"ERROR: {split} path does not exist or is not an image directory/list: {split_path}")
                errors += 1
        if not images:
            print(f"ERROR: no images found in {split}")
            errors += 1
            continue
        split_hashes: set[str] = set()
        missing_labels = 0
        empty_labels = 0
        bad_labels = 0
        box_count = 0
        for image_path in images:
            if not image_path.is_file():
                print(f"ERROR: image listed but missing: {image_path}")
                errors += 1
                continue
            try:
                with Image.open(image_path) as im:
                    im.verify()
            except Exception:
                print(f"ERROR: unreadable image: {image_path}")
                errors += 1
                continue
            file_hash = digest(image_path)
            split_hashes.add(file_hash)
            previous = all_hashes.get(file_hash)
            if previous:
                print(f"WARN: duplicate image content across/in splits: {image_path} (also {previous})")
                warnings += 1
            else:
                all_hashes[file_hash] = f"{split}:{image_path}"
            label_path = paired_label_path(image_path, split_path)
            if not label_path.is_file():
                missing_labels += 1
                continue
            lines = [line.strip() for line in label_path.read_text(encoding="utf-8").splitlines() if line.strip()]
            if not lines:
                empty_labels += 1
            for line_no, line in enumerate(lines, 1):
                parts = line.split()
                try:
                    if len(parts) != 5:
                        raise ValueError("expected 5 fields")
                    cls = int(parts[0])
                    x, y, w, h = map(float, parts[1:])
                    if cls < 0 or cls >= len(class_names):
                        raise ValueError(f"class id {cls} outside [0, {len(class_names)-1}]")
                    if not all(0.0 <= v <= 1.0 for v in (x, y, w, h)) or w <= 0 or h <= 0:
                        raise ValueError("box values must be normalized to [0,1], with positive width/height")
                    box_count += 1
                except ValueError as exc:
                    print(f"ERROR: {label_path}:{line_no}: {exc}")
                    bad_labels += 1
        print(f"{split}: {len(images)} images, {box_count} valid boxes, {empty_labels} empty labels, {missing_labels} missing labels, {bad_labels} malformed labels")
        if missing_labels:
            print(f"WARN: {missing_labels} images have no paired label file; verify these are intentional negative samples")
            warnings += missing_labels
        if bad_labels:
            errors += bad_labels
        split_images[split] = split_hashes

    for left, right in (("train", "val"), ("train", "test"), ("val", "test")):
        if left in split_images and right in split_images:
            overlap = split_images[left] & split_images[right]
            if overlap:
                print(f"ERROR: {len(overlap)} exact duplicate image(s) overlap {left} and {right}")
                errors += len(overlap)
    print(f"Audit result: {errors} error(s), {warnings} warning(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
