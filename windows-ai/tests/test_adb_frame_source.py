import subprocess
import unittest
from unittest.mock import patch

from adb_frame_source import AdbCaptureError, PNG_SIGNATURE, capture_png


class AdbFrameSourceTests(unittest.TestCase):
    @patch("adb_frame_source.subprocess.run")
    def test_captures_png_from_selected_emulator(self, run):
        run.return_value = subprocess.CompletedProcess(
            args=["adb"], returncode=0, stdout=PNG_SIGNATURE + b"fake-png", stderr=b""
        )
        result = capture_png(adb_path="adb", serial="emulator-5554")
        self.assertTrue(result.startswith(PNG_SIGNATURE))
        command = run.call_args.args[0]
        self.assertEqual(command[:3], ["adb", "-s", "emulator-5554"])
        self.assertEqual(command[-3:], ["exec-out", "screencap", "-p"])

    @patch("adb_frame_source.subprocess.run")
    def test_rejects_nonzero_adb_exit(self, run):
        run.return_value = subprocess.CompletedProcess(
            args=["adb"], returncode=1, stdout=b"", stderr=b"device offline"
        )
        with self.assertRaisesRegex(AdbCaptureError, "device offline"):
            capture_png()

    @patch("adb_frame_source.subprocess.run")
    def test_rejects_non_png_output(self, run):
        run.return_value = subprocess.CompletedProcess(
            args=["adb"], returncode=0, stdout=b"not-an-image", stderr=b""
        )
        with self.assertRaisesRegex(AdbCaptureError, "non-PNG"):
            capture_png()

    def test_timeout_must_be_positive(self):
        with self.assertRaises(ValueError):
            capture_png(timeout=0)


if __name__ == "__main__":
    unittest.main()
