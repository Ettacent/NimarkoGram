"""The diagnostic APK's per-frame logger must not ship in the test fix build."""
import pathlib
import unittest


class CloudDebugRemovedTests(unittest.TestCase):
    def test_no_runtime_logger_or_frame_instrumentation(self):
        root = pathlib.Path(__file__).resolve().parents[2] / "main/java"
        blur = root / "org/telegram/ui/Components/blur3"
        self.assertFalse((blur / "CloudFlickerDebug.java").exists())
        for file in blur.rglob("*.java"):
            source = file.read_text()
            for marker in ("CloudFlickerDebug", "NG-cloud-flicker-", "debugCaptureFrame", "debugRecordings"):
                self.assertNotIn(marker, source, str(file))


if __name__ == "__main__":
    unittest.main()
