"""Focused source regressions for the card/inline-folder fixes; no APK or JVM build."""

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2] / "main/java"
STRIP = (ROOT / "app/nimarkogram/messenger/infocards/InfoCardStripView.java").read_text()
BASE = (ROOT / "app/nimarkogram/messenger/infocards/BaseInfoCard.java").read_text()
CACHE = (ROOT / "app/nimarkogram/messenger/infocards/CacheCard.java").read_text()
PROXY = (ROOT / "app/nimarkogram/messenger/infocards/ProxyCard.java").read_text()
TABS = (ROOT / "org/telegram/ui/Components/FilterTabsView.java").read_text()


def body(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 0
    for token in re.finditer(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|[{}]', source[opening:]):
        if token[0] == "{":
            depth += 1
        elif token[0] == "}":
            depth -= 1
            if depth == 0:
                return source[opening + 1:opening + token.start()]
    raise AssertionError(f"unclosed method: {signature}")


class InfoCardBoundedAuditTests(unittest.TestCase):
    def test_first_drag_move_keeps_down_anchor_and_direction(self):
        touch = body(STRIP, "public boolean onTouchEvent(")
        begin = body(STRIP, "private void beginDrag(")
        self.assertIn("downY = ev.getY();", touch)
        self.assertIn("downX = ev.getX();", touch)
        self.assertIn("beginDrag();", touch)
        self.assertIn("float dy = ev.getY() - downY;", touch)
        self.assertLess(touch.index("beginDrag();"), touch.index("float dy = ev.getY() - downY;"))
        self.assertNotIn("downY =", begin)
        self.assertNotIn("downX =", begin)
        self.assertIn("potentialTap = false;", begin)
        # At the first slop crossing, the old rebase yielded zero for both directions.
        for down, move, expected_up in ((120, 95, True), (120, 145, False)):
            self.assertEqual(move - down < 0, expected_up)
            self.assertGreater(abs(move - down) / 28, 0)

    def test_single_card_cannot_wrap_or_commit_to_itself(self):
        neighbor = body(STRIP, "private int neighbor(")
        self.assertLess(neighbor.index("if (n < 2) return -1;"),
                        neighbor.index("InfoCardsConfig.isInfiniteScrolling()"))
        self.assertIn("if (next < 0 || next == currentIndex) return false;",
                      body(STRIP, "private boolean moveFromAccessibility("))
        self.assertIn("pages > 0 && count > 1", body(STRIP, "public boolean onTouchEvent("))

    def test_continuous_wrap_and_finite_edge_arithmetic(self):
        touch = body(STRIP, "public boolean onTouchEvent(")
        neighbor = body(STRIP, "private int neighbor(")
        self.assertIn("return inf ? n - 1 : -1;", neighbor)
        self.assertIn("return inf ? 0 : -1;", neighbor)
        self.assertIn("int availablePages = nb < 0 ? 0 : 1 + (dragUp ? count - 1 - nb : nb);", touch)
        self.assertIn("int step = (pages - 1) % count;", touch)
        self.assertIn("int next = (nb + (dragUp ? step : count - step)) % count;", touch)
        for count in range(2, 6):
            for current in range(count):
                for up in (False, True):
                    for pages in range(1, 3 * count + 1):
                        delta = 1 if up else -1
                        for infinite in (False, True):
                            neighbor_index = current + delta
                            if not 0 <= neighbor_index < count:
                                neighbor_index = neighbor_index % count if infinite else -1
                            available = (0 if neighbor_index < 0 else
                                         1 + (count - 1 - neighbor_index if up else neighbor_index))
                            consumed = pages if infinite else min(pages, available)
                            if consumed == 0:
                                self.assertEqual(neighbor_index, -1)
                                continue
                            step = (consumed - 1) % count
                            next_index = (neighbor_index + (step if up else count - step)) % count
                            expected = ((current + delta * pages) % count if infinite else
                                        current + delta * consumed)
                            self.assertEqual(next_index, expected)

    def test_long_press_does_not_fall_through_to_proxy_or_cache_tap(self):
        timer = STRIP[STRIP.index("private final Runnable longPressRunnable"):
                      STRIP.index("private float visibilityFactor")]
        self.assertLess(timer.index("longPressFired = true;"), timer.index("cur.onCardLongClicked()"))
        self.assertLess(timer.index("potentialTap = false;"), timer.index("cur.onCardLongClicked()"))
        self.assertIn("if (cur.onCardLongClicked())", timer)
        self.assertIn("potentialTap && !dragging && !longPressFired",
                      body(STRIP, "public boolean onTouchEvent("))
        self.assertNotIn("onCardLongClicked()", CACHE)  # inherits guarded Settings menu
        self.assertNotIn("onCardLongClicked()", PROXY)  # inherits guarded Settings menu
        menu = body(BASE, "public boolean onCardLongClicked()")
        self.assertIn("getVisibleMenuFragment()", menu)
        self.assertIn("return options.isShown();", menu)

    def test_content_and_carousel_clipping_share_live_bounds(self):
        draw = body(STRIP, "protected void dispatchDraw(")
        motion = body(STRIP, "private void applyCarouselTransforms(")
        self.assertIn("canvas.clipRect(0, 0, getWidth(), getHeight());", draw)
        self.assertIn("getWidth() / (float) Math.max(1, cur.getWidth())", motion)
        self.assertIn("getWidth() / (float) Math.max(1, in.getWidth())", motion)
        self.assertIn("content.setClipToOutline(true);", BASE)
        self.assertIn("return Math.min(want, available);",
                      body(BASE, "private int animatedWidth("))

    def test_inline_folder_scroll_cannot_replay_after_exit_or_rtl_flip(self):
        inset = body(TABS, "public void setTrailingOverlayInset(")
        reference = body(TABS, "public void setResizeReferenceWidth(")
        scroll = body(TABS, "private void scrollWithPage(")
        replay = body(TABS, "protected void onLayout(boolean changed, int l, int t, int r, int b) {")
        self.assertIn("if (directionChanged)", inset)
        self.assertIn("pageScrollFrom = pageScrollTo = -1;", inset)
        self.assertIn("pendingPageScrollTab = null;", inset)
        self.assertIn("if (resizeReferenceWidth > 0 && width <= 0)", reference)
        self.assertIn("pageScrollFrom = pageScrollTo = -1;", reference)
        self.assertIn("pendingPageScrollTab = null;", reference)
        self.assertRegex(scroll, r"^\s*if \(resizeReferenceWidth <= 0\) return;")
        self.assertIn("scrollWithPage(position, progress)", replay)


if __name__ == "__main__":
    unittest.main()
