"""Run the actual loading-to-topic transition on the JVM; Android drawing is checked separately."""
import shutil
import unittest
from pathlib import Path

from test_sender_infocard_transitions import run_java
from test_recording_composer_lifecycle import method

JAVA = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui"
TRANSITION = (JAVA / "Components/Forum/ForumTopicPreviewTransition.java").read_text()
CELL = (JAVA / "Cells/DialogCell.java").read_text()


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
class ForumTopicPreviewTests(unittest.TestCase):
    def execute(self, body):
        production = TRANSITION.split("\n", 1)[1].replace("public final class", "static final class", 1)
        run_java("public class Transitions {\n" + production + r'''
 static void check(boolean value) { if (!value) throw new AssertionError(); }
 static void near(float a, float b) { check(Math.abs(a-b)<0.0001f); }
 public static void main(String[] args) {
''' + body + "\n}\n}")

    def test_cold_loading_resolves_without_an_empty_frame(self):
        self.execute(r'''
 ForumTopicPreviewTransition t = new ForumTopicPreviewTransition();
 check(!t.bind(0, -42, true, true));
 near(t.alpha(), 0); t.draw(1000); near(t.alpha(), 0);
 t.draw(1090); near(t.alpha(), .5f); t.draw(1180); near(t.alpha(), 1);
 check(!t.isAnimating());
 check(t.bind(0, -42, false, true));
 t.draw(1200); near(t.alpha(), 0); near(t.outgoingAlpha(), 1);
 t.draw(1350); near(t.alpha(), .5f); near(t.outgoingAlpha(), .5f);
 check(!t.bind(0, -42, false, true)); // layout/counter updates must not restart it
 t.draw(1500); near(t.alpha(), 1); near(t.outgoingAlpha(), 0);
 check(!t.isAnimating());
''')

    def test_fast_cached_response_keeps_current_placeholder_opacity(self):
        self.execute(r'''
 ForumTopicPreviewTransition t = new ForumTopicPreviewTransition();
 t.bind(1, -77, true, true); t.draw(1000); t.draw(1040);
 float displayed = t.alpha(); check(displayed > 0 && displayed < .2f);
 check(t.bind(1, -77, false, true)); t.draw(1050);
 near(t.outgoingAlpha(), displayed); // never flash a full-bright loading label
 for (int i=0; i<=36; i++) {
   t.draw(1050 + i * 300 / 36); // 120 Hz; no frame-count stepping
   check(t.alpha() >= 0 && t.alpha() <= 1);
   check(t.outgoingAlpha() <= displayed);
   check(t.alpha() + t.outgoingAlpha() >= displayed - .0001f);
 }
 near(t.alpha(), 1); check(!t.isAnimating());
''')

    def test_recycling_and_account_changes_never_keep_another_owner(self):
        self.execute(r'''
 ForumTopicPreviewTransition t = new ForumTopicPreviewTransition();
 t.bind(0, -42, true, true); t.draw(1000); t.draw(1180);
 check(!t.bind(1, -42, false, true)); near(t.alpha(), 1); near(t.outgoingAlpha(), 0);
 check(!t.isAnimating()); // warm cache on account switch needs no second fade
 t.bind(1, -42, true, true); t.draw(2000); t.draw(2180);
 check(!t.bind(1, -43, false, true)); near(t.alpha(), 1); near(t.outgoingAlpha(), 0);
 t.reset(); check(!t.bind(1, -43, false, true)); near(t.alpha(), 1);
''')

    def test_offscreen_disabled_motion_and_resolution_before_first_draw(self):
        self.execute(r'''
 ForumTopicPreviewTransition t = new ForumTopicPreviewTransition();
 t.bind(0, -42, true, true);
 check(!t.bind(0, -42, false, true)); // no old loading frame was ever drawn
 t.draw(0); near(t.alpha(), 0); near(t.outgoingAlpha(), 0);
 t.finish(); near(t.alpha(), 1); check(!t.isAnimating());
 t.bind(0, -42, true, false); near(t.alpha(), 1); check(!t.isAnimating());
 check(!t.bind(0, -42, false, false)); near(t.alpha(), 1);
 t.bind(0, -42, true, true); t.draw(1000); t.draw(1090);
 t.bind(0, -42, true, false); near(t.alpha(), 1); check(!t.isAnimating());
''')

    def test_render_wiring_and_cleanup(self):
        draw = method(CELL, "protected void onDraw(Canvas canvas)")
        self.assertIn("forumTopicPreviewTransition.draw(now)", draw)
        self.assertIn("outgoingForumLoadingLayout.draw(canvas)", draw)
        self.assertIn("canvas.restoreToCount(topicLayer)", draw)
        self.assertIn("canvas.restoreToCount(topicButtonLayer)", draw)
        for signature in ("protected void onDetachedFromWindow()", "public void setVisible(boolean visibleOnScreen)"):
            cleanup = method(CELL, signature)
            self.assertIn("forumTopicPreviewTransition.finish()", cleanup)
            self.assertIn("outgoingForumLoadingLayout = null", cleanup)
        build = method(CELL, "public void buildLayout()")
        self.assertIn("final StaticLayout previousTopicsLayout = messageLayout", build)
        self.assertIn("bind(currentAccount, currentDialogId", build)


if __name__ == "__main__":
    unittest.main()
