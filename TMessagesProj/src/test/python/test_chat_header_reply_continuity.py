"""Focused source regressions for chat status, reply thumbnails, and header churn."""

from pathlib import Path
import unittest


UI = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui"
CELL = (UI / "Cells/ChatMessageCell.java").read_text()
HEADER = (UI / "Components/ChatAvatarContainer.java").read_text()


class ChatHeaderReplyContinuityTests(unittest.TestCase):
    def test_reply_media_override_never_uses_original_reply_thumb(self):
        branch = CELL.split("reply_to.reply_media != null) {", 1)[1].split(
            "if (photoSize == null) {", 1
        )[0]
        self.assertIn("reply_media.document.thumbs, 320", branch)
        self.assertIn("reply_media.document.thumbs, 40", branch)
        self.assertIn("reply_media.photo.sizes, 320", branch)
        self.assertIn("reply_media.photo.sizes, 40", branch)
        self.assertIn("if (thumbPhotoSize == photoSize) thumbPhotoSize = null;", branch)
        self.assertIn("size = 0;", branch)

    def test_private_status_restores_after_preference_hide(self):
        method = HEADER.split("public void updateSubtitle(boolean animated)", 1)[1].split(
            "public static CharSequence getChatSubtitle", 1
        )[0]
        self.assertIn("chat != null || subtitleHiddenByPreference", method)
        self.assertLess(
            method.index("subtitleHiddenByPreference = false;"),
            method.index("subtitle.setVisibility(VISIBLE);"),
        )
        hide = method.split("if (app.nimarkogram.messenger.NimarkoConfig.hideActionBarStatus)", 1)[1]
        self.assertIn(".finishCrossfade()", hide)
        self.assertIn(".resetPresentation()", hide)

    def test_verified_badge_reuses_drawable_and_unchanged_title_skips_layout(self):
        method = HEADER.split("public void setTitle(CharSequence value, boolean scam", 1)[1].split(
            "private void applyNimarkoBadge", 1
        )[0]
        self.assertIn("if (verifiedDrawable == null)", method)
        self.assertIn("titleTextView.setRightDrawable2(verifiedDrawable)", method)
        self.assertIn("if (titleChanged || previousRightDrawable != titleTextView.getRightDrawable()", method)
        self.assertLess(method.index("if (titleChanged ||"), method.index("requestLayout();"))

if __name__ == "__main__":
    unittest.main()
