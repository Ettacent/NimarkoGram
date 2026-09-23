import unittest
from pathlib import Path

JAVA = Path(__file__).resolve().parents[2] / "main/java"


class DialogTypingFadeTests(unittest.TestCase):
    def test_text_and_indicator_share_alpha_layer(self):
        source = (JAVA / "org/telegram/ui/Cells/DialogCell.java").read_text()
        block = source.split("final int typingLayer =", 1)[1].split("canvas.restoreToCount(typingLayer)", 1)[0]
        self.assertIn("typingLayout.draw(canvas)", block)
        self.assertIn("statusDrawable.draw(canvas)", block)
        self.assertIn("statusDrawable.setColor(color)", block)
        self.assertNotIn("setAlpha(", block)
        self.assertIn("updateHelper.typingProgres < 1f", block)

    def test_send_hints_disabled_by_default_without_overwriting_choice(self):
        source = (JAVA / "app/nimarkogram/messenger/NimarkoConfig.java").read_text()
        self.assertIn('getBoolean("disableSendHints", true)', source)


if __name__ == "__main__":
    unittest.main()
