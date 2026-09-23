"""Exercise the actual fallback entrance methods without an Android build."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui/Components/SearchViewPager.java"


def block(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


class SearchFallbackEntranceTest(unittest.TestCase):
    def test_fallback_is_separate_from_diff_and_canceled_on_updates(self):
        source = SOURCE.read_text()
        refresh = block(source, "public void notifyDataSetChanged()")
        self.assertLess(refresh.index("cancelPendingSearchResultsEntrance();"), refresh.index("snapshotSearchRows(this)"))
        self.assertNotIn("cancelSearchResultsEntrance();", refresh)
        self.assertIn("previousRows != null && previousRows.isEmpty()", refresh)
        self.assertIn("MAX_SEARCH_SNAPSHOT_ROWS = 2048", source)
        snapshot = block(source, "private static ArrayList<SearchRow> snapshotSearchRows(")
        self.assertIn("count > MAX_SEARCH_SNAPSHOT_ROWS", snapshot)
        self.assertNotIn("MAX_ANIMATED_SEARCH_ROWS", snapshot)
        self.assertIn("previousRows.size() + nextRows.size() <= 512", refresh)
        self.assertEqual(refresh.count("scheduleSearchResultsEntrance();"), 1)
        self.assertIn("DiffUtil.calculateDiff", refresh)
        self.assertIn("cancelSearchResultsEntrance();", block(source, "private void invalidateSnapshot()"))
        self.assertIn("cancelSearchResultsEntrance();", block(source, "protected void onDetachedFromWindow()"))
        self.assertNotIn("animateAdd(", block(source, "public void runResultsEnterAnimation()"))
        self.assertIn("SEARCH_EMPTY_REVEAL_DELAY_MS = 110", source)

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_visible_bound_cancellation_and_animation_ownership(self):
        source = SOURCE.read_text()
        methods = "\n".join(block(source, s) for s in (
            "private void scheduleSearchResultsEntrance()",
            "private void cancelSearchResultsEntrance()",
            "private void cancelPendingSearchResultsEntrance()",
        ))
        harness = """
import java.util.ArrayList;
public class Harness {
    static class Trace { void event(String value) {} }
    final Trace searchMotionTrace = new Trace();
    static class SharedConfig {
        static boolean enabled = true;
        static boolean animationsEnabled() { return enabled; }
    }
    static class ViewTreeObserver {
        interface OnPreDrawListener { boolean onPreDraw(); }
        OnPreDrawListener listener;
        void addOnPreDrawListener(OnPreDrawListener l) { listener = l; }
        void removeOnPreDrawListener(OnPreDrawListener l) { if (listener == l) listener = null; }
    }
    static class View {
        static final int VISIBLE = 0;
        int top = 0, bottom = 20, position = 0, visibility = VISIBLE;
        RecyclerView.ViewHolder holder = new RecyclerView.ViewHolder();
        int getTop() { return top; } int getBottom() { return bottom; }
        int getVisibility() { return visibility; }
    }
    static class RecyclerView {
        static final int NO_POSITION = -1;
        static class ViewHolder {
            int locks; float alpha = 1;
            void setIsRecyclable(boolean value) { locks += value ? -1 : 1; }
        }
    }
    static class Animator {
        boolean running; int adds, ends;
        ArrayList<RecyclerView.ViewHolder> holders = new ArrayList<>();
        boolean isRunning() { return running; }
        void animateAdd(RecyclerView.ViewHolder h) { adds++; h.alpha = 0; holders.add(h); }
        void runPendingAnimations() { running = !holders.isEmpty(); }
        void endAnimations() {
            ends++; running = false;
            for (RecyclerView.ViewHolder h : holders) { h.alpha = 1; h.setIsRecyclable(true); }
            holders.clear();
        }
    }
    class ListView {
        boolean attached = true, shown = true, computing, pending;
        ViewTreeObserver observer = new ViewTreeObserver();
        ArrayList<View> children = new ArrayList<>();
        boolean isAttachedToWindow() { return attached; } boolean isShown() { return shown; }
        boolean isComputingLayout() { return computing; } boolean hasPendingAdapterUpdates() { return pending; }
        Animator getItemAnimator() { return itemAnimator; }
        ViewTreeObserver getViewTreeObserver() { return observer; }
        int getChildCount() { return children.size(); } int getHeight() { return 500; }
        View getChildAt(int i) { return children.get(i); }
        int getChildAdapterPosition(View v) { return v.position; }
        RecyclerView.ViewHolder getChildViewHolder(View v) { return v.holder; }
    }
    static final int MAX_ANIMATED_SEARCH_ROWS = 64;
    Animator itemAnimator = new Animator();
    ListView searchListView = new ListView();
    ViewTreeObserver.OnPreDrawListener searchResultsEnterListener;
    boolean searchResultsEntering;
""" + methods + """
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    public static void main(String[] args) {
        Harness h = new Harness();
        for (int i = 0; i < 100; i++) h.searchListView.children.add(new View());
        h.searchListView.children.get(0).bottom = 0;
        h.searchListView.children.get(1).top = 500;
        h.searchListView.children.get(2).position = -1;
        h.searchListView.children.get(3).visibility = 8;
        h.scheduleSearchResultsEntrance();
        check(h.itemAnimator.adds == 0);
        h.searchResultsEnterListener.onPreDraw();
        check(h.itemAnimator.adds == 60 && h.searchResultsEnterListener == null);
        check(h.searchListView.children.get(4).holder.locks == 1);
        h.cancelPendingSearchResultsEntrance();
        check(h.itemAnimator.running && h.itemAnimator.ends == 0);
        check(h.searchListView.children.get(4).holder.locks == 1);
        h.cancelSearchResultsEntrance();
        check(h.searchListView.children.get(4).holder.alpha == 1);
        check(h.searchListView.children.get(4).holder.locks == 0);
        h.scheduleSearchResultsEntrance();
        ViewTreeObserver.OnPreDrawListener stale = h.searchResultsEnterListener;
        h.cancelSearchResultsEntrance();
        h.scheduleSearchResultsEntrance();
        stale.onPreDraw();
        check(h.searchResultsEnterListener != null && h.itemAnimator.adds == 60);
        SharedConfig.enabled = false;
        h.searchResultsEnterListener.onPreDraw();
        check(h.itemAnimator.adds == 60);
        h.scheduleSearchResultsEntrance();
        check(h.searchResultsEnterListener == null);
        SharedConfig.enabled = true;
        for (int mode = 0; mode < 5; mode++) {
            h.scheduleSearchResultsEntrance();
            h.searchListView.attached = mode != 0;
            h.searchListView.shown = mode != 1;
            h.searchListView.computing = mode == 2;
            h.searchListView.pending = mode == 3;
            h.itemAnimator.running = mode == 4;
            h.searchResultsEnterListener.onPreDraw();
            check(h.itemAnimator.adds == 60);
            h.searchListView.attached = h.searchListView.shown = true;
            h.searchListView.computing = h.searchListView.pending = false;
        }
        h.cancelSearchResultsEntrance();
        check(h.itemAnimator.running); // Never cancel a diff we do not own.
    }
}
"""
        with tempfile.TemporaryDirectory(prefix="search-fallback-") as tmp:
            java = Path(tmp) / "Harness.java"
            java.write_text(harness)
            result = subprocess.run(["javac", "-d", tmp, str(java)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(["java", "-cp", tmp, "Harness"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
