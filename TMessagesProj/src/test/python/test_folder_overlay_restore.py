"""Regression: cached folders must not be clipped using an unlaid-out info-card sibling."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

JAVA = Path(__file__).resolve().parents[2] / "main/java"


class FolderOverlayRestoreTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac"), "JDK required")
    def test_production_edge_and_invalidation(self):
        source = (JAVA / "org/telegram/ui/Components/FilterTabsView.java").read_text()
        start = source.index("public int getTrailingOverlayEdge()")
        end = source.index("public void setTrailingOverlayInset", start)
        edge = source[start:end]
        start = end
        end = source.index("int trailing = listViewPaddingH", start)
        setter = source[start:end] + "}\n"
        harness = '''public class FolderOverlayHarness {
          int width, trailingOverlayInset, invalidations;
          boolean trailingOverlayRtl;
          int getWidth() { return width; }
          void invalidate() { invalidations++; }
          EDGE
          SETTER
          static void check(boolean ok) { if (!ok) throw new AssertionError(); }
          public static void main(String[] args) {
            FolderOverlayHarness v = new FolderOverlayHarness();
            // Log geometry: 1080 px window, 1056 px strip, 255 px card, 24 px gap.
            v.setTrailingOverlayInset(279, false);
            check(v.getTrailingOverlayEdge() == 0); // not laid out yet
            v.width = 1056;
            check(v.getTrailingOverlayEdge() == 777); // sibling may still have x=0
            int before = v.invalidations;
            v.setTrailingOverlayInset(279, false);
            check(v.invalidations == before); // no per-frame redraw loop
            v.setTrailingOverlayInset(240, false);
            check(v.invalidations == before + 1 && v.getTrailingOverlayEdge() == 816);
            v.setTrailingOverlayInset(279, true);
            check(v.getTrailingOverlayEdge() == 279);
            v.width = 200;
            check(v.getTrailingOverlayEdge() == 200);
            v.setTrailingOverlayInset(-1, false);
            check(v.getTrailingOverlayEdge() == 200);
            FolderOverlayHarness recreated = new FolderOverlayHarness();
            recreated.width = 1056;
            recreated.setTrailingOverlayInset(279, false);
            check(recreated.getTrailingOverlayEdge() == 777);
          }
        }'''.replace("EDGE", edge).replace("SETTER", setter)
        with tempfile.TemporaryDirectory(prefix="folder-overlay-") as folder:
            path = Path(folder) / "FolderOverlayHarness.java"
            path.write_text(harness)
            result = subprocess.run(["javac", str(path)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(["java", "-cp", folder, "FolderOverlayHarness"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_drawing_and_touch_share_measured_edge(self):
        source = (JAVA / "org/telegram/ui/DialogsActivity.java").read_text()
        start = source.index("filterTabsView = new FilterTabsView")
        end = source.index("filterTabsView.setVisibility(View.GONE)", start)
        view = source[start:end]
        self.assertEqual(view.count("getTrailingOverlayEdge()"), 2)
        self.assertNotIn("homeInfoCards.getX()", view)


if __name__ == "__main__":
    unittest.main()
