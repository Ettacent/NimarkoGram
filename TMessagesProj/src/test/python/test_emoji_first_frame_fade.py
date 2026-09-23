"""Run production receiver delivery/draw/lifecycle code on a deterministic host JVM.

Android pixel rendering and loading are fakes; fade, drawable selection, cache
delivery, background snapshots and disposal execute verbatim Java source.
No Gradle, APK, device, network or repository-generated artifacts required.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest

SRC = Path(__file__).resolve().parents[2]
JAVA = SRC / "main/java/org/telegram"


def method(source, signature):
    start = source.index(signature)
    end = source.index("{", start) + 1
    depth = 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


class EmojiFirstFrameFadeTest(unittest.TestCase):
    def source(self):
        receiver = (JAVA / "messenger/ImageReceiver.java").read_text()
        methods = [
            "public void markLoadingPlaceholderPresented(", "private void resetLoadingPresentation(",
            "private boolean isAnimatedDrawableReady(",
            "private boolean isDrawableReadyForDraw(",
            "public void setCrossfadeOnReady(", "private boolean canCrossfadeOnReady(",
            "private boolean canAnimateLoadingTransition(",
            "private void trackCrossfadeOnReady(", "private boolean prepareCrossfadeOnReady(",
            "private boolean prepareDrawAlpha(",
            "private void checkAlphaAnimation(",
            "public boolean drawWithoutLoadFade(",
            "public boolean draw(Canvas canvas, BackgroundThreadDrawHolder",
            "public BackgroundThreadDrawHolder setDrawInBackgroundThread(",
            "public static class BackgroundThreadDrawHolder",
            "protected boolean setImageBitmapByKey(", "public void recycleBitmap(",
            "public void clearImage(", "public void onDetachedFromWindow(",
            "public void setCurrentAccount(", "public Drawable getDrawable(",
            "public AnimatedFileDrawable getAnimation(", "public RLottieDrawable getLottieAnimation(",
            "public AnimatedEmojiDrawable getAnimatedEmojiDrawable(",
        ]
        code = "\n".join(method(receiver, s) for s in methods)
        emoji = (JAVA / "ui/Components/AnimatedEmojiDrawable.java").read_text()
        forum = (JAVA / "ui/Components/Forum/ForumUtilities.java").read_text()
        forum_start = forum.index("    public static void setTopicIcon(BackupImageView")
        forum_end = forum.index("    public static GeneralTopicDrawable", forum_start)
        template = (SRC / "test/fixtures/EmojiFirstFrameHarness.java.txt").read_text()
        return template.replace("/* RECEIVER */", code).replace(
            "/* PREVIEW_APPEARANCE */", method(receiver, "private static final class PreviewAppearance {")
        ).replace(
            "/* IDENTITY */", method(emoji, "public boolean isSameEmoji(")
        ).replace("/* FACTORY */", method(emoji, "private void createImageReceiver(")).replace(
            "/* CACHE_TYPES */", "\n".join(line for line in emoji.splitlines()
                                          if "public static final int CACHE_TYPE_" in line)
        ).replace("/* FORUM */", forum[forum_start:forum_end])

    def run_harness(self, source):
        with tempfile.TemporaryDirectory(prefix="emoji-first-frame-") as tmp:
            file = Path(tmp) / "EmojiFirstFrameHarness.java"
            file.write_text(source)
            compiled = subprocess.run(["javac", "-d", tmp, str(file)],
                                      capture_output=True, text=True, timeout=30)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            return subprocess.run(["java", "-ea", "-cp", tmp, "EmojiFirstFrameHarness"],
                                  capture_output=True, text=True, timeout=30)

    def test_production_receiver_and_forum_consumers(self):
        run = self.run_harness(self.source())
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("PASS: emoji first-frame pipeline", run.stdout)

    def test_negative_controls(self):
        source = self.source()
        for label, before, after in [
            ("cached undecoded object", "!memCache || !isAnimatedDrawableReady(drawable)", "!memCache"),
            ("premature progress", "manualAlphaAnimator || crossfadeOnReadyDrawable != null", "manualAlphaAnimator"),
            ("background readiness", "final boolean animationNotReady = prepareDrawAlpha(true);", "final boolean animationNotReady = false;"),
            ("forum wrong slot", "final Drawable current = imageReceiver.getDrawable();", "final Drawable current = imageReceiver.currentImageDrawable;"),
            ("UI publication race", "animationNotReady = preparedAnimationNotReady;", "animationNotReady = false;"),
            ("background publication race", "holder.animationNotReady = animationNotReady;", "holder.animationNotReady = false;"),
        ]:
            with self.subTest(label=label):
                self.assertEqual(source.count(before), 1)
                run = self.run_harness(source.replace(before, after))
                self.assertNotEqual(run.returncode, 0, "mutation unexpectedly passed")
                self.assertIn("AssertionError", run.stderr)
                if "publication race" in label:
                    self.assertTrue("race snapshot must keep pending frame hidden" in run.stderr
                                    or "preview stays visible" in run.stderr)

    def test_opt_in_and_shared_status_ownership(self):
        emoji = (JAVA / "ui/Components/AnimatedEmojiDrawable.java").read_text()
        self.assertIn("imageReceiver.setCrossfadeOnReady(cacheType != CACHE_TYPE_RENDERING_VIDEO)", emoji)
        swap = method(emoji, "private void drawDrawable(Canvas canvas, Drawable drawable, float opacity, boolean crossfading)")
        self.assertNotIn("setCurrentAlpha", swap)
        self.assertIn("((AnimatedEmojiDrawable) drawable).draw(canvas, crossfading)", swap)
        self.assertIn("imageReceiver.drawWithoutLoadFade(canvas)", emoji)
        self.assertIn("finally", swap)


if __name__ == "__main__":
    unittest.main()
