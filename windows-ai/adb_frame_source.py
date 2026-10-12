"""Minimal, read-only Android emulator frame capture via ADB.

No taps/swipes are sent by this module. Input automation is intentionally
separate and must not be enabled until perception has been validated.
"""
from dataclasses import dataclass
import subprocess
from typing import Optional


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


class AdbCaptureError(RuntimeError):
    pass


@dataclass(frozen=True)
class AdbFrame:
    png_bytes: bytes
    captured_at_monotonic: float
    serial: Optional[str]


def capture_png(adb_path: str = "adb", serial: Optional[str] = None, timeout: float = 5.0) -> bytes:
    """Capture the emulator/device display as PNG bytes using adb exec-out."""
    if timeout <= 0:
        raise ValueError("timeout must be positive")
    command = [adb_path]
    if serial:
        command.extend(["-s", serial])
    command.extend(["exec-out", "screencap", "-p"])
    try:
        completed = subprocess.run(
            command,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise AdbCaptureError(f"ADB screencap failed: {exc}") from exc
    if completed.returncode != 0:
        message = completed.stderr.decode("utf-8", errors="replace").strip()
        raise AdbCaptureError(f"ADB exited {completed.returncode}: {message}")
    if not completed.stdout.startswith(PNG_SIGNATURE):
        raise AdbCaptureError("ADB returned empty or non-PNG screen data")
    return completed.stdout
