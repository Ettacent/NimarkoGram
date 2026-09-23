"""Cached delivery followed by an already queued loader callback must not blink.

Runs the actual ImageReceiver acceptance, readiness and draw methods, including
ordinary photos (no emoji opt-in), static topic icons, Lottie and WebM.
"""
import unittest

import test_emoji_first_frame_fade as first_frame


SCENARIOS = r"""
    static void duplicateDelivery() {
        for (boolean optIn : new boolean[]{true, false}) {
            for (int type : new int[]{ImageReceiver.TYPE_IMAGE, ImageReceiver.TYPE_MEDIA}) {
                for (int kind = 0; kind < 3; kind++) {
                    ImageReceiver r = receiver();
                    r.setCrossfadeOnReady(optIn);
                    r.videoThumbIsSame = optIn; // ordinary photos do not opt into the emoji shortcut
                    Drawable d = kind == 0 ? new BitmapDrawable()
                            : kind == 1 ? new RLottieDrawable() : new AnimatedFileDrawable();
                    if (d instanceof RLottieDrawable) ((RLottieDrawable) d).ready = true;
                    if (d instanceof AnimatedFileDrawable) ((AnimatedFileDrawable) d).ready = true;
                    check(r.deliver(d, type, true), "initial cache delivery accepted");
                    check(r.frame(1000, false).alpha(d) == 255, "cache frame is visible");
                    int acquisitions = ImageLoader.acquisitions;
                    check(r.deliver(d, type, false), "queued identical delivery remains accepted");
                    check(ImageLoader.acquisitions == acquisitions, "duplicate delivery acquired the image twice");
                    check(r.frame(1016, false).alpha(d) == 255,
                            "duplicate delivery restarted visible image: optIn=" + optIn + " type=" + type + " kind=" + kind);

                    // A repeated callback during a real fade must preserve its clock,
                    // not restart it or snap to opaque even if reported as cached.
                    r = receiver();
                    check(r.deliver(d, type, false), "new identity accepted");
                    r.frame(2000, false); r.frame(2060, false);
                    float alpha = r.currentAlpha;
                    long time = r.lastUpdateAlphaTime;
                    check(alpha > 0 && alpha < 1, "real first load still fades");
                    r.deliver(d, type, true);
                    check(r.currentAlpha == alpha && r.lastUpdateAlphaTime == time,
                            "duplicate cached callback changed an active fade");
                    r.deliver(d, type, false);
                    check(r.currentAlpha == alpha && r.lastUpdateAlphaTime == time,
                            "duplicate disk callback reset an active fade");
                    check(!r.setImageBitmapByKey(d, "wrong-key", type, false, r.currentGuid),
                            "identity guard must not bypass key validation");
                    check(!r.setImageBitmapByKey(d, type == ImageReceiver.TYPE_MEDIA ? r.currentMediaKey : r.currentImageKey,
                            type, false, r.currentGuid - 1), "identity guard must not bypass generation validation");
                }
                ImageReceiver pending = receiver();
                RLottieDrawable undecoded = new RLottieDrawable();
                pending.deliver(undecoded, type, true);
                pending.frame(5000, false);
                pending.deliver(undecoded, type, false);
                check(pending.crossfadeOnReadyDrawable == undecoded && pending.currentAlpha == 0,
                        "duplicate undecoded object retains pending first-frame fade");
                undecoded.ready = true;
                check(pending.frame(5016, false).alpha(undecoded) == 0, "only first ready frame starts the fade");
                completeFade(pending, undecoded, 5016, false);
            }
        }
    }
"""


class ImageDeliveryIdempotenceTest(unittest.TestCase):
    def source(self):
        return first_frame.EmojiFirstFrameFadeTest().source().replace(
            "public static void main(String[] args) {",
            SCENARIOS + "public static void main(String[] args) { duplicateDelivery();",
        ).replace(
            "void incrementUseCount(String k) {}",
            "static int acquisitions; void incrementUseCount(String k) { acquisitions++; }",
        )

    def test_repeated_callback_does_not_restart_image(self):
        run = first_frame.EmojiFirstFrameFadeTest().run_harness(self.source())
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)

    def test_negative_controls(self):
        for field in ("currentImageDrawable", "currentMediaDrawable"):
            with self.subTest(field=field):
                guard = (f"if ({field} == drawable) {{\n"
                         "                trackStickerFirstFrame(key, type, guid, drawable);\n"
                         "                invalidate();\n                return true;\n            }")
                source = self.source()
                self.assertEqual(source.count(guard), 1)
                run = first_frame.EmojiFirstFrameFadeTest().run_harness(source.replace(guard, ""))
                self.assertNotEqual(run.returncode, 0)
                self.assertIn("duplicate delivery", run.stderr)


if __name__ == "__main__":
    unittest.main()
