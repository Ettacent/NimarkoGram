"""Focused source/JVM regressions for search rows and invisible emoji draws."""

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


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
class FirstFrameBoundaries(unittest.TestCase):
    def run_java(self, source):
        with tempfile.TemporaryDirectory(prefix="search-first-frame-") as tmp:
            java = Path(tmp) / "Harness.java"
            java.write_text(source)
            compiled = subprocess.run(["javac", "-d", tmp, str(java)], capture_output=True, text=True, timeout=30)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            return subprocess.run(["java", "-ea", "-cp", tmp, "Harness"], capture_output=True, text=True, timeout=30)

    def emoji_harness(self):
        source = (UI / "Components/AnimatedEmojiDrawable.java").read_text()
        draws = "\n".join(block(source, signature) for signature in (
            "private void draw(Canvas canvas, boolean ownsLoadFade)",
            "public void draw(Canvas canvas, Rect drawableBounds, float alpha)",
            "public void draw(Canvas canvas, ImageReceiver.BackgroundThreadDrawHolder",
        ))
        return """
class Canvas {} class Rect {}
class Drawable { Rect getBounds() { return new Rect(); } }
class ImageReceiver {
    static class BackgroundThreadDrawHolder {}
    boolean ready; int normal, owned, background;
    boolean hasReadyImage() { return ready; }
    void setImageCoords(Rect r) {} void setAlpha(float a) {}
    void draw(Canvas c) { normal++; }
    void drawWithoutLoadFade(Canvas c) { owned++; }
    void draw(Canvas c, BackgroundThreadDrawHolder h) { background++; }
}
class AnimatedEmojiDrawable extends Drawable {
    ImageReceiver imageReceiver; float alpha = 1; int blanks;
    void markMissingDocumentPresented() { blanks++; }
    void render(Canvas c) { draw(c, false); }
    void renderOwned(Canvas c) { draw(c, true); }
""" + draws + """
}
public class Harness {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        AnimatedEmojiDrawable emoji = new AnimatedEmojiDrawable(); Canvas c = new Canvas();
        emoji.alpha = 0; emoji.render(c); check(emoji.blanks == 0);
        emoji.alpha = 1; emoji.render(c); check(emoji.blanks == 1);
        ImageReceiver r = emoji.imageReceiver = new ImageReceiver();
        r.ready = true; emoji.alpha = 0;
        emoji.render(c); emoji.renderOwned(c);
        emoji.draw(c, new Rect(), 0);
        emoji.draw(c, new ImageReceiver.BackgroundThreadDrawHolder(), false);
        check(r.normal == 0 && r.owned == 0 && r.background == 0);
        emoji.alpha = 1; emoji.render(c); emoji.renderOwned(c);
        emoji.draw(c, new Rect(), 1);
        emoji.draw(c, new ImageReceiver.BackgroundThreadDrawHolder(), false);
        check(r.normal == 2 && r.owned == 1 && r.background == 1);
        r.ready = false; emoji.alpha = 0; emoji.render(c);
        check(r.normal == 3); // undecoded animation still gets preparation draws
        System.out.println("PASS: invisible ready emoji preserves first presented draw");
    }
}
"""

    def test_ready_emoji_does_not_consume_hidden_draw(self):
        run = self.run_java(self.emoji_harness())
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("PASS: invisible ready emoji", run.stdout)

    def test_hidden_draw_negative_control(self):
        source = self.emoji_harness()
        before = "if (alpha <= 0f && imageReceiver.hasReadyImage()) return;"
        self.assertEqual(source.count(before), 3)
        run = self.run_java(source.replace(before, "if (false) return;", 1))
        self.assertNotEqual(run.returncode, 0)
        self.assertIn("AssertionError", run.stderr)

    def emoji_host_harness(self):
        source = (UI / "Components/AnimatedEmojiDrawable.java").read_text()
        guard = block(source, "private boolean hasPresentedView()")
        return """
import java.util.ArrayList;
interface ViewParent {}
class View implements ViewParent {
    static final int VISIBLE=0, INVISIBLE=4;
    int visibility=VISIBLE; float alpha=1; ViewParent parent;
    int getVisibility() { return visibility; }
    float getAlpha() { return alpha; }
    ViewParent getParent() { return parent; }
}
public class Harness {
    ArrayList<View> views;
    ArrayList<Object> holders;
""" + guard + """
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        Harness emoji = new Harness(); check(emoji.hasPresentedView());
        View row = new View(), icon = new View(); icon.parent = row;
        emoji.views = new ArrayList<>(); emoji.views.add(icon);
        row.alpha = 0; check(!emoji.hasPresentedView());
        row.alpha = 1; check(emoji.hasPresentedView());
        row.visibility = View.INVISIBLE; check(!emoji.hasPresentedView());
        row.visibility = View.VISIBLE; icon.alpha = 0; check(!emoji.hasPresentedView());
        View other = new View(); emoji.views.add(other); check(emoji.hasPresentedView());
        other.alpha = 0; check(!emoji.hasPresentedView());
        emoji.holders = new ArrayList<>(); emoji.holders.add(new Object());
        check(emoji.hasPresentedView());
        emoji.holders.clear(); check(!emoji.hasPresentedView());
        System.out.println("PASS: direct receiver host presentation");
    }
}
"""

    def test_direct_receiver_waits_for_presented_host(self):
        source = (UI / "Components/AnimatedEmojiDrawable.java").read_text()
        factory = block(source, "private void createImageReceiver()")
        self.assertIn("if (hasReadyImage() && !hasPresentedView()) return false;", factory)
        run = self.run_java(self.emoji_host_harness())
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("PASS: direct receiver host presentation", run.stdout)

    def test_host_alpha_negative_control(self):
        source = self.emoji_host_harness()
        before = "view.getVisibility() == View.VISIBLE && view.getAlpha() > 0f"
        self.assertEqual(source.count(before), 1)
        run = self.run_java(source.replace(before, "view.getVisibility() == View.VISIBLE"))
        self.assertNotEqual(run.returncode, 0)
        self.assertIn("AssertionError", run.stderr)

    def search_rows_harness(self):
        source = (UI / "Components/SearchViewPager.java").read_text()
        methods = "\n".join(block(source, signature) for signature in (
            "private static class SearchRow",
            "private static boolean sameSearchRows(",
            "private static ArrayList<SearchRow> snapshotSearchRows(",
        ))
        return """
import java.util.ArrayList; import java.util.HashMap;
public class Harness {
    static final int MAX_ANIMATED_SEARCH_ROWS = 64;
    static class DialogsSearchAdapter {
        static final int VIEW_TYPE_GRAY_SECTION = 1;
        final int[] types; final Object[] items;
        DialogsSearchAdapter(int[] types, Object[] items) { this.types = types; this.items = items; }
        int getItemCount() { return types.length; }
        int getItemViewType(int i) { return types[i]; }
        Object getItem(int i) { return items[i]; }
    }
    static class TLRPC {
        static class User { long id; User(long id) { this.id=id; } }
        static class Chat { long id; }
        static class EncryptedChat { int id; }
        static class TL_forumTopic { int id; }
        static class TL_sponsoredPeer { Object peer; }
    }
    static class MessageObject {
        long getDialogId() { return 0; } int getId() { return 0; }
        static long getPeerId(Object peer) { return 0; }
    }
    static class ContactsController { static class Contact { int contact_id; } }
""" + methods + """
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        TLRPC.User a = new TLRPC.User(1), b = new TLRPC.User(2), inserted = new TLRPC.User(9);
        ArrayList<SearchRow> oldRows = snapshotSearchRows(new DialogsSearchAdapter(
            new int[]{1,2,1,2}, new Object[]{null,a,null,b}));
        ArrayList<SearchRow> newRows = snapshotSearchRows(new DialogsSearchAdapter(
            new int[]{1,2,1,2,1,2}, new Object[]{null,inserted,null,a,null,b}));
        check(oldRows.get(0).key.equals(newRows.get(2).key));
        check(oldRows.get(2).key.equals(newRows.get(4).key));
        check(!oldRows.get(0).key.equals(newRows.get(0).key));
        check(!sameSearchRows(oldRows, newRows));
        int[] manyTypes = new int[65]; Object[] manyItems = new Object[65];
        check(snapshotSearchRows(new DialogsSearchAdapter(manyTypes, manyItems)) == null);
        System.out.println("PASS: section identity follows surviving result");
    }
}
"""

    def test_search_section_identity_survives_insert_above(self):
        run = self.run_java(self.search_rows_harness())
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("PASS: section identity", run.stdout)

    def test_section_ordinal_negative_control(self):
        source = self.search_rows_harness()
        before = 'rows.set(i, new SearchRow("section-for:" + rows.get(firstResult).key + ":gap:" + (firstResult - i)));'
        self.assertEqual(source.count(before), 1)
        run = self.run_java(source.replace(before, "/* retained ordinal identity */"))
        self.assertNotEqual(run.returncode, 0)
        self.assertIn("AssertionError", run.stderr)

    def test_topics_search_publishes_matching_rows_before_adapter_notification(self):
        source = (UI / "TopicsFragment.java").read_text()
        search = block(source, "private void searchMessages(String searchString)")
        self.assertIn("final int generation = ++searchGeneration", search)
        self.assertIn("if (generation != searchGeneration) return", search)
        self.assertIn("isLoading = rowCount == 0", search)
        self.assertLess(search.index("isLoading = searchResultTopics.isEmpty();"), search.index("updateRows();", search.index("searchRunnable = () ->")))
        self.assertNotIn("searchResultTopics.clear();\n            searchResultMessages.clear();", search[:search.index("if (TextUtils.isEmpty(searchString))")])
        adapter = source[source.index("private class SearchAdapter extends RecyclerListView.SelectionAdapter") :]
        count = block(adapter, "public int getItemCount()")
        self.assertIn("return rowCount;", count)
        self.assertNotIn("if (isLoading)", count)
        rows = block(source, "private void updateRows()")
        self.assertIn("DiffUtil.calculateDiff", rows)
        self.assertIn('"topics-header"', rows)
        self.assertIn('"message:" + message.getDialogId()', rows)
        self.assertIn("MAX_ANIMATED_SEARCH_ROWS", rows)
        network = block(source, "private void loadMessages(String searchString)")
        self.assertIn("generation == searchGeneration", network)

    def test_badge_draw_waits_for_visible_ancestor(self):
        source = (UI / "Cells/DialogCell.java").read_text()
        guard = block(source, "private boolean isBadgePresented()")
        self.assertIn("view.getAlpha() <= 0f", guard)
        self.assertIn("view.getVisibility() != View.VISIBLE", guard)
        self.assertIn("view = parent instanceof View ? (View) parent : null", guard)
        self.assertIn("getAlpha() <= 0f || !isBadgePresented()", source)
        self.assertIn("botVerification != null && isBadgePresented()", source)
        self.assertIn("if (isBadgePresented()) emojiStatus.draw(canvas);", source)
        self.assertIn("badgeOwnerDrawn && attachedToWindow && isBadgePresented()", source)
        pager = (UI / "Components/SearchViewPager.java").read_text()
        self.assertIn("itemAnimator.setSupportsChangeAnimations(false)", pager)

    def test_suggested_search_rows_keep_generation_and_diff_handoff(self):
        adapter = (UI / "Adapters/DialogsSearchAdapter.java").read_text()
        local = block(adapter, "private void updateSearchResults(")
        self.assertLess(local.index("if (searchId != lastSearchId)"), local.index("waitingResponseCount--;"))
        sponsored = block(adapter, "public void searchDialogs(String text, int folderId, boolean allowPublicPosts)")
        self.assertIn("final int sponsoredGeneration = ++sponsoredRequestGeneration", sponsored)
        self.assertIn("if (sponsoredGeneration != sponsoredRequestGeneration)", sponsored)
        count = block(adapter[adapter.index("public int getRecentItemsCount()"):], "public int getItemCount()")
        self.assertNotIn("if (waitingResponseCount == 3)", count)
        pager = (UI / "Components/SearchViewPager.java").read_text()
        refresh = block(pager, "public void notifyDataSetChanged()")
        self.assertIn("DiffUtil.calculateDiff", refresh)
        self.assertIn("notifyItemRangeInserted(0, nextRows.size())", refresh)
        self.assertIn("notifyItemRangeRemoved(0, previousRows.size())", refresh)


if __name__ == "__main__":
    unittest.main()
