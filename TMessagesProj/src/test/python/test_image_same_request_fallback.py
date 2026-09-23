"""Guard the same-key thumbnail fast path against a stale fallback drawable."""

from pathlib import Path
import subprocess
import tempfile
import unittest

import test_sticker_first_frame_fade as sticker


SOURCE = Path(__file__).resolve().parents[2] / "main/java/org/telegram/messenger/ImageReceiver.java"


class SameRequestFallbackTest(unittest.TestCase):
    def test_same_key_rebind_refreshes_fallback_without_reloading_image(self):
        source = SOURCE.read_text()
        start = source.index("if (sameImageRequest || sameMediaRequest || sameThumbOnlyRequest) {")
        end = source.index("if (!sameImageRequest && !sameMediaRequest", start)
        branch = source[start:end]
        self.assertLess(branch.index("setStaticDrawable(thumb)"), branch.index("if (delegate != null)"))
        self.assertLess(branch.index("setStaticDrawable(thumb)"), branch.index("if (!canceledLoading && !retryExhausted)"))
        self.assertIn("updateDrawableRadius(staticThumbDrawable)", branch)
        self.assertIn("invalidate()", branch)
        self.assertIn("return;", branch)

    def test_same_key_rebind_tracks_side_slots_and_account(self):
        receiver, fixture = sticker.source()
        scenario = r"""
    static void sameRequestBoundaries() {
        ImageReceiver r = receiver();
        MessageObject owner = new MessageObject();
        Drawable firstFallback = new BitmapDrawable(), nextFallback = new BitmapDrawable();
        r.setImage(null, null, new ImageLocation("image"), null,
                new ImageLocation("thumb-a"), null, firstFallback, 0, null, owner, 1);
        int guid = r.currentGuid;
        BitmapDrawable image = new BitmapDrawable();
        check(r.deliver(image, ImageReceiver.TYPE_IMAGE, true), "image accepted");
        r.setImage(null, null, new ImageLocation("image"), null,
                new ImageLocation("thumb-a"), null, nextFallback, 0, null, owner, 1);
        check(r.currentGuid == guid && r.currentImageDrawable == image
                && r.staticThumbDrawable == nextFallback, "fallback-only rebind retains image and replaces stale fallback");

        r = receiver();
        r.setImage(null, null, new ImageLocation("image"), null,
                new ImageLocation("thumb-a"), null, null, 0, null, owner, 1);
        BitmapDrawable oldThumb = new BitmapDrawable();
        check(r.deliver(oldThumb, ImageReceiver.TYPE_THUMB, true), "old thumbnail accepted");
        guid = r.currentGuid;
        r.setImage(null, null, new ImageLocation("image"), null,
                new ImageLocation("thumb-b"), null, null, 0, null, owner, 1);
        check(r.currentGuid != guid && "thumb-b".equals(r.currentThumbKey)
                && r.currentThumbDrawable == null, "changed thumbnail key is not a same-request shortcut");

        r = receiver();
        r.setImage(new ImageLocation("media"), null, new ImageLocation("preview-a"), null,
                null, null, null, 0, null, owner, 1);
        BitmapDrawable oldPreview = new BitmapDrawable();
        check(r.deliver(oldPreview, ImageReceiver.TYPE_IMAGE, true), "old preview accepted");
        guid = r.currentGuid;
        r.setImage(new ImageLocation("media"), null, new ImageLocation("preview-b"), null,
                null, null, null, 0, null, owner, 1);
        check(r.currentGuid != guid && "preview-b".equals(r.currentImageKey)
                && r.currentImageDrawable == null, "changed preview key is not a same-media shortcut");

        r = receiver();
        r.setImage(null, null, new ImageLocation("shared"), null,
                null, null, null, 0, null, owner, 1);
        BitmapDrawable oldImage = new BitmapDrawable();
        check(r.deliver(oldImage, ImageReceiver.TYPE_IMAGE, true), "old account image accepted");
        guid = r.currentGuid;
        r.setCurrentAccount(1);
        check(r.currentGuid != guid && r.currentImageDrawable == null && r.currentImageKey == null,
                "account change must discard old pixels and invalidate queued deliveries");
        check(!r.setImageBitmapByKey(oldImage, "shared", ImageReceiver.TYPE_IMAGE, false, guid),
                "old account callback must be rejected");
        MessageObject otherAccount = new MessageObject(); otherAccount.currentAccount = 1;
        r.setImage(null, null, new ImageLocation("shared"), null,
                null, null, null, 0, null, otherAccount, 1);
        check("shared".equals(r.currentImageKey) && r.currentGuid != guid,
                "same file key must issue a fresh account request");
    }
"""
        fixture = fixture.replace("public static void main(String[] args) {",
                                  scenario + "public static void main(String[] args) { sameRequestBoundaries();")
        with tempfile.TemporaryDirectory(prefix="image-rebind-") as tmp:
            sticker.compile_sources(tmp, (receiver, fixture))
            run = subprocess.run(["java", "-ea", "-cp", tmp, "StickerFirstFrameHarness", "scope"],
                                 capture_output=True, text=True, timeout=20)
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)


if __name__ == "__main__":
    unittest.main()
