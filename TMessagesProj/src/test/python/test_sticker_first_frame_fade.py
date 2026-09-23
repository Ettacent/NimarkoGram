"""Chat sticker rendering regressions using production Java on a host JVM.

Reuses the emoji pipeline's Android/decoder fakes. Delivery, draw, request
replacement, cancellation and disposal are extracted verbatim from ImageReceiver.
No Gradle, APK, network or generated files in the repository.
"""
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

import test_emoji_first_frame_fade as emoji_tests

SRC = Path(__file__).resolve().parents[2]
JAVA = SRC / "main/java/org/telegram"
FIXTURE = SRC / "test/fixtures/StickerFirstFrameHarness.java.txt"


def source():
    receiver = (JAVA / "messenger/ImageReceiver.java").read_text()
    cell = (JAVA / "ui/Cells/ChatMessageCell.java").read_text()
    code = emoji_tests.EmojiFirstFrameFadeTest().source()
    extra = "\n".join(emoji_tests.method(receiver, signature) for signature in [
        "public void setImage(ImageLocation mediaLocation,",
        "private boolean sameLoadingLocation(",
        "private boolean sameLoadingParent(",
        "public void cancelLoadImage(", "public int getNewGuid(",
        "public void setImageBitmap(Drawable bitmap)",
    ])
    # Extend only the platform/loading fakes; never rewrite extracted production bodies.
    code = code.replace("// Loader acceptance and frame selection/fading are not mocked.", extra)
    code = code.replace("void setImageBitmap(Drawable d) { staticThumbDrawable = d; }", "")
    code = code.replace("class Bitmap {}", "class Bitmap { void recycle() {} }")
    code = code.replace("boolean isHeavyDrawable()", "void setAllowVibration(boolean b) {} boolean isHeavyDrawable()")
    code = code.replace("void setStartEndTime(long a,long b)", "void setParentView(View v) {} void setStartEndTime(long a,long b)")
    code = code.replace("class ImageReceiver {", """class ImageReceiver {
        boolean allowLoadingOnAttachedOnly, ignoreImageSet, needsQualityThumb;
        boolean canceledLoading, forceLoding, allowLottieVibration;
        int animatedFileDrawableRepeatMaxCount;
        String uniqKeyPrefix;
        ImageLocation strippedLocation;
        TLRPC.Document qulityThumbDocument;
        Object composeShader, legacyShader;
        Canvas legacyCanvas;
        Bitmap legacyBitmap;
        void setBackupImage() {}
        boolean isFailedLoadRetryExhausted(String k, int t) { return false; }
        void resetFailedLoadRetry(int t) {}
        void loadImage() { getNewGuid(); } // fake ImageLoader request dispatch only
    """)
    code = code.replace("Object currentParentObject, currentImageLocation, currentMediaLocation, currentThumbLocation;",
                        "Object currentParentObject; ImageLocation currentImageLocation, currentMediaLocation, currentThumbLocation;")
    code = code.replace("Object mediaLocation, imageLocation, thumbLocation, parentObject;",
                        "ImageLocation mediaLocation, imageLocation, thumbLocation; Object parentObject;")
    code = code.replace("static class SetImageBackup {", """static class SetImageBackup {
        boolean isWebfileSet() { return false; }
        boolean isSet() { return mediaLocation != null || imageLocation != null || thumbLocation != null || thumb != null; }
    """)
    code = code.replace("class MessageObject {", """class MessageObject {
        int currentAccount;
        int getId() { return 0; }
        long getDialogId() { return 0; }
        boolean sticker = true, dice;
        Object lastGeoWebFileSet, lastGeoWebFileLoaded;
        boolean isAnyKindOfSticker() { return sticker; }
        boolean isDice() { return dice; }
        TLRPC.Document getDocument() { return null; }
        static Object getMedia(MessageObject m) { return null; }
    """)
    code = code.replace("class TLRPC {", """class TLRPC {
        static class Document { int dc_id; long id; }
        static class TL_messageMediaGeoLive {}
    """)
    bind = re.findall(r"photoImage\.setCrossfadeOnReady\([^;]+;", cell)
    fixture = FIXTURE.read_text().replace("/* CHAT_BIND */", "\n".join(bind))
    return code, fixture


def compile_sources(directory, sources):
    paths = []
    for filename, code in zip(("EmojiFirstFrameHarness.java", "StickerFirstFrameHarness.java"), sources):
        path = Path(directory) / filename
        path.write_text(code)
        paths.append(str(path))
    build = subprocess.run(["javac", "-d", directory, *paths],
                           capture_output=True, text=True, timeout=30)
    if build.returncode:
        raise AssertionError(build.stderr)


class StickerFirstFrameFadeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="sticker-first-frame-")
        cls.addClassCleanup(cls.temp.cleanup)
        compile_sources(cls.temp.name, source())

    def run_scenario(self, scenario):
        run = subprocess.run(["java", "-ea", "-cp", self.temp.name, "StickerFirstFrameHarness", scenario],
                             capture_output=True, text=True, timeout=15)
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)

    def test_delayed_first_frame_and_placeholder_continuity(self):
        self.run_scenario("cold")

    def test_late_bitmap_thumb_during_lottie_decode(self):
        self.run_scenario("late_thumb")

    def test_ready_cache_and_same_message_rebind_do_not_refade(self):
        self.run_scenario("cache")

    def test_cancel_reuse_detach_and_stale_delivery(self):
        self.run_scenario("lifecycle")

    def test_worker_publication_race(self):
        self.run_scenario("race")

    def test_sticker_only_opt_in_precedes_loading(self):
        cell = (JAVA / "ui/Cells/ChatMessageCell.java").read_text()
        start = cell.index("photoImage.setCrossfadeWithOldImage(false);")
        end = cell.index("photoImage.setGradientBitmap(null);", start)
        self.assertIn("photoImage.setCrossfadeOnReady(", cell[start:end])
        self.run_scenario("scope")

    def test_negative_controls(self):
        receiver, fixture = source()
        for label, sources, scenario, failure in [
            ("missing chat opt-in", (receiver, fixture.replace(
                "photoImage.setCrossfadeOnReady(messageObject.isRoundVideo()\n                    || messageObject.isAnyKindOfSticker() && !messageObject.isDice());",
                "photoImage.setCrossfadeOnReady(false);")), "cold", "first decoded sticker frame"),
            ("late thumb rejected", (receiver.replace(
                "if (crossfadeOnReadyDrawable == null\n                        && (currentImageDrawable",
                "if (true\n                        && (currentImageDrawable"), fixture),
             "late_thumb", "late thumb must not be rejected"),
        ]:
            with self.subTest(label=label), tempfile.TemporaryDirectory(prefix="sticker-fade-mutation-") as tmp:
                self.assertNotEqual(sources, (receiver, fixture), "mutation must change extracted code")
                compile_sources(tmp, sources)
                run = subprocess.run(["java", "-ea", "-cp", tmp, "StickerFirstFrameHarness", scenario],
                                     capture_output=True, text=True, timeout=15)
                self.assertNotEqual(run.returncode, 0, "regression mutation unexpectedly passed")
                self.assertIn("AssertionError: " + failure, run.stderr)


if __name__ == "__main__":
    unittest.main()
