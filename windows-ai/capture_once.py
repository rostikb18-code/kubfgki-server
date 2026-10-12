"""Save one read-only screenshot from an Android emulator connected via ADB."""
import argparse
from pathlib import Path
import sys

from adb_frame_source import AdbCaptureError, capture_png


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb", help="Path to adb executable (default: adb on PATH)")
    parser.add_argument("--serial", default=None, help="ADB device serial, e.g. emulator-5554")
    parser.add_argument("--output", default="captures/latest.png", help="Output PNG path")
    args = parser.parse_args()
    try:
        png = capture_png(adb_path=args.adb, serial=args.serial)
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(png)
    except (AdbCaptureError, OSError) as exc:
        print(f"Capture failed: {exc}", file=sys.stderr)
        return 1
    print(f"Saved emulator screenshot: {output.resolve()} ({len(png)} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
