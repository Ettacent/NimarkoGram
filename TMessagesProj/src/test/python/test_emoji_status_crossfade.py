"""Execute the production swap drawable and AnimatedFloat on a deterministic JVM.

Only Android rendering/loading dependencies are faked; transition code is extracted
verbatim from production, so these tests do not test a duplicate state machine.
Run: python3 -B -m unittest discover -s TMessagesProj/src/test/python -p test_emoji_status_crossfade.py -v
"""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SRC = Path(__file__).resolve().parents[2]
COMPONENTS = SRC / "main/java/org/telegram/ui/Components"


def java_block(source, marker, start=0):
    begin = source.index(marker, start)
    opening = source.index("{", begin)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[begin:end]


class EmojiStatusCrossfadeTests(unittest.TestCase):
    def harness_source(self):
        self.assertIsNotNone(shutil.which("javac"), "JDK required for behavioral regressions")
        source = (COMPONENTS / "AnimatedEmojiDrawable.java").read_text()
        start = source.index("    public static class SwapAnimatedEmojiDrawable")
        end = source.index("\n    public static void updateAll()", start)
        animation = (COMPONENTS / "AnimatedFloat.java").read_text()
        animation = animation[animation.index("public class AnimatedFloat"):].replace(
            "public class AnimatedFloat", "class AnimatedFloat", 1
        )
        template = (SRC / "test/fixtures/SwapAnimatedEmojiDrawableHarness.java.txt").read_text()
        simple = (COMPONENTS.parent / "ActionBar/SimpleTextView.java").read_text()
        host_methods = "\n".join(java_block(simple, marker) for marker in (
            "private boolean isRightDrawableVisible(",
            "private boolean isRightDrawableInteractive(",
            "private int getScaledRightDrawableWidth(",
            "private int getRightDrawableSlotWidth(",
            "public int getRightDrawablesWidth(",
            "private void updateRightDrawableLayoutIfNeeded(",
            "public void invalidateDrawable(",
            "public boolean onTouchEvent(",
            "public boolean performAccessibilityAction(",
        )).replace("android.graphics.Rect", "Rect")
        draw_start = simple.index("protected void onDraw(")
        standard = "\n".join(java_block(simple,
            f"if (isRightDrawableVisible({name}) && !rightDrawableHidden && !rightDrawableOutside && !rightDrawableInside)", draw_start)
            for name in ("rightDrawable", "rightDrawable2"))
        outside = "\n".join(java_block(simple,
            f"if (isRightDrawableVisible({name}) && rightDrawableOutside)", draw_start)
            for name in ("rightDrawable", "rightDrawable2"))
        return template.replace("/* SWAP_SOURCE */", source[start:end]).replace(
            "/* FLOAT_SOURCE */", animation
        ).replace("/* SIMPLE_METHODS */", host_methods).replace(
            "/* SIMPLE_STANDARD_DRAW */", standard).replace("/* SIMPLE_OUTSIDE_DRAW */", outside)

    def run_harness(self, harness, *args):
        with tempfile.TemporaryDirectory(prefix="emoji-status-crossfade-") as directory:
            java_file = Path(directory) / "Harness.java"
            java_file.write_text(harness)
            compile_result = subprocess.run(
                ["javac", "-d", directory, str(java_file)], capture_output=True, text=True, timeout=30
            )
            self.assertEqual(compile_result.returncode, 0, compile_result.stderr)
            run = subprocess.run(
                ["java", "-ea", "-cp", directory, "Harness", *args], capture_output=True, text=True, timeout=30
            )
            return run

    def test_production_swap_state_machine(self):
        run = self.run_harness(self.harness_source())
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("PASS: 23 production swap scenarios", run.stdout)

    def test_negative_controls(self):
        harness = self.harness_source()
        mutations = [
            ("premature fade", "if (waitingForReady && isDrawableReady(drawable))",
             "if (waitingForReady)", "cold receiver waits indefinitely"),
            ("opaque incoming", "drawDrawable(canvas, drawable, progress, ownsReceiverFade",
             "drawDrawable(canvas, drawable, 1, ownsReceiverFade", "cold receiver waits indefinitely"),
            ("same-ID layout reset", "if (same) {", "if (same) { if (!animated) resetAnimation();",
             "same ID while waiting preserves outgoing"),
            ("recycler history leak", "changeProgress.set(1, true);\n            removeOldDrawable();",
             "changeProgress.set(1, true);", "document overload attaches and reset releases outgoing"),
        ]
        for name, before, after, expected_failure in mutations:
            with self.subTest(name=name):
                self.assertEqual(harness.count(before), 1, "mutation must target exactly one production site")
                run = self.run_harness(harness.replace(before, after, 1))
                self.assertNotEqual(run.returncode, 0, "negative control unexpectedly passed")
                self.assertIn(expected_failure, run.stderr)

    def test_ordered_pixel_compositing(self):
        run = self.run_harness(self.harness_source(), "pixels")
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("PASS: 9 pixel compositing scenarios", run.stdout)

    def test_source_over_pixel_negative_control(self):
        harness = self.harness_source()
        before = "new PorterDuffXfermode(PorterDuff.Mode.ADD)"
        self.assertEqual(harness.count(before), 1)
        run = self.run_harness(harness.replace(before, "new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)"), "pixels")
        self.assertNotEqual(run.returncode, 0)
        self.assertIn("identical opaque pixels do not dim", run.stderr)

    def test_nested_readiness(self):
        run = self.run_harness(self.harness_source(), "nested")
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("PASS: 3 nested readiness scenarios", run.stdout)

    def test_nested_readiness_negative_control(self):
        harness = self.harness_source()
        before = "return depth < 8 && isDrawableReady(((SwapAnimatedEmojiDrawable) drawable).drawable, depth + 1);"
        self.assertEqual(harness.count(before), 1)
        run = self.run_harness(harness.replace(before, "return true;"), "nested")
        self.assertNotEqual(run.returncode, 0)
        self.assertIn("nested wrapper waits for actual decoded badge", run.stderr)

    def test_simple_text_outgoing_visibility(self):
        run = self.run_harness(self.harness_source(), "visibility")
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("PASS: 5 host visibility scenarios", run.stdout)

    def test_final_retirement_layout_negative_control(self):
        harness = self.harness_source()
        before = "// Notify hosts after outgoing content stops reserving a slot.\n                invalidate();"
        self.assertEqual(harness.count(before), 1)
        run = self.run_harness(harness.replace(before, "// omitted post-retirement invalidation"), "visibility")
        self.assertNotEqual(run.returncode, 0)
        self.assertIn("final retirement requests one layout and no per-frame layouts", run.stderr)

    def test_simple_text_visibility_negative_control(self):
        harness = self.harness_source()
        before = "((AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable) drawable).hasRenderableContent()"
        self.assertEqual(harness.count(before), 1)
        run = self.run_harness(harness.replace(before,
            "!((AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable) drawable).isEmpty()"), "visibility")
        self.assertNotEqual(run.returncode, 0)
        self.assertIn("outgoing icon remains drawn until fade completion", run.stderr)


if __name__ == "__main__":
    unittest.main()
