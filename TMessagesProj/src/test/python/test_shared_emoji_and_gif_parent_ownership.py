"""Focused host-JVM regressions; production Java bodies, fake Android/loader.

No APK or repository build output. Negative controls remove each fix in memory.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import test_sticker_first_frame_fade as sticker
from test_emoji_first_frame_fade import method


JAVA = Path(__file__).resolve().parents[2] / "main/java/org/telegram"
STRING_BRANCH = """        if (first instanceof String && second instanceof String) {
            return first.equals(second);
        }
"""
HOLDER_BRANCH = "        if (holders != null && !holders.isEmpty()) return true;"


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
class SharedEmojiAndGifParentOwnership(unittest.TestCase):
    def host_source(self):
        source = (JAVA / "ui/Components/AnimatedEmojiDrawable.java").read_text()
        draw = method(method(source, "private void createImageReceiver()"),
                      "public boolean draw(Canvas canvas)")
        gate = method(source, "private boolean hasPresentedView()")
        return """
import java.util.ArrayList;
interface ViewParent {}
class View implements ViewParent {
    static final int VISIBLE = 0, GONE = 8;
    int visibility; float alpha = 1; ViewParent parent;
    int getVisibility() { return visibility; }
    float getAlpha() { return alpha; }
    ViewParent getParent() { return parent; }
}
class Canvas {}
class ImageReceiver {
    boolean ready = true; int draws;
    boolean hasReadyImage() { return ready; }
    public boolean draw(Canvas c) { draws++; return true; }
}
public class Harness {
    ArrayList<View> views = new ArrayList<>();
    ArrayList<Object> holders;
    ImageReceiver receiver = new ImageReceiver() {
""" + draw + "\n};\n" + gate + """
    static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        Harness h = new Harness(); Canvas c = new Canvas();
        View suggestion = new View(), container = new View();
        suggestion.parent = container; h.views.add(suggestion);
        container.visibility = View.GONE;
        check(!h.receiver.draw(c) && h.receiver.draws == 0,
              "hidden view-only host must preserve its first-frame clock");
        h.holders = new ArrayList<>(); h.holders.add(new Object());
        check(h.receiver.draw(c) && h.receiver.draws == 1,
              "visible span/topic holder must not be vetoed by hidden suggestion");
        container.visibility = View.VISIBLE; container.alpha = 0;
        check(h.receiver.draw(c), "holder must survive alpha-zero view ancestor");
        h.holders.clear();
        check(!h.receiver.draw(c), "last holder removal restores view-only gate");
        container.alpha = 1;
        check(h.receiver.draw(c), "visible view remains drawable");
        container.alpha = 0; h.receiver.ready = false;
        check(h.receiver.draw(c), "undecoded image still gets preparation draws");
        h.receiver.ready = true; h.views.clear(); h.holders.add(new Object());
        check(h.receiver.draw(c), "holder-only host remains drawable");
        System.out.println("PASS: shared holder visibility and view-only fade gate");
    }
}
"""

    def run_host(self, source):
        with tempfile.TemporaryDirectory(prefix="shared-emoji-holder-") as tmp:
            path = Path(tmp) / "Harness.java"
            path.write_text(source)
            build = subprocess.run(["javac", "-d", tmp, str(path)],
                                   capture_output=True, text=True, timeout=30)
            self.assertEqual(build.returncode, 0, build.stderr)
            return subprocess.run(["java", "-ea", "-cp", tmp, "Harness"],
                                  capture_output=True, text=True, timeout=20)

    def test_shared_holder_visibility(self):
        run = self.run_host(self.host_source())
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)

    def test_hidden_holder_negative_control(self):
        source = self.host_source()
        self.assertEqual(source.count(HOLDER_BRANCH), 1)
        run = self.run_host(source.replace(HOLDER_BRANCH, ""))
        self.assertNotEqual(run.returncode, 0)
        self.assertIn("visible span/topic holder must not be vetoed", run.stderr)

    def parent_sources(self):
        receiver, fixture = sticker.source()
        # Add observability only to the decoder fake, not production methods.
        receiver = receiver.replace("class AnimatedFileDrawable extends BitmapDrawable {",
                                    "class AnimatedFileDrawable extends BitmapDrawable { int releases;")
        receiver = receiver.replace("void recycle() {} void stop() {}",
                                    "void recycle() { releases++; } void stop() {}")
        scenario = r"""
    static class EqualMutableParent {
        @Override public boolean equals(Object other) { return true; }
    }
    static class OwnedMessage extends MessageObject {
        int id; long dialog;
        OwnedMessage(int account, int id, long dialog) {
            currentAccount = account; this.id = id; this.dialog = dialog;
        }
        @Override int getId() { return id; }
        @Override long getDialogId() { return dialog; }
    }
    static void bindParent(ImageReceiver r, Object owner, boolean media) {
        r.setImage(media ? new ImageLocation("gif") : null, null,
                new ImageLocation(media ? "preview" : "gif"), null,
                new ImageLocation("thumb"), null, null, 0, null, owner, 1);
    }
    static void parentOwnership() {
        for (boolean media : new boolean[]{false, true}) {
            ImageReceiver r = receiver();
            int type = media ? ImageReceiver.TYPE_MEDIA : ImageReceiver.TYPE_IMAGE;
            bindParent(r, new String("gif123"), media);
            AnimatedFileDrawable gif = new AnimatedFileDrawable();
            gif.ready = true; gif.isWebmSticker = false;
            check(r.deliver(gif, type, true), "ready GIF accepted");
            r.currentAlpha = .42f; r.lastUpdateAlphaTime = 1234;
            int guid = r.currentGuid;
            bindParent(r, new String("gif123"), media);
            check(r.currentGuid == guid && gif.releases == 0
                    && (media ? r.currentMediaDrawable : r.currentImageDrawable) == gif,
                    "equal immutable GIF parent must retain decoder/request");
            check(r.currentAlpha == .42f && r.lastUpdateAlphaTime == 1234,
                    "same parent rebind must retain fade opacity and clock");

            r.setCurrentAccount(1);
            check(gif.releases == 1 && r.currentGuid != guid
                    && r.currentMediaDrawable == null && r.currentImageDrawable == null
                    && r.currentMediaKey == null && r.currentImageKey == null,
                    "account switch must release old decoder and keys");
            bindParent(r, new String("gif123"), media);
            check(!r.setImageBitmapByKey(gif, "gif", type, false, guid),
                    "same string/key on new account must reject old callback");
            AnimatedFileDrawable replacement = new AnimatedFileDrawable();
            replacement.ready = true; replacement.isWebmSticker = false;
            check(r.deliver(replacement, type, true), "new account delivery accepted");
            check(gif.releases == 1, "stale callback must not release old decoder twice");
            guid = r.currentGuid;
            bindParent(r, new String("gif456"), media);
            check(r.currentGuid != guid && replacement.releases == 1,
                    "different string parent remains a replacement request");
        }
        Object[][] parents = {
            {new EqualMutableParent(), new EqualMutableParent()},
            {new EqualMutableParent(), new String("gif123")},
            {new StringBuilder("gif123"), new StringBuilder("gif123")},
            {new OwnedMessage(0, 3, 4), new OwnedMessage(1, 3, 4)},
            {new OwnedMessage(0, 3, 4), new OwnedMessage(0, 5, 4)},
            {new OwnedMessage(0, 3, 4), new OwnedMessage(0, 3, 6)}
        };
        for (Object[] pair : parents) {
            ImageReceiver r = receiver(); bindParent(r, pair[0], false);
            int guid = r.currentGuid; bindParent(r, pair[1], false);
            check(r.currentGuid != guid, "mutable/logically different owners cannot compare equal");
        }
        ImageReceiver r = receiver();
        bindParent(r, new OwnedMessage(0, 3, 4), false);
        int guid = r.currentGuid;
        bindParent(r, new OwnedMessage(0, 3, 4), false);
        check(r.currentGuid == guid, "same account/dialog/message logical identity preserved");
        System.out.println("PASS: GIF rebind opacity/decoder retention and account release safety");
    }
