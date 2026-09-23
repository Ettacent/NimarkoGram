"""Production ImageReceiver: preview -> first frame must not blink or brighten twice.

Uses the real delivery, draw and alpha-clock methods from the existing JVM
harness. Android/decoders are fakes; these checks do not claim GPU coverage.
"""
import unittest

import test_emoji_first_frame_fade as first_frame


SCENARIOS = r"""
    static void mediaPreviewHandoff() {
        for (boolean background : new boolean[]{false, true}) {
            for (boolean late : new boolean[]{false, true}) {
                for (boolean webm : new boolean[]{false, true}) {
                    ImageReceiver r = receiver();
                    r.currentMediaLocation = new Object();
                    BitmapDrawable preview = new BitmapDrawable();
                    r.staticThumbDrawable = new SvgHelper.SvgDrawable();
                    Drawable media = webm ? new AnimatedFileDrawable() : new RLottieDrawable();
                    if (!late) r.deliver(preview, ImageReceiver.TYPE_IMAGE, true);
                    r.deliver(media, ImageReceiver.TYPE_MEDIA, true);
                    if (late) r.deliver(preview, ImageReceiver.TYPE_IMAGE, true);
                    check(r.frame(1000, background).alpha(preview) == 255, "decoded preview visible while waiting");
                    if (webm) ((AnimatedFileDrawable) media).ready = true;
                    else ((RLottieDrawable) media).ready = true;
                    Canvas start = r.frame(2000, background);
                    check(start.alpha(media) == 0, "first animated frame starts transparently");
                    check(start.alpha(preview) == 255,
                            "ready frame replaced a loaded preview with a placeholder: background=" + background + " late=" + late);
                    for (long time = 2008; time < 2140; time += 8) {
                        Canvas c = r.frame(time, background);
                        // Layer-adjusted weights must cover the transition;
                        // actual SRC_OVER/ADD pixels are checked by the compositing sidecar.
                        check(c.alpha(preview) + c.alpha(media) >= 254,
                                "no opacity hole during preview/media blend");
                    }
                    for (long time = 2144; time <= 2400; time += 8) r.frame(time, background);
                    check(r.frame(2400, background).alpha(media) == 255, "media fade completes");
                }
            }
        }
    }

    static void photoPreviewHandoff() {
        for (boolean background : new boolean[]{false, true}) {
            for (boolean staticThumb : new boolean[]{false, true}) {
                ImageReceiver r = receiver();
                r.setCrossfadeOnReady(false); // ordinary photo, not an emoji/sticker
                r.videoThumbIsSame = false;
                r.crossfadeByScale = 0;
                if (staticThumb) r.staticThumbDrawable = new BitmapDrawable();
                BitmapDrawable preview = new BitmapDrawable();
                r.deliver(preview, ImageReceiver.TYPE_THUMB, false);
                r.currentAlpha = .4f;
                r.lastUpdateAlphaTime = 1000;
                Canvas before = r.frame(1000, background);
                int opacity = before.alpha(preview);
                BitmapDrawable photo = new BitmapDrawable();
                r.deliver(photo, ImageReceiver.TYPE_IMAGE, false);
                Canvas after = r.frame(1000, background);
                check(after.alpha(photo) == 0, "photo starts its quality transition");
                check(Math.abs(after.alpha(preview) - opacity) <= 1,
                        "photo delivery jumped preview brightness: " + opacity + " -> " + after.alpha(preview));
            }
        }
    }

    static void latePreviewDoesNotRestartMedia() {
        for (boolean background : new boolean[]{false, true}) {
            for (boolean cached : new boolean[]{false, true}) {
                ImageReceiver r = receiver();
                r.staticThumbDrawable = new SvgHelper.SvgDrawable();
                RLottieDrawable media = new RLottieDrawable();
                r.deliver(media, ImageReceiver.TYPE_MEDIA, true);
                media.ready = true;
                r.frame(3000, background);
                r.frame(3030, background);
                float alpha = r.currentAlpha;
                long clock = r.lastUpdateAlphaTime;
                BitmapDrawable late = new BitmapDrawable();
                r.deliver(late, ImageReceiver.TYPE_IMAGE, cached);
                check(r.currentAlpha == alpha && r.lastUpdateAlphaTime == clock,
                        "late preview interrupted the final image fade: " + alpha + " -> " + r.currentAlpha);
                Canvas c = r.frame(3030, background);
                check(c.alpha(late) == -1, "a preview arriving after the first frame must not flash underneath it");
            }
        }
    }

    static void sharedBadgeClock() {
        ImageReceiver r = receiver();
        RLottieDrawable badge = new RLottieDrawable();
        r.deliver(badge, ImageReceiver.TYPE_IMAGE, true);
        badge.ready = true;
        SystemClock.now = 4000;
        for (int frame = 0; frame <= 40; frame++) {
            Canvas owned = new Canvas();
            r.drawWithoutLoadFade(owned);
            check(owned.alpha(badge) == 255, "status wrapper applies its own opacity only once");
            check(!r.drawWithoutLoadFade, "scoped opacity must not leak to other consumers");
            SystemClock.now += 8;
        }
        check(r.currentAlpha == 1, "status wrapper froze the shared load fade");
        check(r.frame(SystemClock.now, false).alpha(badge) == 255,
                "rebind/new consumer must not make the visible badge transparent again");
        check(r.frame(SystemClock.now, true).alpha(badge) == 255,
                "background consumers must not restart a badge already displayed by a wrapper");
    }
"""


