"""Guard first-frame presentation while forum rows are still transparent."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui"


class ForumOpeningEmojiPresentationTests(unittest.TestCase):
    def test_hidden_transition_row_does_not_consume_ready_fade(self):
        source = (ROOT / "TopicsFragment.java").read_text()
        self.assertIn("if (isTopicIconPresented()) {\n                        animatedEmojiDrawable.draw(canvas);", source)
        method = source[source.index("private boolean isTopicIconPresented()"):]
        method = method[:method.index("\n        @Override")]
        self.assertIn("while (view != null)", method)
        self.assertIn("view.getAlpha() <= 0f", method)
        self.assertIn("view = parent instanceof View ? (View) parent : null", method)

    def test_document_delivery_is_not_held_until_forum_animation_ends(self):
        source = (ROOT / "Components/AnimatedEmojiDrawable.java").read_text()
        method = source[source.index("private void processDatabaseResult("):]
        method = method[:method.index("private void deliverDocuments(")]
        self.assertIn("AndroidUtilities.runOnUIThread(() -> processDocumentsAndLoadMore", method)
        self.assertNotIn("doOnIdle", method)
        self.assertNotIn("loadOpeningVisibleEmojiDocuments", (ROOT / "TopicsFragment.java").read_text())


if __name__ == "__main__":
    unittest.main()
