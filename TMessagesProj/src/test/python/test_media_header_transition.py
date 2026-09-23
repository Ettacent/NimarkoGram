"""Regression guards for the 370px header strip observed in MediaReturn logs."""
import unittest
from pathlib import Path

JAVA = Path(__file__).resolve().parents[2] / "main/java/org/telegram"


class MediaHeaderTransitionTests(unittest.TestCase):
    def test_recorded_transition_and_retained_strip_meet(self):
        header_boundary = 549
        cell_y = 164
        image_y = 15
        image_height = 555
        strip_height = header_boundary - cell_y - image_y
        self.assertEqual(strip_height, 370)
        self.assertEqual(strip_height + (image_height - strip_height), image_height)
        for boundary in (-100, 0, 180, 549, 1000):
            retained = max(0, min(image_height, boundary - cell_y - image_y))
            animated = max(0, image_height - retained)
            self.assertEqual(retained + animated, image_height)

    def test_remnant_is_clipped_and_does_not_advance_video_twice(self):
        source = (JAVA / "ui/Cells/ChatMessageCell.java").read_text()
        method = source.split("protected boolean drawPhotoImage(", 1)[1].split("private boolean drawPhotoImageInternal(", 1)[0]
        self.assertIn("delegate.getPhotoViewerClipTop() - getY() - getPaddingTop()", method)
        self.assertIn("canvas.clipRect(0, 0, getWidth(), Math.min(getHeight(), clipTop))", method)
        self.assertIn("photoImage.setSkipUpdateFrame(true)", method)
        self.assertIn("canvas.restoreToCount(save)", method)
        for guard in ("!photoImage.getVisible()", "PhotoViewer.isShowingImage(currentMessageObject)",
                      "!currentMessageObject.hasMediaSpoilers()", "!currentMessageObject.needDrawBluredPreview()",
                      "!SecretMediaViewer.getInstance().isShowingImage(currentMessageObject)",
                      "!StoryViewer.isShowingImage(currentMessageObject)"):
            self.assertIn(guard, method)

    def test_visibility_override_is_scoped(self):
        source = (JAVA / "messenger/ImageReceiver.java").read_text()
        method = source.split("public boolean drawIgnoringVisibility(", 1)[1].split("public boolean draw(Canvas canvas,", 1)[0]
        self.assertIn("finally", method)
        self.assertIn("isVisible = wasVisible", method)
        self.assertNotIn("setVisible(", method)

    def test_same_header_boundary_as_photo_viewer(self):
        source = (JAVA / "ui/ChatActivity.java").read_text()
        self.assertIn("return chatListViewPaddingTop - chatListViewPaddingVisibleOffset - dp(4);", source)

    def test_bottom_strip_respects_input_and_keyboard(self):
        chat = (JAVA / "ui/ChatActivity.java").read_text()
        method = chat.split("public float getPhotoViewerClipBottom()", 1)[1].split("@Override", 1)[0]
        for token in ("chatListView.getHeight()", "getAnimatedMaxBottomInset()",
                      "TopicsTabsView.Position.BOTTOM", "inputIslandHeightCurrent"):
            self.assertIn(token, method)
        cell = (JAVA / "ui/Cells/ChatMessageCell.java").read_text()
        self.assertIn("clipBottom = Math.max(clipTop, clipBottom)", cell)
        self.assertIn("canvas.clipRect(0, Math.max(0f, clipBottom), getWidth(), getHeight())", cell)


if __name__ == "__main__":
    unittest.main()
