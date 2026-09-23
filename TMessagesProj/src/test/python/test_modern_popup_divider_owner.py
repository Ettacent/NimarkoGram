"""Guard against rendering modern-menu separators outside their page."""
from pathlib import Path
import unittest

JAVA = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui"


class ModernPopupDividerOwner(unittest.TestCase):
    def test_modern_gap_is_not_suppressed_in_page(self):
        source = (JAVA / "ActionBar/ActionBarPopupWindow.java").read_text()
        self.assertIn("if (child instanceof GapView && backgroundDrawable != null && popupOverlayDrawable == null)", source)

    def test_detached_separator_pass_is_legacy_only(self):
        source = (JAVA / "ActionBar/ActionBarPopupWindow.java").read_text()
        start = source.index("if (hasGap && popupOverlayDrawable == null)")
        end = source.index("canvas.restoreToCount(saveCount)", start)
        self.assertIn("child.draw(canvas)", source[start:end])

    def test_page_has_foreground_clip_and_visibility(self):
        source = (JAVA / "Components/PopupSwipeBackLayout.java").read_text()
        self.assertIn("canvas.clipRect(0f, 0f, foregroundEdge, getHeight())", source)
        self.assertIn("invalidateVisibility();", source)


if __name__ == "__main__":
    unittest.main()
