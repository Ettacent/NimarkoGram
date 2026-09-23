"""Guard single ownership of the organizer tab background during popup transitions."""
import unittest
from pathlib import Path

JAVA = Path(__file__).resolve().parents[2] / "main/java"


class OrganizerPopupRenderingTests(unittest.TestCase):
    def test_all_three_tab_menus_leave_anchor_rendering_to_organizer(self):
        source = (JAVA / "org/telegram/ui/MainTabsActivity.java").read_text()
        for name in ("openCallsSelector", "openFoldersSelector", "openAccountSelector"):
            start = source.index("boolean " + name + "(")
            end = source.index("o.show();", start)
            body = source[start:end]
            self.assertIn(".setDrawScrim(false)", body, name)
            self.assertNotIn("setScrimViewBackground", body, name)

    def test_blur_capture_does_not_hide_non_duplicated_anchor(self):
        source = (JAVA / "org/telegram/ui/Components/ItemOptions.java").read_text()
        start = source.index("public DimView(Context context)")
        end = source.index("protected void onSizeChanged", start)
        body = source[start:end]
        self.assertIn("hideAnchorForCapture = drawScrim && scrimView != null", body)
        self.assertIn("if (hideAnchorForCapture) {", body)
        self.assertIn("scrimView.setAlpha(0.0f)", body)
        self.assertIn("if (hideAnchorForCapture) scrimView.setAlpha(anchorAlpha)", body)
        self.assertIn("restoreBlurAnchor();", body)
        self.assertNotIn("scrimView.setAlpha(1.0f)", body)


if __name__ == "__main__":
    unittest.main()
