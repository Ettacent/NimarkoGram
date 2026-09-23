"""Bounded regressions for photo-return glass and search empty-state handoff."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


UI = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui"


def block(source, signature):
    start = source.index(signature)
    pos = source.index("{", start) + 1
    depth = 1
    while depth:
        depth += (source[pos] == "{") - (source[pos] == "}")
        pos += 1
    return source[start:pos]


class PhotoReturnSearchMotionTests(unittest.TestCase):
    def test_glass_capture_restores_scope_and_retains_photo_pixels(self):
        chat = (UI / "ChatActivity.java").read_text()
        capture = block(chat, "private void drawListImpl(Canvas blurCanvas, RectF position)")
        self.assertIn("boolean previousCapture = ChatMessageCell.drawingGlassBackdrop", capture)
        self.assertIn("finally", capture)
        self.assertIn("ChatMessageCell.drawingGlassBackdrop = previousCapture", capture)
        cell = (UI / "Cells/ChatMessageCell.java").read_text()
        photo = block(cell, "protected boolean drawPhotoImage(Canvas canvas)")
        # Hidden source restoration is restricted to public, non-spoiler media.
        self.assertLess(photo.index("!currentMessageObject.hasMediaSpoilers()"),
                        photo.index("if (drawingGlassBackdrop)"))
        self.assertIn("return drawn | photoImage.drawIgnoringVisibility(canvas)", photo)
        self.assertIn("photoImage.setAlpha(oldAlpha)", photo)
        self.assertIn("photoViewerVisible != wasPhotoViewerVisible", chat)

    def test_photo_return_requests_capture_without_hiding_material(self):
        chat = (UI / "ChatActivity.java").read_text()
        refresh = block(chat, "private void refreshGlassAfterPhotoViewerClose()")
        self.assertIn("BLUR_INVALIDATE_FLAG_SCROLL | BLUR_INVALIDATE_FLAG_POSITIONS", refresh)
        self.assertIn("invalidateAllGlassAttachedViews()", refresh)
        self.assertNotIn("setGlassAlpha", refresh)
        self.assertNotIn("ValueAnimator", refresh)

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_search_empty_reveal_discards_stale_callbacks(self):
        source = (UI / "Components/SearchViewPager.java").read_text()
        self.assertIn("return searchEmptyRevealReady && super.emptyViewIsVisible()", source)
        self.assertIn("itemAnimator.setRemoveDuration(180)", source)
        methods = "\n".join(block(source, signature) for signature in (
            "private void deferSearchEmptyReveal()",
            "private void cancelSearchEmptyReveal()",
        ))
        harness = """
public class Harness {
    static class SearchMotionTrace { static void event(String value) {} }
    final SearchMotionTrace searchMotionTrace = new SearchMotionTrace();
    static final long SEARCH_EMPTY_REVEAL_DELAY_MS = 110;
    static class ListView {
        Runnable pending; long delay; int checks; boolean attached = true;
        void postDelayed(Runnable runnable, long ms) { pending = runnable; delay = ms; }
        void removeCallbacks(Runnable runnable) { if (pending == runnable) pending = null; }
        boolean isAttachedToWindow() { return attached; }
        void checkIfEmpty() { checks++; }
    }
    ListView searchListView = new ListView();
    boolean searchEmptyRevealReady = true;
    Runnable searchEmptyRevealRunnable;
    int searchEmptyRevealGeneration;
""" + methods + """
    static void check(boolean valid) { if (!valid) throw new AssertionError(); }
    public static void main(String[] args) {
        Harness h = new Harness();
        h.deferSearchEmptyReveal();
        Runnable stale = h.searchEmptyRevealRunnable;
        check(!h.searchEmptyRevealReady && h.searchListView.delay == 110);
        h.deferSearchEmptyReveal();
        stale.run();
        check(!h.searchEmptyRevealReady && h.searchListView.checks == 0);
        Runnable canceled = h.searchEmptyRevealRunnable;
        h.cancelSearchEmptyReveal();
        canceled.run();
        check(h.searchEmptyRevealReady && h.searchListView.checks == 0);
        h.deferSearchEmptyReveal();
        h.searchEmptyRevealRunnable.run();
        check(h.searchEmptyRevealReady && h.searchListView.checks == 1);
        h.deferSearchEmptyReveal();
        h.searchListView.attached = false;
        h.searchEmptyRevealRunnable.run();
        check(h.searchEmptyRevealReady && h.searchListView.checks == 1);
    }
}
"""
        with tempfile.TemporaryDirectory(prefix="search-empty-handoff-") as tmp:
            java = Path(tmp) / "Harness.java"
            java.write_text(harness)
            compiled = subprocess.run(["javac", "-d", tmp, str(java)], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            run = subprocess.run(["java", "-cp", tmp, "Harness"], capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stderr)


if __name__ == "__main__":
    unittest.main()
