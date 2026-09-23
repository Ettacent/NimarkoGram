"""Consumer wiring contracts; decoder behavior is covered by first-frame tests.

These checks do not assert device rendering or frame timing.
"""
from pathlib import Path
import unittest

from test_in_app_notification_lifecycle import method

JAVA = Path(__file__).resolve().parents[2] / "main/java"


class MediaConsumersAndNotificationExit(unittest.TestCase):
    def test_media_consumers_opt_in_before_loading(self):
        consumers = {
            "Cells/StickerEmojiCell.java": "imageView",
            "Cells/StickerCell.java": "imageView.getImageReceiver()",
            "Cells/StickerSetCell.java": "imageView.getImageReceiver()",
            "Cells/FeaturedStickerSetCell.java": "imageView.getImageReceiver()",
            "Cells/ContextLinkCell.java": "linkImageView",
            "Cells/SharedDocumentCell.java": "thumbImageView.getImageReceiver()",
            "Cells/SharedPhotoVideoCell2.java": "imageReceiver",
            "Components/Reactions/ReactionsLayoutInBubble.java": "imageReceiver",
        }
        for file, receiver in consumers.items():
            with self.subTest(file=file):
                text = (JAVA / "org/telegram/ui" / file).read_text()
                self.assertIn(receiver + ".setCrossfadeOnReady(true);", text)

    def test_tap_releases_height_on_the_fade_clock(self):
        text = (JAVA / "app/nimarkogram/messenger/notifications/NimarkoInAppNotifications.java").read_text()
        tap = method(text, "void animateOpenChat()")
        settle = method(text, "void settleGeometry(")
        self.assertIn("settleGeometry(expansion, -getHeight(), 220,", tap)
        self.assertNotIn("withEndAction", tap)
        self.assertLess(tap.index("account != UserConfig.selectedAccount"),
                        tap.index("settleGeometry("))
        self.assertIn("if (closing || opening) setAlpha(fromAlpha * (1f - progress))", settle)
        self.assertIn("setPullOffset(fromOffset + (targetOffset - fromOffset) * progress)", settle)
        self.assertIn("if (pullAnimator != animation) return", settle)


if __name__ == "__main__":
    unittest.main()
