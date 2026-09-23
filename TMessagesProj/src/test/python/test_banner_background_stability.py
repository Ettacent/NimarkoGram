"""Production-method JVM regressions; no Android build or APK required.

Run with python3 -B -m unittest discover -s TMessagesProj/src/test/python
    -p test_banner_background_stability.py -v
Android drawing/capture dependencies are faked, not the transition/ownership code.
"""
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

SRC = Path(__file__).resolve().parents[2]
JAVA = SRC / "main/java"
RENDERER = JAVA / "app/nimarkogram/messenger/banners/NimarkoBannerRenderer.java"
PROFILE = JAVA / "org/telegram/ui/ProfileActivity.java"


def block(source, marker):
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


class BannerBackgroundStabilityTests(unittest.TestCase):
    def harness(self):
        renderer = RENDERER.read_text()
        profile = PROFILE.read_text()
        template = (SRC / "test/fixtures/BannerBackgroundStabilityHarness.java.txt").read_text()
        methods = [
            "private static float clamp01(", "private static boolean okBmp(",
            "private static void recycle(", "private boolean canRefreshVideoBlur(",
            "private boolean grabBlur(", "public void applyVideoFx(",
            "private boolean usesLiveVideoBlur(", "private void updateLiveVideoBlur(",
            "private float videoVisualProgress(",
            "private static final class PhotoBlur {", "private static final class PhotoBlurLru",
        ]
        animation = (JAVA / "org/telegram/ui/Components/AnimatedFloat.java").read_text()
        animation = animation[animation.index("public class AnimatedFloat"):].replace(
            "public class AnimatedFloat", "class AnimatedFloat", 1
        )
        constants = "\n".join(re.findall(
            r"    private static final (?:double|int) (?:BLUR_FADE_DUR|BLUR_DS|BLUR_SR|FX_MIN_INTERVAL|COLL_SETTLE) = [^;]+;",
            renderer,
        ))
        photo_draw = block(renderer, "public void drawImageBanner(")
        photo_async = photo_draw[photo_draw.index("boolean lite ="):photo_draw.index("int y1q =")]
        alpha = re.search(r"pBlur.setAlpha\((.*?)\);", photo_draw)[1]
        return (template.replace("/* METHODS */", "\n".join(block(renderer, m) for m in methods))
                .replace("/* CONSTANTS */", constants)
                .replace("/* PATTERN */", block(profile, "if (hasEmoji && !nimarkoSuppressBackground"))
                .replace("/* PHOTO_ASYNC */", photo_async)
                .replace("/* PHOTO_ALPHA */", alpha)
                .replace("/* ANIMATED_FLOAT */", animation))

    def run_harness(self, source):
        with tempfile.TemporaryDirectory(prefix="banner-background-") as directory:
            java = Path(directory) / "Harness.java"
            java.write_text(source)
            compile_result = subprocess.run(["javac", str(java)], capture_output=True, text=True, timeout=30)
            self.assertEqual(compile_result.returncode, 0, compile_result.stderr)
            return subprocess.run(["java", "-ea", "-cp", directory, "Harness"],
                                  capture_output=True, text=True, timeout=30)

    def test_production_behavior(self):
        result = self.run_harness(self.harness())
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("PASS: banner background stability", result.stdout)

    def test_negative_controls(self):
        harness = self.harness()
        for before, after, message in [
            ("ngBg * loadedScale", "ngBg", "cold pattern ramps opacity"),
            ("visFactor * photoBlurProgress", "visFactor", "late photo blur starts transparent"),
            ("|| !canRefreshVideoBlur(captureSession, capturePath)) { recycle(fin); return; }",
             ") { recycle(fin); return; }", "stale publication rejected gate 0"),
            ("transition.setCrossFadeEnabled(false)", "transition.setCrossFadeEnabled(true)",
             "old blurred frame stays opaque underneath replacement"),
            ("if (vidDark != null) vidDark.setAlpha(visualProgress);", "",
             "dark overlay follows actual video alpha"),
            ("if (blurFadeStart > 0) postInv();", "", "fade requests next frame even when rate limited"),
        ]:
            with self.subTest(message=message):
                self.assertEqual(harness.count(before), 1)
                result = self.run_harness(harness.replace(before, after, 1))
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stderr)

    def test_cache_and_lifecycle_wiring(self):
        renderer = RENDERER.read_text()
        reset = block(renderer, "private void resetState()")
        self.assertNotIn("blurBmps.clear", reset)
        self.assertNotIn("photoFadeStart.clear", reset)
        switch = block(renderer, "public void onAccountSwitched(")
        self.assertIn("accountGeneration++", switch)
        self.assertIn("clearBmps();", switch)
        self.assertIn("blurBmps.clearAll()", block(renderer, "private void clearBmps()"))
        self.assertIn("blurFadeStart = 0;", block(renderer, "private void removeVidViews(boolean"))
        self.assertIn("new PhotoBlurLru(16)", renderer)
        self.assertIn("new BitmapLru(32)", renderer)
        self.assertIn("public static final boolean DBG = false", renderer)
        self.assertIn("public static final boolean DBG_AV = false", renderer)
        progress = block(renderer, "private float videoVisualProgress(")
        self.assertNotIn("vidFirstFrameTime", progress)
        self.assertIn("suppressBg = vidReady && videoVisualProgress() >= 1f;",
                      block(renderer, "public FrameDecision prepareFrame("))
        self.assertIn("setUpdateListener", block(renderer, "private void dismissFreeze()"))


if __name__ == "__main__":
    unittest.main()
