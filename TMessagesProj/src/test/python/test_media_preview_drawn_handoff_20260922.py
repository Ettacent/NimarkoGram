"""Host-JVM checks for the production ImageReceiver media-preview alpha handoff.

The decoder and draw surface are fakes; the readiness and fade methods are
extracted verbatim. This does not replace an Android pixel/device test.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_emoji_first_frame_fade import method


RECEIVER = Path(__file__).resolve().parents[2] / "main/java/org/telegram/messenger/ImageReceiver.java"
METHODS = (
    "private boolean isAnimatedDrawableReady(",
    "private boolean isDrawableReadyForDraw(",
    "private boolean canCrossfadeOnReady(",
    "private boolean canAnimateLoadingTransition(",
    "private void trackCrossfadeOnReady(",
    "private boolean prepareCrossfadeOnReady(",
    "private boolean prepareDrawAlpha(",
    "private void checkAlphaAnimation(",
)

HARNESS = r"""
public class MediaPreviewHandoffHarness {
    static class SystemClock {
        static long now = 1000;
        static long uptimeMillis() { return now; }
    }
    static class Drawable {}
    static class BitmapDrawable extends Drawable {}
    static class AnimatedFileDrawable extends BitmapDrawable {
        boolean ready;
        boolean hasBitmap() { return ready; }
    }
    static class RLottieDrawable extends BitmapDrawable {
        boolean ready;
        boolean hasBitmap() { return ready; }
        boolean hasRenderingBitmap() { return ready; }
        void updateCurrentFrame(long time, boolean background) {}
    }
    static class ImageReceiver {
        static final int TYPE_IMAGE = 0, TYPE_MEDIA = 3;
        boolean manualAlphaAnimator, crossfadeOnReady, isVisible = true;
        boolean forcePreview, forceNotMedia, crossfadeWithOldImage;
        boolean crossfadeWithThumb, crossfadingWithThumb, crossfadeFromImage;
        byte crossfadeAlpha = 1;
        int crossfadeDuration = 150, loadingPresentationGeneration;
        int loadingPlaceholderGeneration = -1, presentedImagePreviewGeneration = -1;
        float currentAlpha = 1, previousAlpha = 1;
        long lastUpdateAlphaTime, currentTime;
        Object currentMediaLocation;
        Drawable currentMediaDrawable, currentImageDrawable, currentThumbDrawable;
        Drawable staticThumbDrawable, crossfadeImage, crossfadeOnReadyDrawable;
        Object crossfadeShader;
        Drawable presentedImagePreview;
        PreviewAppearance previewAppearance;
        int invalidations;
        static class PreviewAppearance {
            boolean complete(long now, boolean finalFadeComplete) { return false; }
        }
        AnimatedFileDrawable getAnimation() {
            if (currentMediaDrawable instanceof AnimatedFileDrawable) return (AnimatedFileDrawable) currentMediaDrawable;
            if (currentImageDrawable instanceof AnimatedFileDrawable) return (AnimatedFileDrawable) currentImageDrawable;
            return null;
        }
        RLottieDrawable getLottieAnimation() {
            if (currentMediaDrawable instanceof RLottieDrawable) return (RLottieDrawable) currentMediaDrawable;
            if (currentImageDrawable instanceof RLottieDrawable) return (RLottieDrawable) currentImageDrawable;
            return null;
        }
        void invalidate() { invalidations++; }
        void recycleBitmap(String key, int type) { crossfadeImage = null; }
        /* PRODUCTION */
    }
    static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    static ImageReceiver receiver(boolean shown) {
        ImageReceiver r = new ImageReceiver();
        r.currentMediaLocation = new Object();
        r.currentImageDrawable = new BitmapDrawable();
        if (shown) {
            r.presentedImagePreview = r.currentImageDrawable;
            r.presentedImagePreviewGeneration = r.loadingPresentationGeneration;
        }
        return r;
    }
    static void handoff(boolean background, boolean lottie) {
        ImageReceiver r = receiver(true);
        Drawable media = lottie ? new RLottieDrawable() : new AnimatedFileDrawable();
        r.currentMediaDrawable = media;
        r.prepareDrawAlpha(background);
        check(r.currentAlpha == 1 && !r.crossfadeFromImage, "decode must not consume preview fade");
        if (lottie) ((RLottieDrawable) media).ready = true;
        else ((AnimatedFileDrawable) media).ready = true;
        SystemClock.now = 2000;
        r.prepareDrawAlpha(background);
        check(r.currentAlpha == 0 && r.crossfadeFromImage && r.crossfadeWithThumb,
              "ready media must start over painted image preview");
        check(r.previousAlpha == 1, "predecessor remains opaque at handoff");
        SystemClock.now = 2050;
        r.prepareDrawAlpha(background);
        check(r.currentAlpha > 0 && r.currentAlpha < 1, "media fade must progress");
        SystemClock.now = 2100;
        r.prepareDrawAlpha(background);
        SystemClock.now = 2150;
        r.prepareDrawAlpha(background);
        check(r.currentAlpha == 1 && !r.crossfadeFromImage && r.presentedImagePreview == null,
              "exactly complete fade must release predecessor");
        for (int n = 0; n < 4; n++) {
            SystemClock.now += 64;
            r.prepareDrawAlpha(background);
        }
        check(r.currentAlpha == 1 && !r.crossfadeFromImage && r.presentedImagePreview == null,
              "completed fade must release predecessor");
    }
    static void unseenCache(boolean background) {
        ImageReceiver r = receiver(false);
        r.currentMediaDrawable = new BitmapDrawable();
        r.prepareDrawAlpha(background);
        check(r.currentAlpha == 1 && !r.crossfadeFromImage,
              "ready cache without painted predecessor must be immediate");
    }
    static void seenCache(boolean background) {
        ImageReceiver r = receiver(true);
        r.currentMediaDrawable = new BitmapDrawable();
        SystemClock.now = 3000;
        r.prepareDrawAlpha(background);
        check(r.currentAlpha == 0 && r.crossfadeFromImage,
              "ready cache replacing painted preview must fade");
        r = receiver(true);
        r.presentedImagePreviewGeneration--;
        r.currentMediaDrawable = new BitmapDrawable();
        r.prepareDrawAlpha(background);
        check(r.currentAlpha == 1, "stale worker generation cannot donate a predecessor");
    }
    static void optedIn(boolean background) {
        ImageReceiver r = receiver(true);
        r.crossfadeOnReady = true;
        r.loadingPlaceholderGeneration = r.loadingPresentationGeneration;
        RLottieDrawable media = new RLottieDrawable();
        r.currentMediaDrawable = media;
        r.trackCrossfadeOnReady(media, ImageReceiver.TYPE_MEDIA, true);
        r.prepareDrawAlpha(background);
        check(r.crossfadeOnReadyDrawable == media, "pending animation retains first-frame gate");
        media.ready = true;
        SystemClock.now = 4000;
        r.prepareDrawAlpha(background);
        check(r.currentAlpha == 0 && r.crossfadeFromImage && r.crossfadeOnReadyDrawable == null,
              "opted-in handoff starts exactly at first frame");
        SystemClock.now = 4050;
        r.prepareDrawAlpha(background);
        check(r.currentAlpha > 0, "opted-in handoff advances only once");
    }
    public static void main(String[] args) {
        for (boolean background : new boolean[]{false, true}) {
            unseenCache(background);
            for (boolean lottie : new boolean[]{false, true}) handoff(background, lottie);
            seenCache(background);
            optedIn(background);
        }
        System.out.println("PASS: drawn media-preview handoff");
    }
}
"""


class MediaPreviewDrawnHandoffTest(unittest.TestCase):
    def source(self, mutation=None):
        receiver = RECEIVER.read_text()
        draw = method(receiver, "public boolean draw(Canvas canvas, BackgroundThreadDrawHolder")
        delivery = method(receiver, "protected boolean setImageBitmapByKey(")
        self.assertIn("presentedImagePreview = currentImageDrawable;", draw)
        guard = "if (!forcePreview && !forceNotMedia && currentImageDrawable != null && currentMediaDrawable != null"
        self.assertIn(guard, delivery)
        self.assertLess(delivery.index(guard), delivery.index("currentImageDrawable = drawable;"))
        self.assertIn("return false;", delivery[delivery.index(guard):delivery.index("currentImageDrawable = drawable;")])
        production = "\n".join(method(receiver, signature) for signature in METHODS)
        if mutation:
            before, after = mutation
            self.assertEqual(production.count(before), 1)
            production = production.replace(before, after)
        return HARNESS.replace("/* PRODUCTION */", production)

    def compile_and_run(self, source):
        with tempfile.TemporaryDirectory(prefix="media-preview-handoff-") as directory:
            java = Path(directory) / "MediaPreviewHandoffHarness.java"
            java.write_text(source)
            compiled = subprocess.run(["javac", "-d", directory, str(java)],
                                      capture_output=True, text=True, timeout=30)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            return subprocess.run(["java", "-ea", "-cp", directory, "MediaPreviewHandoffHarness"],
                                  capture_output=True, text=True, timeout=30)

    def test_drawn_preview_and_cache_paths(self):
        run = self.compile_and_run(self.source())
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)

    def test_unseen_cache_negative_control(self):
        run = self.compile_and_run(self.source((
            "&& presentedImagePreview == currentImageDrawable\n"
            "                && presentedImagePreviewGeneration == loadingPresentationGeneration\n"
            "                && !crossfadeFromImage",
            "&& currentImageDrawable != null\n                && !crossfadeFromImage",
        )))
        self.assertNotEqual(run.returncode, 0)
        self.assertIn("ready cache without painted predecessor must be immediate", run.stderr)


if __name__ == "__main__":
    unittest.main()