"""
        fixture = fixture.replace("public static void main(String[] args) {",
                                  scenario + "public static void main(String[] args) { parentOwnership();")
        return receiver, fixture

    def run_parents(self, sources):
        with tempfile.TemporaryDirectory(prefix="gif-parent-owner-") as tmp:
            sticker.compile_sources(tmp, sources)
            return subprocess.run(["java", "-ea", "-cp", tmp, "StickerFirstFrameHarness", "scope"],
                                  capture_output=True, text=True, timeout=20)

    def test_gif_rebind_and_account_safety(self):
        run = self.run_parents(self.parent_sources())
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)

    def test_string_identity_negative_control(self):
        receiver, fixture = self.parent_sources()
        self.assertEqual(receiver.count(STRING_BRANCH), 1)
        run = self.run_parents((receiver.replace(STRING_BRANCH, ""), fixture))
        self.assertNotEqual(run.returncode, 0)
        self.assertIn("equal immutable GIF parent must retain decoder/request", run.stderr)

    def test_arbitrary_equals_negative_control(self):
        receiver, fixture = self.parent_sources()
        run = self.run_parents((receiver.replace(STRING_BRANCH,
                                "        if (java.util.Objects.equals(first, second)) return true;\n"), fixture))
        self.assertNotEqual(run.returncode, 0)
        self.assertIn("mutable/logically different owners cannot compare equal", run.stderr)


if __name__ == "__main__":
    unittest.main()
