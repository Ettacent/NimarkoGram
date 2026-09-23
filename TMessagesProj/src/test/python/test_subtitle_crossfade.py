"""Source regressions for the shared chat/forum subtitle transition."""
import unittest
from pathlib import Path

JAVA = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui"
SOURCE = (JAVA / "Components/ChatAvatarContainer.java").read_text()


class SubtitleCrossfadeTests(unittest.TestCase):
    def test_no_sequential_fade_out_swap(self):
        method = SOURCE.split("private void setSubtitleTextSmooth", 1)[1].split(
            "public ImageView getTimeItem", 1)[0]
        self.assertNotIn(".alpha(0f)", method)
        self.assertLess(method.index("view.captureSubtitle()"), method.index("view.setText(target)"))
        self.assertLess(method.index("view.setText(target)"), method.index("view.startCrossfade("))

    def test_both_layers_use_same_progress(self):
        self.assertIn("Math.round(255 * progress)", SOURCE)
        self.assertIn("fadePaint.setAlpha(255 - incomingAlpha)", SOURCE)
        self.assertIn("incomingPaint.setAlpha(incomingAlpha)", SOURCE)
        self.assertIn("crossfade.setDuration(300)", SOURCE)

    def test_interruption_captures_visible_blend_before_cancelling(self):
        method = SOURCE.split("void captureSubtitle()", 1)[1].split("void startCrossfade", 1)[0]
        capture = method.split("Bitmap snapshot = null;", 1)[1]
        self.assertLess(capture.index("draw(snapshotCanvas)"), capture.index("finishCrossfade()"))

    def test_detach_cleans_up_animation(self):
        method = SOURCE.split("protected void onDetachedFromWindow()", 1)[1]
        self.assertIn("((SubtitleTextView) subtitleTextView).finishCrossfade()", method)

    def test_forum_member_counts_use_shared_transition(self):
        topics = (JAVA / "TopicsFragment.java").read_text()
        self.assertIn("avatarContainer.setSubtitle(newSubtitle)", topics)
        self.assertIn('formatPluralString("Members", chatFull.participants_count)', topics)


if __name__ == "__main__":
    unittest.main()
