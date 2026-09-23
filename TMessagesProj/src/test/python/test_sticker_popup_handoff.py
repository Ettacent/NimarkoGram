"""Execute the pack popup's delayed-result and sheet-handoff methods on a host JVM."""

from pathlib import Path
import subprocess
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui/Components/EmojiPacksAlert.java"


def method(source, signature, start=0):
    begin = source.index(signature, start)
    opening = source.index("{", begin)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[begin:end]


class StickerPopupHandoffTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = SOURCE.read_text()
        loader = source.index("class EmojiPacksLoader implements")
        extracted = "\n".join((
            method(source, "private void openAsStickerSet("),
            method(source, "public void dismissInternal()"),
        ))
        loader_methods = "\n".join((
            method(source, "private void applyStickerSet(", loader),
            method(source, "public void didReceivedNotification(", loader),
        ))
        java = r"""
import java.util.*;

class TLRPC {
    static class InputStickerSet { long id; String short_name; }
    static class StickerSet { long id; String short_name; boolean emojis; }
    static class TL_messages_stickerSet { StickerSet set; ArrayList<Object> documents = new ArrayList<>(); }
}
class NotificationCenter { static final int groupStickersDidLoad = 1; }
class BaseFragment { Object getParentActivity() { return new Object(); } }
class ChatActivity extends BaseFragment { Object getChatActivityEnterView() { return null; } }
class BottomSheet {
    void dismissInternal() {}
}
class StickersAlert {
    static TLRPC.TL_messages_stickerSet openedSet;
    StickersAlert(Object context, BaseFragment fragment, TLRPC.InputStickerSet input,
                  TLRPC.TL_messages_stickerSet set, Object delegate, Object resources, boolean force) {
        openedSet = set;
    }
    void show() { StickerPopupHandoff.events.add("new_open"); }
}
public class StickerPopupHandoff extends BottomSheet {
    static ArrayList<String> events = new ArrayList<>();
    static void check(boolean yes, String label) { if (!yes) throw new AssertionError(label); }
    boolean dismissed;
    BaseFragment fragment = new BaseFragment();
    Object resourcesProvider;
    TLRPC.InputStickerSet pendingStickerSetInput;
    TLRPC.TL_messages_stickerSet pendingStickerSet;
    EmojiPacksLoader customEmojiPacks = new EmojiPacksLoader();
    boolean isDismissed() { return dismissed; }
    void dismiss() { dismissed = true; events.add("old_dismiss"); }
    Object getContext() { return new Object(); }
    @Override public void dismissInternalMarker() {}
    // @OUTER_METHODS@
    class EmojiPacksLoader {
        int currentAccount = 2;
        ArrayList<TLRPC.InputStickerSet> inputStickerSets = new ArrayList<>();
        ArrayList<TLRPC.TL_messages_stickerSet> stickerSets = new ArrayList<>();
        int updates;
        void putStickerSet(int index, TLRPC.TL_messages_stickerSet set) { updates++; }
        void onUpdate() { updates++; }
        // @LOADER_METHODS@
    }
    static TLRPC.TL_messages_stickerSet set(long id, String name, boolean emojis) {
        TLRPC.TL_messages_stickerSet result = new TLRPC.TL_messages_stickerSet();
        result.set = new TLRPC.StickerSet();
        result.set.id = id; result.set.short_name = name; result.set.emojis = emojis;
        return result;
    }
    static StickerPopupHandoff popup(long id, String name) {
        StickerPopupHandoff popup = new StickerPopupHandoff();
        TLRPC.InputStickerSet input = new TLRPC.InputStickerSet();
        input.id = id; input.short_name = name;
        popup.customEmojiPacks.inputStickerSets.add(input);
        popup.customEmojiPacks.stickerSets.add(null);
        return popup;
    }
    public static void main(String[] args) {
        events.clear(); StickersAlert.openedSet = null;
        StickerPopupHandoff popup = popup(7, "pack7");
        TLRPC.TL_messages_stickerSet unrelated = set(8, "pack8", true);
        popup.customEmojiPacks.didReceivedNotification(1, 2, 8L, unrelated);
        check(popup.customEmojiPacks.stickerSets.get(0) == null, "unrelated set applied");
        TLRPC.TL_messages_stickerSet incomplete = set(7, "pack7", true);
        incomplete.documents = null;
        popup.customEmojiPacks.didReceivedNotification(1, 2, 7L, incomplete);
        check(popup.customEmojiPacks.stickerSets.get(0) == null, "documentless set shown");
        TLRPC.TL_messages_stickerSet emoji = set(7, "pack7", true);
        popup.customEmojiPacks.didReceivedNotification(1, 2, 7L, emoji);
        check(popup.customEmojiPacks.stickerSets.get(0) == emoji, "late documents not bound");
        check(popup.customEmojiPacks.updates == 2, "late arrival did not update UI once");
        popup.customEmojiPacks.applyStickerSet(0, emoji);
        check(popup.customEmojiPacks.updates == 2, "duplicate callback rebound UI");

        events.clear();
        StickerPopupHandoff handoff = popup(9, "pack9");
        TLRPC.TL_messages_stickerSet sticker = set(9, "pack9", false);
        handoff.customEmojiPacks.didReceivedNotification(1, 2, 9L, sticker);
        check(handoff.dismissed && StickersAlert.openedSet == null, "sheets overlapped");
        handoff.dismissInternal();
        check(StickersAlert.openedSet == sticker, "handoff refetched the loaded set");
        check(events.equals(Arrays.asList("old_dismiss", "old_removed", "new_open")), "wrong sheet order " + events);
        handoff.customEmojiPacks.didReceivedNotification(1, 2, 9L, sticker);
        check(events.size() == 3, "dismissed popup handled late notification");
    }
}
"""
        # The fake superclass records the actual end of the old sheet.
        java = java.replace("void dismissInternal() {}", "void dismissInternal() { StickerPopupHandoff.events.add(\"old_removed\"); }")
        java = java.replace("    @Override public void dismissInternalMarker() {}\n", "")
        java = java.replace("// @OUTER_METHODS@", extracted).replace("// @LOADER_METHODS@", loader_methods)
        cls.temp = tempfile.TemporaryDirectory(prefix="sticker-popup-")
        cls.addClassCleanup(cls.temp.cleanup)
        path = Path(cls.temp.name) / "StickerPopupHandoff.java"
        path.write_text(java)
        build = subprocess.run(["javac", "-d", cls.temp.name, str(path)], capture_output=True, text=True, timeout=30)
        if build.returncode:
            raise AssertionError(build.stderr)

    def test_delayed_documents_and_nonemoji_handoff(self):
        result = subprocess.run(["java", "-ea", "-cp", self.temp.name, "StickerPopupHandoff"],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_loader_preallocates_slots_and_ignores_late_error(self):
        source = SOURCE.read_text()
        init = method(source, "public void init()", source.index("class EmojiPacksLoader implements"))
        self.assertLess(init.index("stickerSets.add(null)"), init.index("getStickerSet(inputStickerSets.get(i)"))
        self.assertIn("set == null && stickerSets.get(index) == null", init)
        self.assertIn("if (isDismissed()) return;", init)


if __name__ == "__main__":
    unittest.main()
