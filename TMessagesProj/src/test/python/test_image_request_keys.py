"""Exercise production request construction with the emoji cache namespace set."""
import subprocess
import tempfile
import unittest

import test_sticker_first_frame_fade as sticker


SCENARIOS = r"""
    static void prefixedRequests() {
        for (String prefix : new String[]{null, "13_", "7_", "avatar_"}) {
            ImageReceiver r = receiver();
            r.currentImageKey = r.currentMediaKey = r.currentThumbKey = null;
            r.uniqKeyPrefix = prefix;
            request(r, "topic-A", null);
            check(r.currentMediaKey == null, "absent media acquired a fake prefixed key");
            check(((prefix == null ? "" : prefix) + "topic-A").equals(r.currentImageKey), "real image keeps namespace");
            BitmapDrawable bitmap = new BitmapDrawable();
            r.deliver(bitmap, ImageReceiver.TYPE_IMAGE, true);
            int guid = r.currentGuid;
            request(r, "topic-A", null);
            check(r.currentGuid == guid && r.currentImageDrawable == bitmap, "same topic bind must not reload");
            request(r, "topic-B", null);
            check(r.currentGuid != guid && r.currentImageDrawable == null
                    && ((prefix == null ? "" : prefix) + "topic-B").equals(r.currentImageKey),
                    "another topic must not compare equal through a fake media key");
            r.setImage(new ImageLocation("video"), "filter", null, null, null, null,
                    null, 0, null, new MessageObject(), 1);
            check(r.currentImageKey == null, "absent image acquired a fake prefixed key");
            check(((prefix == null ? "" : prefix) + "video@filter").equals(r.currentMediaKey), "real media keeps namespace");
        }
    }
"""


class ImageRequestKeysTest(unittest.TestCase):
    def sources(self):
        receiver, fixture = sticker.source()
        fixture = fixture.replace("public static void main(String[] args) {", SCENARIOS +
                                  "public static void main(String[] args) { prefixedRequests();")
        self.assertIn(SCENARIOS, fixture)
        return receiver, fixture

    def run_harness(self, sources):
        with tempfile.TemporaryDirectory(prefix="image-request-keys-") as tmp:
            sticker.compile_sources(tmp, sources)
            return subprocess.run(["java", "-ea", "-cp", tmp, "StickerFirstFrameHarness", "cache"],
                                  capture_output=True, text=True, timeout=20)

    def test_namespaced_static_topic_photo_and_media_requests(self):
        run = self.run_harness(self.sources())
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)

    def test_negative_controls(self):
        for field in ("imageKey", "mediaKey"):
            with self.subTest(field=field):
                receiver, fixture = self.sources()
                guard = f"if (uniqKeyPrefix != null && {field} != null)"
                self.assertEqual(receiver.count(guard), 1)
                run = self.run_harness((receiver.replace(guard, "if (uniqKeyPrefix != null)"), fixture))
                self.assertNotEqual(run.returncode, 0)
                self.assertIn("absent", run.stderr)


if __name__ == "__main__":
    unittest.main()
