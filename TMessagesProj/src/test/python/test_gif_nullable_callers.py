"""Execute real TextureRenderer callsites; audit the other nullable consumers."""
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_gif_loop_transition import JAVA, method


class GifNullableCallerTests(unittest.TestCase):
    def source(self):
        texture = (JAVA / "org/telegram/messenger/video/TextureRenderer.java").read_text()
        round_loop = method(texture, "while (!entity.looped && entity.animatedFileDrawable.getProgressMs()")
        collage = method(texture, "private void stepCollagePart(")
        return r"""public class Harness {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static class Bitmap {}
    static class Decoder {
        boolean fail = true; int progress, calls;
        Bitmap bitmap = new Bitmap();
        Bitmap getNextFrame(boolean loop) {
            if (fail && ++calls > 3) throw new AssertionError("round decode must stop on no frame");
            if (fail) return null;
            progress += 40; return bitmap;
        }
        void skipNextFrame(boolean loop) {}
        int getProgressMs() { return progress; } int getDurationMs() { return 200; }
        void seekToSync(long p) { progress = (int)p; }
    }
    static class Player { void ensure(long p) {} }
    static class Surface { void updateTexImage() {} }
    static class VideoEditedInfo {
        static class Part {
            Decoder animatedFileDrawable = new Decoder(); Player player; Surface surfaceTexture;
            long offset, duration = 200; float left, right = 1, msPerFrame = 40;
        }
        static class MediaEntity { Decoder animatedFileDrawable = new Decoder(); boolean looped; }
    }
    static class Utilities { static long clamp(long n, long max, long min) { return Math.min(max, Math.max(min, n)); } }
    static class GLES20 { static int GL_TEXTURE_2D = 1; static void glBindTexture(int a, int b) {} }
    static class GL10 { static int GL_TEXTURE_2D = 1; }
    static class GLUtils {
        static Bitmap texture = new Bitmap(); static int uploads;
        static void texImage2D(int target, int level, Bitmap bitmap, int border) {
            check(bitmap != null, "never upload a null frame"); texture = bitmap; uploads++;
        }
    }
    int[] collageTextures = {1};
    /* COLLAGE */
    void retainRoundFrame(VideoEditedInfo.MediaEntity entity, Bitmap frame) {
        check(frame != null, "round must not retain a null frame");
    }
    void round(VideoEditedInfo.MediaEntity entity, long roundMs) { /* ROUND */ }
    public static void main(String[] args) {
        Harness h = new Harness();
        for (int start : new int[]{0, 80}) {
            VideoEditedInfo.MediaEntity e = new VideoEditedInfo.MediaEntity();
            e.animatedFileDrawable.progress = start;
            h.round(e, 200);
            check(e.animatedFileDrawable.calls == 1 && !e.looped, "failure is bounded and can retry next draw");
            e.animatedFileDrawable.fail = false; h.round(e, 200);
            check(e.animatedFileDrawable.progress == 200, "round resumes after transient failure");
        }
        VideoEditedInfo.Part p = new VideoEditedInfo.Part();
        Bitmap old = GLUtils.texture;
        h.stepCollagePart(0, p, 120000000L);
        check(GLUtils.uploads == 0 && GLUtils.texture == old, "failed decode retains prior texture");
        p.animatedFileDrawable.fail = false; h.stepCollagePart(0, p, 120000000L);
        check(GLUtils.uploads == 1 && GLUtils.texture == p.animatedFileDrawable.bitmap, "successful decode uploads normally");
        System.out.println("PASS: actual nullable GIF caller control flow");
    }
}
""".replace("/* ROUND */", round_loop).replace("/* COLLAGE */", collage)

    def run_source(self, source):
        with tempfile.TemporaryDirectory(prefix="gif-caller-") as directory:
            path = Path(directory) / "Harness.java"
            path.write_text(source)
            result = subprocess.run(["javac", str(path)], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            return subprocess.run(["java", "-ea", "-cp", directory, "Harness"],
                                  capture_output=True, text=True, timeout=10)

    def test_actual_round_and_collage_callers(self):
        result = self.run_source(self.source())
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_negative_controls(self):
        source = self.source()
        for before, after, failure in [
            ("if (nextRoundFrame == null) {\n                            break;\n                        }",
             "if (false) {\n                            break;\n                        }", "round must not retain a null frame"),
            ("if (bitmap != null) {", "if (true) {", "never upload a null frame"),
        ]:
            with self.subTest(mutation=before):
                self.assertEqual(source.count(before), 1)
                result = self.run_source(source.replace(before, after, 1))
                self.assertNotEqual(result.returncode, 0, "unsafe null caller escaped test")
                self.assertIn(failure, result.stderr)

    def test_remaining_callers_keep_null_guards(self):
        # Source contracts complement runtime checks above. No claim to run the
        # full UI/encoder classes or Android MediaMetadataRetriever on the host.
        for name, start, checks in [
            ("messenger/ImageLoader.java", "Bitmap bitmap = fileDrawable.getFrameAtTime(0, false);",
             ["if (bitmap == null)", "onPostExecute(null);"]),
            ("messenger/ImageLoader.java", "image = fileDrawable.getFrameAtTime(0, true);", ["if (image == null)"]),
            ("messenger/SendMessagesHelper.java", "bitmap = fileDrawable.getFrameAtTime(time, precise);",
             ["if (bitmap == null)", "return createVideoThumbnailAtTime(filePath, time, orientation, false);"]),
            ("ui/PhotoViewer.java", "bitmap = drawable.getFrameAtTime(0);", ["if (current && result != null)"]),
            ("ui/Components/VideoSeekPreviewImage.java", "Bitmap bitmap = fileDrawable.getFrameAtTime(time, false);",
             ["if (bitmap != null)", "if (bitmapFinal != null)"]),
            ("messenger/video/TextureRenderer.java", "Bitmap frameBitmap = entity.animatedFileDrawable.getBackgroundBitmap();",
             ["if (frameBitmap != null)"]),
            ("messenger/video/TextureRenderer.java", "Bitmap bitmap = part.animatedFileDrawable.getNextFrame(false);",
             ["if (bitmap != null)"]),
            ("messenger/video/WebmEncoder.java", "Bitmap frameBitmap = entity.animatedFileDrawable.getBackgroundBitmap();",
             ["if (frameBitmap != null)"]),
            ("messenger/video/WebmEncoder.java", "bitmap = entity.animatedFileDrawable.getBackgroundBitmap();",
             ["if (bitmap != null)"]),
        ]:
            with self.subTest(caller=name, start=start):
                source = (JAVA / "org/telegram" / name).read_text()
                section = source[source.index(start):source.index(start) + 3200]
                for check in checks:
                    self.assertIn(check, section)
        for name in ("VideoTimelineView", "VideoTimelinePlayView"):
            source = (JAVA / f"org/telegram/ui/Components/{name}.java").read_text()
            self.assertNotIn("AnimatedFileDrawable", source)
            self.assertIn("mediaMetadataRetriever.getFrameAtTime(", source)
            self.assertIn("if (bitmap != null)", source)


if __name__ == "__main__":
    unittest.main()
