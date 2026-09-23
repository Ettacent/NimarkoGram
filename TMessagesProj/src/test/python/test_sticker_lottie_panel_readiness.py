"""Focused first-frame readiness checks without building the Android application."""

from pathlib import Path
import subprocess
import tempfile
import unittest


JAVA = Path(__file__).resolve().parents[2] / "main/java/org/telegram"


def method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


class StickerLottiePanelReadinessTest(unittest.TestCase):
    def test_rendering_frame_not_queued_frame_is_ready(self):
        source = (JAVA / "ui/Components/RLottieDrawable.java").read_text()
        ready = method(source, "public final boolean hasRenderingBitmap()")
        harness = """
class RLottieReadiness {
    boolean isRecycled, destroyWhenDone, isInvalid, decoderReady;
    Object renderingBitmap, nextRenderingBitmap;
    boolean canLoadFrames() { return decoderReady; }
    // @READY@
    static void check(boolean yes) { if (!yes) throw new AssertionError(); }
    public static void main(String[] args) {
        RLottieReadiness drawable = new RLottieReadiness();
        drawable.decoderReady = true;
        drawable.nextRenderingBitmap = new Object();
        check(!drawable.hasRenderingBitmap());
        drawable.renderingBitmap = new Object();
        check(drawable.hasRenderingBitmap());
        drawable.isInvalid = true;
        check(!drawable.hasRenderingBitmap());
        drawable.isInvalid = false; drawable.isRecycled = true;
        check(!drawable.hasRenderingBitmap());
        drawable.isRecycled = false; drawable.destroyWhenDone = true;
        check(!drawable.hasRenderingBitmap());
        drawable.destroyWhenDone = false; drawable.decoderReady = false;
        check(!drawable.hasRenderingBitmap());
    }
}
""".replace("// @READY@", ready)
        with tempfile.TemporaryDirectory(prefix="sticker-lottie-") as directory:
            path = Path(directory) / "RLottieReadiness.java"
            path.write_text(harness)
            compiled = subprocess.run(["javac", "-d", directory, str(path)], capture_output=True, text=True, timeout=30)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run(["java", "-ea", "-cp", directory, "RLottieReadiness"],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        decode = method(source, "protected int loadFrameRunnableImpl()")
        self.assertLess(decode.index("if (result < 0)"), decode.index("nextRenderingBitmap = backgroundBitmap;"))
        self.assertIn("return LOAD_FRAME_RESULT_ERROR;", decode)

    def test_panel_frame_snapshots_are_released_and_rebound(self):
        for panel in ("EmojiPacksAlert", "StickerMasksAlert"):
            with self.subTest(panel=panel):
                source = (JAVA / f"ui/Components/{panel}.java").read_text()
                line = source[source.index("class DrawingInBackgroundLine extends DrawingInBackgroundThreadDrawable"):]
                self.assertIn("drawFrame.add(drawable.getImageReceiver(), threadIndex)", line)
                self.assertIn("drawFrame.draw(canvas)", line)
                self.assertIn("drawFrame.release();", method(line, "public void onFrameReady()"))
                self.assertIn("drawInUiThread(canvas, alpha);", method(line, "public void draw(Canvas canvas, long time"))


if __name__ == "__main__":
    unittest.main()
