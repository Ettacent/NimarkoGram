"""Run the complete production drawable/buffer on a deterministic JVM (no app build).

Android drawing, scheduling and the native decoder are fakes. The production
decoder -> UI publication -> prerender queue -> draw/retirement lifecycle is not
reimplemented. Canvas models source-over pixels including bitmap alpha, not Android GPU commands.
"""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SRC = Path(__file__).resolve().parents[2]
JAVA = SRC / "main/java"


def method(source, marker):
    start = source.index(marker)
    opening = source.index("{", start)
    depth = 0
    for token in re.finditer(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|[{}]', source[opening:]):
        if token[0] == "{":
            depth += 1
        elif token[0] == "}":
            depth -= 1
            if depth == 0:
                return source[start:opening + token.end()]
    raise AssertionError(marker)


class GifLoopTransitionTests(unittest.TestCase):
    def harness(self):
        template = (SRC / "test/fixtures/GifLoopTransitionHarness.java.txt").read_text()
        for name in ("AnimatedFileDrawable", "AnimatedFileBuffer"):
            source = (JAVA / f"org/telegram/ui/Components/{name}.java").read_text()
            source = source[source.index("public " + ("final " if name.endswith("Drawable") else "") + "class " + name):]
            template = template.replace(f"/* {name} */", source.replace("public ", "public static ", 1))
        source = (JAVA / "org/telegram/messenger/MessageObject.java").read_text()
        markers = ["public static boolean isGifDocument(TLRPC.Document document)",
                   "public static boolean isGifDocument(TLRPC.Document document, boolean hasGroup)",
                   "public static boolean isNewGifDocument(TLRPC.Document document)",
                   "public static boolean isRoundVideoDocument(TLRPC.Document document)"]
        return template.replace("/* GIF_DETECTION */", "\n".join(method(source, m) for m in markers))

    def run_harness(self, source, scenario="all"):
        self.assertIsNotNone(shutil.which("javac"), "JDK required for source-only lifecycle tests")
        with tempfile.TemporaryDirectory(prefix="gif-loop-test-") as directory:
            path = Path(directory) / "Harness.java"
            path.write_text(source)
            result = subprocess.run(["javac", str(path)], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            return subprocess.run(["java", "-ea", "-cp", directory, "Harness", scenario],
                                  capture_output=True, text=True, timeout=30)

    def test_actual_drawable_lifecycle(self):
        result = self.run_harness(self.harness())
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("PASS: GIF loop lifecycle", result.stdout)

    def test_eof_publication_boundaries(self):
        for scenario in ("publication", "direct", "cache"):
            with self.subTest(scenario=scenario):
                result = self.run_harness(self.harness(), scenario)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_negative_controls(self):
        source = self.harness()
        for before, after, scenario, failure in [
            ("gifLoopBuffer = renderingBuffer;", "gifLoopBuffer = nextRenderingBuffer;", "pixels", "last pixel at seam"),
            ("Math.round(alpha * loopProgress)", "alpha", "pixels", "last pixel at seam"),
            ("Math.round((alpha - incomingAlpha) / (1f - incomingAlpha / 255f))", "alpha", "pixels", "parent alpha preserved"),
            ("if (timestampWrapped && (!gifLoopBlendEnabled || isWebmSticker\n"
             "                            || frameGeneration == lastDecodedGifGeneration && frameGeneration == gifPlaybackGeneration\n"
             "                            && pendingSeekTo < 0 && pendingSeekToUI < 0))",
             "if (metaData[3] < lastTimeStamp)", "seek", "seek is not a repeat"),
            ("frameGeneration == lastDecodedGifGeneration && ", "", "seek", "sync seek does not consume repeat limit"),
            ("&& renderingBuffer.gifPlaybackGeneration == gifPlaybackGeneration", "", "seek", "queued pre-seek loop cannot blend"),
            ("if (gifLoopBlendEnabled && !isWebmSticker && isRunning", "if (gifLoopBlendEnabled && isRunning", "eligibility", "webm flag excludes blend"),
        ]:
            with self.subTest(scenario=scenario, mutation=before):
                self.assertEqual(source.count(before), 1)
                result = self.run_harness(source.replace(before, after, 1), scenario)
                self.assertNotEqual(result.returncode, 0, "mutation escaped the behavioral tests")
                self.assertIn(failure, result.stderr)

    def test_publication_negative_controls(self):
        source = self.harness()
        for before, after, scenario, failure in [
            ("if (backgroundBuffer == null || !backgroundBuffer.hasFrame)",
             "if (backgroundBuffer == null)", "guard", "UI rejects an unfilled opaque buffer"),
            ("return backgroundBuffer.hasFrame ? backgroundBuffer.bitmap : null;",
             "return backgroundBuffer.bitmap;", "direct", "no bitmap before first successful decode"),
            ("// callers must retain their previous texture, not upload these pixels.\n        backgroundBuffer.hasFrame = false;",
             "// readiness was incorrectly retained", "direct",
             "direct EOF tells caller to retain previous texture"),
            ("public Bitmap getBackgroundBitmap() {\n        return backgroundBuffer != null && backgroundBuffer.hasFrame",
             "public Bitmap getBackgroundBitmap() {\n        return backgroundBuffer != null", "direct",
             "background getter cannot expose an unfilled bitmap"),
            ("if (cacheGenerateDecoder.getVideoFrame(generatingCacheBitmap, false, startTime, endTime, this.loop) == 0) {\n            return 0;\n        }",
             "cacheGenerateDecoder.getVideoFrame(generatingCacheBitmap, false, startTime, endTime, this.loop);",
             "cache", "cache cannot encode a failed decode as a frame"),
        ]:
            with self.subTest(scenario=scenario, mutation=before):
                self.assertEqual(source.count(before), 1)
                result = self.run_harness(source.replace(before, after, 1), scenario)
                self.assertNotEqual(result.returncode, 0, "publication mutation escaped tests")
                self.assertIn(failure, result.stderr)

        # Restore BOTH parts of the original catch -> success-callback bug.
        bad = source.replace("if (backgroundBuffer == null || !backgroundBuffer.hasFrame)",
                             "if (backgroundBuffer == null)", 1)
        bad = bad.replace("// a frame. Keep the displayed/prerendered frames and retry decoding.\n"
                          "            AndroidUtilities.runOnUIThread(uiRunnableNoFrame);\n            return;",
                          "// old fallthrough to success callback", 1)
        result = self.run_harness(bad, "publication")
        self.assertNotEqual(result.returncode, 0, "original catch/publication bug escaped tests")
        self.assertIn("failed decode must not publish fresh or poisoned pooled buffer", result.stderr)


if __name__ == "__main__":
    unittest.main()
