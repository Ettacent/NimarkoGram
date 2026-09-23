"""Pixel-level sidecar for the production ImageReceiver delivery/draw methods.

Reuses the first-frame harness's extraction/stubs, not its alpha-map oracle.
The replaced rendering stub performs ordered premultiplied SRC_OVER/ADD on
three samples (solid interior, antialiased edge, transparent hole). No Android
rasterizer, geometry, decoder, GPU, or visual/perceptual equivalence is claimed.
Identical endpoint images must not brighten/dim; unlike endpoints need only be
continuous at handoff/completion. Cache residency alone is not a fade mandate.

Run with PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s
TMessagesProj/src/test/python -p test_image_compositing_continuity.py -v
"""
from pathlib import Path
import subprocess
import tempfile
import unittest

import test_emoji_first_frame_fade as first_frame
import test_sticker_first_frame_fade as sticker_frame


CANVAS = r"""
class Canvas {
    // Premultiplied RGBA, initially transparent. Record every ordered draw.
    double[][] pixels = new double[3][4];
    final List<String> operations = new ArrayList<>();
    static class Saved {
        double[][] destination;
        int opacity;
        boolean layer, add;
        Saved(double[][] d, int a, boolean l, boolean plus) { destination=d; opacity=a; layer=l; add=plus; }
    }
    final List<Saved> stack = new ArrayList<>();
    int layerCount;
    int scaleCount, clipCount;
    final List<float[]> bounds = new ArrayList<>();
    String failOn;
    void composite(Drawable d, int opacity) {
        if (d.label.equals(failOn)) throw new IllegalStateException("injected draw failure");
        operations.add(d.label + "@" + opacity);
        for (int p = 0; p < 3; p++) {
            double a = d.coverage[p] * opacity / 255.0;
            for (int c = 0; c < 3; c++)
                pixels[p][c] = d.rgb[c] * a + pixels[p][c] * (1 - a);
            pixels[p][3] = a + pixels[p][3] * (1 - a);
        }
    }
    // Compare the actual visible result on both light and dark surfaces.
    double distance(Canvas other) {
        double delta = 0;
        for (double background : new double[]{0, 1})
            for (int p = 0; p < 3; p++)
                for (int c = 0; c < 3; c++)
                    delta = Math.max(delta, Math.abs(
                        pixels[p][c] + background * (1 - pixels[p][3]) -
                        (other.pixels[p][c] + background * (1 - other.pixels[p][3]))));
        return delta;
    }
    public String toString() { return operations + " pixels=" + Arrays.deepToString(pixels); }
    int save() { int n = stack.size()+1; stack.add(new Saved(pixels,255,false,false)); return n; }
    int saveLayerAlpha(float l,float t,float r,float b,int a) { return layer(l,t,r,b,a,false); }
    int saveLayer(float l,float t,float r,float b,Paint p) {
        return layer(l,t,r,b,p == null ? 255 : p.alpha,
            p != null && p.xfermode != null && p.xfermode.mode == PorterDuff.Mode.ADD);
    }
    int layer(float l,float t,float r,float b,int a,boolean add) {
        if (!(r > l && b > t)) throw new AssertionError("layer must be bounded and nonempty");
        bounds.add(new float[]{l,t,r,b});
        int n = stack.size()+1;
        stack.add(new Saved(pixels,a,true,add)); pixels = new double[3][4]; layerCount++; return n;
    }
    void restore() {
        Saved saved = stack.remove(stack.size()-1);
        if (saved.layer) {
            double opacity = saved.opacity / 255.0;
            for (int p=0;p<3;p++) {
                double alpha = pixels[p][3] * opacity;
                for (int c=0;c<4;c++) saved.destination[p][c] = saved.add
                    ? Math.min(1, saved.destination[p][c] + pixels[p][c] * opacity)
                    : pixels[p][c] * opacity + saved.destination[p][c] * (1-alpha);
            }
        }
        pixels = saved.destination;
    }
    void restoreToCount(int n) { while (stack.size() >= n) restore(); }
    void clipRect(float a,float b,float c,float d) {}
    void drawColor(int c) {} void clipPath(Path p) { clipCount++; }
    void scale(float a,float b,float c,float d) { scaleCount++; }
}
"""


class ImageCompositingContinuityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = first_frame.EmojiFirstFrameFadeTest().source()
        old_canvas = first_frame.method(source, "class Canvas {")
        source = source.replace(old_canvas, CANVAS)
        source = source.replace("class Drawable {", """class Drawable {
    String label = getClass().getSimpleName();
    double[] rgb = {1, 1, 1};
    double[] coverage = {1, .5, 0};
""")
        old_draw = "if (isAnimatedDrawableReady(d)) canvas.record(d, alpha);"
        if source.count(old_draw) != 1:
            raise AssertionError("first-frame rendering stub changed; audit before adapting")
        source = source.replace(old_draw, "if (isAnimatedDrawableReady(d)) canvas.composite(d, alpha);")
        # Replace only the old test driver; all extracted production is intact.
        source = source[:source.index("public class EmojiFirstFrameHarness {")]
        source += (Path(__file__).resolve().parents[1] /
                   "fixtures/ImageCompositingContinuityHarness.java.txt").read_text()
        cls.production_source = source
        cls.tmp = tempfile.TemporaryDirectory(prefix="image-compositing-")
        cls.addClassCleanup(cls.tmp.cleanup)
        java = Path(cls.tmp.name) / "EmojiFirstFrameHarness.java"
        java.write_text(source)
        result = subprocess.run(["javac", "-d", cls.tmp.name, str(java)],
                                capture_output=True, text=True, timeout=30)
        if result.returncode:
            raise AssertionError(result.stderr)

    def scenario(self, name):
        for background in (False, True):
            with self.subTest(background=background):
                result = subprocess.run(
                    ["java", "-ea", "-cp", self.tmp.name, "EmojiFirstFrameHarness",
                     name, str(background).lower()], capture_output=True, text=True, timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_canvas_oracle_detects_order_and_duplicate_draws(self):
        self.scenario("oracle")

    def test_isolated_premultiplied_add_reference_control(self):
        self.scenario("addReference")

    def test_bitmap_thumb_midfade_with_same_video_thumb_policy(self):
        self.scenario("photoSameVideoThumb")

    def test_bitmap_thumb_midfade_photo_quality_transition_control(self):
        self.scenario("photoQuality")

    def test_svg_to_ready_media_identical_solid_pixels_do_not_dim(self):
        self.scenario("svg")

    def test_transparent_media_has_no_preview_removal_snap(self):
        self.scenario("transparent")

    def test_transparent_webm_has_no_preview_removal_snap(self):
        self.scenario("transparentWebm")

    def test_nonoverlapping_silhouettes_have_no_preview_removal_snap(self):
        self.scenario("nonoverlapping")

    def test_identical_antialiased_images_do_not_double_blend(self):
        self.scenario("doubleBlend")

    def test_override_opacity_does_not_amplify_identical_opaque_images(self):
        self.scenario("override")

    def test_background_holder_is_compositing_snapshot(self):
        self.scenario("holder")

    def test_ready_cache_without_visible_predecessor_is_immediate(self):
        self.scenario("cacheUnseen")

    def test_ready_cache_replacing_visible_svg_is_continuous(self):
        self.scenario("cacheSeen")

    def test_unrelated_late_preview_does_not_change_active_composite(self):
        self.scenario("late")

    def test_frozen_static_and_partial_thumb_presentation(self):
        self.scenario("frozen")

    def test_layers_restore_on_draw_exceptions_and_skip_steady_state(self):
        self.scenario("layers")

    def test_forwarded_blank_history_and_invisible_draw_controls(self):
        self.scenario("history")

    def test_layer_bounds_and_specialized_geometry_paths(self):
        self.scenario("geometry")

    def test_negative_controls(self):
        for before, after, scenario in [
            ("addPaint.setXfermode(CROSSFADE_ADD);", "addPaint.setXfermode(null);", "svg"),
            ("crossfadeWithThumb = backgroundThreadDrawHolder.crossfadeWithThumb;",
             "crossfadeWithThumb = this.crossfadeWithThumb;", "holder"),
            ("canvas.restoreToCount(compositeSave);", "/* broken outer restore */", "layers"),
            ("|| loadingPlaceholderGeneration == loadingPresentationGeneration ? drawable : null;",
             "? drawable : null;", "cacheSeen"),
            ("&& (currentAlpha < 1f || manualAlphaAnimator || crossfadeWithOldImage || crossfadingWithThumb)",
             "", "frozen"),
        ]:
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory(prefix="compositing-mutation-") as tmp:
                self.assertEqual(self.production_source.count(before), 1)
                java = Path(tmp) / "EmojiFirstFrameHarness.java"
                java.write_text(self.production_source.replace(before, after))
                build = subprocess.run(["javac", "-d", tmp, str(java)], capture_output=True, text=True, timeout=30)
                self.assertEqual(build.returncode, 0, build.stderr)
                run = subprocess.run(["java", "-ea", "-cp", tmp, "EmojiFirstFrameHarness", scenario, "true"],
                                     capture_output=True, text=True, timeout=30)
                self.assertNotEqual(run.returncode, 0, "mutation unexpectedly passed")
                self.assertIn("AssertionError", run.stderr)

    def test_presentation_history_uses_real_request_lifecycle(self):
        # This second harness extracts setImage/cancel/getNewGuid/setImageBitmap,
        # not just delivery/draw. Prove the before-first-setImage API contract.
        source, fixture = sticker_frame.source()
        receiver = (first_frame.JAVA / "messenger/ImageReceiver.java").read_text()
        source = source.replace("void setBackupImage() {}", "\n".join([
            first_frame.method(receiver, "public boolean setBackupImage("),
            first_frame.method(receiver, "public boolean onAttachedToWindow("),
            "int currentLayerNum; void didReceivedNotification(int n, int a, int f) {}",
        ]))
        source = source.replace(first_frame.method(source, "static class SetImageBackup {"),
                                first_frame.method(receiver, "private static class SetImageBackup {"))
        source = source.replace("class Bitmap {", "class Bitmap { boolean isRecycled() { return false; }")
        source = source.replace("void removeObserver(", """int getCurrentHeavyOperationFlags() { return 0; }
    boolean isAnimationInProgress() { return false; }
    void addObserver(Object o, int n) {} void removeObserver(""")
        source = source.replace("interface AttachableDrawable {", """interface AttachableDrawable {
    default void onAttachedToWindow(ImageReceiver r) {} """)
        source = source.replace("static class Decorator {", """static class Decorator {
    void onAttachedToWindow(ImageReceiver r) {} """)
        fixture = fixture.replace("class ImageLocation {", "class ImageLocation { Object webFile; String path;")
        source += r"""
class LoadingPresentationLifecycle {
    static void check(boolean b, String why) { if (!b) throw new AssertionError(why); }
    static ImageReceiver fresh() {
        ImageReceiver r = StickerFirstFrameHarness.receiver();
        r.currentImageKey = r.currentMediaKey = r.currentThumbKey = null;
        return r;
    }
    static void result(ImageReceiver r, boolean fade, String why) {
        RLottieDrawable d = new RLottieDrawable(); d.ready = true;
        check(r.deliver(d,ImageReceiver.TYPE_IMAGE,true), "accepted " + why);
        check(r.frame(1000,false).alpha(d) == (fade ? 0 : 255), why);
    }
    public static void main(String[] args) {
        for (int mode=0;mode<6;mode++) {
            ImageReceiver r = fresh(); r.attachedToWindow=false; r.allowLoadingOnAttachedOnly=true;
            if (mode != 1) r.markLoadingPlaceholderPresented();
            StickerFirstFrameHarness.request(r,"deferred",null);
            check(r.currentImageKey == null && r.setImageBackup != null,"request is actually deferred");
            if (mode == 2) r.cancelLoadImage();
            if (mode == 3) r.onDetachedFromWindow();
            if (mode == 4) StickerFirstFrameHarness.request(r,"other",null);
            if (mode == 5) StickerFirstFrameHarness.request(r,"deferred",null);
            check(r.onAttachedToWindow(),"production attach restores backup");
            result(r,mode==0 || mode==5,"attached-only restore mode="+mode);
        }
        for (int mode=0;mode<9;mode++) {
            ImageReceiver r = fresh(); r.markLoadingPlaceholderPresented();
            StickerFirstFrameHarness.request(r,"first",null);
            switch(mode) {
                case 0: break; // Mark before first synchronous cache lookup.
                case 1: StickerFirstFrameHarness.request(r,"first",null); break;
                case 2: StickerFirstFrameHarness.request(r,"other",null); break;
                case 3: r.cancelLoadImage(); StickerFirstFrameHarness.request(r,"first",null); break;
                case 4: r.onDetachedFromWindow(); r.attachedToWindow=true;
                        StickerFirstFrameHarness.request(r,"first",null); break;
                case 5: r.clearImage(); StickerFirstFrameHarness.request(r,"first",null); break;
                case 6: r.setCurrentAccount(1); StickerFirstFrameHarness.request(r,"first",null); break;
                case 7: r.setImageBitmap(new BitmapDrawable());
                        StickerFirstFrameHarness.request(r,"other",null); break;
                case 8:
                    ImageReceiver.BackgroundThreadDrawHolder old = r.setDrawInBackgroundThread(null,0);
                    StickerFirstFrameHarness.request(r,"other",null);
                    r.draw(new Canvas(),old); // Stale blank must not mark the new request.
                    break;
            }
            result(r,mode<2,"request lifecycle mode="+mode);
        }
    }
}
"""
        with tempfile.TemporaryDirectory(prefix="compositing-lifecycle-") as tmp:
            sticker_frame.compile_sources(tmp, (source, fixture))
            run = subprocess.run(["java", "-ea", "-cp", tmp, "LoadingPresentationLifecycle"],
                                 capture_output=True, text=True, timeout=30)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)


if __name__ == "__main__":
    unittest.main()
