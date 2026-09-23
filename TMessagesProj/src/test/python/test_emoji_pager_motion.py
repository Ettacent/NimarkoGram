"""Production pager/keyboard methods on a deterministic Android boundary.

No APK/Gradle build. AndroidX physics/rendering are NOT simulated as device proof;
the fake ViewPager models its selection/settling contract. Also check the bridge
against the installed AndroidX ABI. Mutations must fail behavioral assertions.
Run: python3 -B -m unittest discover -s TMessagesProj/src/test/python -p test_emoji_pager_motion.py -v
"""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SRC = Path(__file__).resolve().parents[2]
COMPONENTS = SRC / "main/java/org/telegram/ui/Components"
EMOJI = (COMPONENTS / "EmojiView.java").read_text()
TABS = (COMPONENTS / "PagerSlidingTabStrip.java").read_text()
BRIDGE = (SRC / "main/java/androidx/viewpager/widget/EmojiViewPager.java").read_text()


def block(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 1
    i = opening + 1
    while depth:
        depth += (source[i] == "{") - (source[i] == "}")
        i += 1
    return source[start:i]


class EmojiPagerMotionTests(unittest.TestCase):
    def harness(self):
        pager = EMOJI[EMOJI.index("pager = new EmojiViewPager(context)"):]
        bridge = BRIDGE[BRIDGE.index("public abstract class EmojiViewPager"):]
        template = (SRC / "test/fixtures/EmojiPagerMotionHarness.java.txt").read_text()
        methods = "\n".join(block(EMOJI, signature) for signature in (
            "private int getPageType(", "private boolean isPageTypeVisible(",
            "private void checkGridVisibility("))
        clicks = []
        for signature in ("private void addIconTab(", "private void addTab("):
            click = block(block(TABS, signature), "tab.setOnClickListener(v ->")
            clicks.append(click[click.index("{") + 1:-1])
        return (template.replace("/* BRIDGE */", bridge.replace("public abstract class", "static abstract class", 1))
                .replace("/* POLICY */", block(pager, "public boolean isPageMotionEnabled("))
                .replace("/* SELECT */", block(pager, "public void setCurrentItem(int item, boolean smoothScroll)"))
                .replace("/* GRIDS */", methods)
                .replace("/* TEXT_CLICK */", clicks[1]).replace("/* ICON_CLICK */", clicks[0]))

    def run_harness(self, source):
        self.assertIsNotNone(shutil.which("javac"), "JDK is required for the isolated test harness")
        with tempfile.TemporaryDirectory(prefix="emoji-pager-motion-") as directory:
            java = Path(directory) / "Harness.java"
            java.write_text(source)
            compiled = subprocess.run(["javac", "-d", directory, str(java)], capture_output=True, text=True, timeout=30)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            return subprocess.run(["java", "-ea", "-cp", directory, "Harness"],
                                  capture_output=True, text=True, timeout=30)

    def test_production_transition_paths(self):
        result = self.run_harness(self.harness())
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("PASS emoji pager", result.stdout)

    def test_negative_controls(self):
        source = self.harness()
        mutations = (
            ("snap tap", "smoothScroll && isTabAnimationEnabled()", "false", "tap must animate"),
            ("collapsed layout", "? VISIBLE : INVISIBLE", "? VISIBLE : GONE", "retains layout"),
            ("ignore motion policy", "smoothScroll &= !finishingPageTransition && isPageMotionEnabled();",
             "smoothScroll &= !finishingPageTransition;", "reduced swipe"),
            ("cancel bypasses policy", "if (!finishingPageTransition && isPageMotionEnabled())",
             "if (!finishingPageTransition)", "cancel leaves no dragging state"),
            ("repeat target scrolls grid", "if (isPageTransitionRunning()) {", "if (false) {", "repeat target"),
            ("hidden scroll survives", "if (visibility != VISIBLE)", "if (false)", "hide settles"),
            ("settings changed during settle", "pageScrollState == SCROLL_STATE_SETTLING && !isPageMotionEnabled()",
             "false", "live setting settles"),
            ("detach no cleanup", "finishPageTransition();\n        super.onDetachedFromWindow();",
             "super.onDetachedFromWindow();", "detach settles"),
        )
        for name, before, after, expected in mutations:
            with self.subTest(name=name):
                self.assertIn(before, source)
                result = self.run_harness(source.replace(before, after))
                self.assertNotEqual(result.returncode, 0, "negative control unexpectedly passed")
                self.assertIn(expected, result.stderr)

    def test_integration_and_negative_scope_controls(self):
        self.assertIn("pager.setOffscreenPageLimit(2)", EMOJI)
        self.assertIn("typeTabs.setSmoothScroll(true)", EMOJI)
        self.assertIn("private boolean smoothScroll;", TABS)  # opt-in, old consumers unchanged
        self.assertEqual(TABS.count("pager.setCurrentItem(position, smoothScroll && isTabAnimationEnabled())"), 2)
        self.assertIn("lineLeftAnimated.set(lineLeft, smoothScroll || !isTabAnimationEnabled())", TABS)
        self.assertIn("lineRightAnimated.set(lineRight, smoothScroll || !isTabAnimationEnabled())", TABS)
        self.assertIn("1f - Math.abs(i - currentPosition - currentPositionOffset)", TABS)
        allow = block(EMOJI, "public void setAllow(boolean allowEmoji,")
        self.assertLess(allow.index("pager.finishPageTransition()"), allow.index("currentTabs.clear()"))
        self.assertIn("pager.finishPageTransition()", block(EMOJI, "public void onDestroy()"))
        self.assertIn("getPageType(pager.getCurrentItem())", block(EMOJI, "private void saveNewPage()"))
        playback = block(EMOJI, "private void startStopVisibleGifs(")
        self.assertIn("isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE", playback)
        bridge_code = re.sub(r"/\*.*?\*/|//[^\n]*", "", BRIDGE, flags=re.S)
        for forbidden in ("postDelayed", "Bitmap", "getDeclaredField", "new Scroller", "ValueAnimator"):
            self.assertNotIn(forbidden, bridge_code)

    def test_installed_viewpager_bridge_abi(self):
        cache = Path.home() / ".gradle" / "caches"
        jars = list(cache.glob("*/transforms/*/transformed/viewpager-1.0.0-runtime.jar"))
        if not jars:
            self.skipTest("installed AndroidX 1.0.0 cache unavailable")
        result = subprocess.run(["javap", "-classpath", str(jars[0]), "androidx.viewpager.widget.ViewPager"],
                                capture_output=True, text=True, timeout=15, check=True)
        self.assertIn("void setCurrentItemInternal(int, boolean, boolean, int);", result.stdout)
        self.assertIn("void setScrollState(int);", result.stdout)
        self.assertIn("void smoothScrollTo(int, int, int);", result.stdout)
        self.assertNotIn("final void setCurrentItemInternal", result.stdout)
        self.assertNotIn("final void setScrollState", result.stdout)


if __name__ == "__main__":
    unittest.main()