class ImagePreviewHandoffTest(unittest.TestCase):
    def source(self, scenario):
        return first_frame.EmojiFirstFrameFadeTest().source().replace(
            "public static void main(String[] args) {",
            SCENARIOS + "public static void main(String[] args) { " + scenario + "();",
        )

    def test_media_keeps_the_preview_until_covered(self):
        run = first_frame.EmojiFirstFrameFadeTest().run_harness(self.source("mediaPreviewHandoff"))
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)

    def test_photo_keeps_preview_opacity(self):
        run = first_frame.EmojiFirstFrameFadeTest().run_harness(self.source("photoPreviewHandoff"))
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)

    def test_late_preview_does_not_interrupt_final_fade(self):
        run = first_frame.EmojiFirstFrameFadeTest().run_harness(self.source("latePreviewDoesNotRestartMedia"))
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)

    def test_status_wrapper_advances_the_shared_load_clock(self):
        run = first_frame.EmojiFirstFrameFadeTest().run_harness(self.source("sharedBadgeClock"))
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)

    def test_negative_controls(self):
        mutations = [
            ("mediaPreviewHandoff", "crossfadeFromImage = crossfadeOnReadyDrawable == currentMediaDrawable",
             "crossfadeFromImage = false && crossfadeOnReadyDrawable == currentMediaDrawable",
             "ready frame replaced a loaded preview"),
            ("photoPreviewHandoff", "if (currentThumbDrawable != null) {\n                        // The preview",
             "if (currentThumbDrawable != null && staticThumbDrawable != null) {\n                        // The preview",
             "photo delivery jumped preview brightness"),
            ("latePreviewDoesNotRestartMedia", "boolean mediaOwnsFade = currentMediaDrawable != null",
             "boolean mediaOwnsFade = false && currentMediaDrawable != null",
             "late preview interrupted the final image fade"),
        ]
        for scenario, before, after, reason in mutations:
            with self.subTest(scenario=scenario):
                source = self.source(scenario)
                self.assertEqual(source.count(before), 1)
                run = first_frame.EmojiFirstFrameFadeTest().run_harness(source.replace(before, after))
                self.assertNotEqual(run.returncode, 0)
                self.assertIn(reason, run.stderr)
        source = self.source("sharedBadgeClock")
        scoped_draw = first_frame.method(source, "public boolean drawWithoutLoadFade(")
        old_draw = """public boolean drawWithoutLoadFade(Canvas canvas) {
            float saved = currentAlpha;
            currentAlpha = 1;
            try { return draw(canvas, null); }
            finally { currentAlpha = saved; }
        }"""
        run = first_frame.EmojiFirstFrameFadeTest().run_harness(source.replace(scoped_draw, old_draw))
        self.assertNotEqual(run.returncode, 0)
        self.assertTrue("status wrapper froze the shared load fade" in run.stderr
                        or "status wrapper applies its own opacity only once" in run.stderr)


if __name__ == "__main__":
    unittest.main()
