#!/usr/bin/env python3
"""Bootstrap the audited upstream BrawlStarsBot in an isolated folder.

This runs the upstream project's own implementation instead of pretending the
current RostikAI placeholder policy is a trained game-playing AI.

Windows-only: upstream uses Win32 window capture and desktop mouse/keyboard
input, and is designed around BlueStacks. It does not control the Android app
directly and does not run inside the native Android APK.
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

UPSTREAM_URL = "https://github.com/Jooi025/BrawlStarsBot.git"
UPSTREAM_COMMIT = "a5a797b28f1aa662db8364515bfd8342e66557a6"
ROOT = Path(__file__).resolve().parent
VENDOR_DIR = ROOT / "vendor" / "BrawlStarsBot"
EXPECTED_LICENSE_MARKERS = ("MIT License", "Copyright (c) 2023 Joseph")


def run(command: list[str], *, cwd: Path | None = None) -> None:
    print("+", " ".join(command), flush=True)
    subprocess.run(command, cwd=str(cwd) if cwd else None, check=True)


def ensure_checkout() -> None:
    if not (VENDOR_DIR / ".git").exists():
        VENDOR_DIR.parent.mkdir(parents=True, exist_ok=True)
        run(["git", "clone", "--no-checkout", UPSTREAM_URL, str(VENDOR_DIR)])
    run(["git", "fetch", "--depth", "1", "origin", UPSTREAM_COMMIT], cwd=VENDOR_DIR)
    run(["git", "checkout", "--detach", UPSTREAM_COMMIT], cwd=VENDOR_DIR)

    license_path = VENDOR_DIR / "LICENSE"
    if not license_path.is_file():
        raise RuntimeError("Upstream LICENSE is missing; refusing to run unverified code.")
    license_text = license_path.read_text(encoding="utf-8", errors="replace")
    if not all(marker in license_text for marker in EXPECTED_LICENSE_MARKERS):
        raise RuntimeError("Upstream license text differs from the audited MIT license.")

    # The upstream repository's OpenVINO model is used by its default config.
    model = VENDOR_DIR / "yolov8_model" / "yolov8_openvino_model" / "yolov8.xml"
    weights = VENDOR_DIR / "yolov8_model" / "yolov8_openvino_model" / "yolov8.bin"
    if not model.is_file() or not weights.is_file():
        raise RuntimeError("Upstream OpenVINO model files are missing.")
    print(f"Verified upstream revision: {UPSTREAM_COMMIT}")
    print("Verified repository license notice: MIT / Joseph, 2023")
    print("Verified model files exist (this is not an accuracy or model-license certification).")


def requirements_present() -> bool:
    try:
        import cv2  # noqa: F401
        import numpy  # noqa: F401
        import pyautogui  # noqa: F401
        import ultralytics  # noqa: F401
        import openvino  # noqa: F401
        import win32gui  # noqa: F401
        return True
    except ImportError:
        return False


def main() -> int:
    parser = argparse.ArgumentParser(description="Run the upstream BrawlStarsBot on Windows.")
    parser.add_argument("--install", action="store_true", help="install upstream Python requirements first")
    parser.add_argument("--setup-only", action="store_true", help="download and verify source/model, without game input")
    args = parser.parse_args()

    if os.name != "nt":
        print("ERROR: upstream capture/input uses Windows-only Win32 APIs.", file=sys.stderr)
        return 2
    try:
        ensure_checkout()
        if args.setup_only:
            print("Setup verified. No game input was sent.")
            return 0
        if args.install:
            run([sys.executable, "-m", "pip", "install", "-r", "requirements.txt"], cwd=VENDOR_DIR)
        elif not requirements_present():
            print("Dependencies are missing. Re-run with --install.", file=sys.stderr)
            return 3

        print("\nBefore continuing: open BlueStacks, launch Brawl Stars, select Solo Showdown,")
        print("and check the upstream configuration. This program sends real mouse/keyboard input.")
        print("Stop with Ctrl+C or move the cursor to a screen corner as supported by upstream.")
        run([sys.executable, "main.py"], cwd=VENDOR_DIR)
        return 0
    except (OSError, subprocess.CalledProcessError, RuntimeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
