"""Focused JVM/source checks for media-viewer transition ownership.

These exercise the decisions without claiming to measure Android frame timing.
"""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_recording_composer_lifecycle import method


JAVA = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui"
PHOTO = (JAVA / "PhotoViewer.java").read_text()
MEDIA = (JAVA / "Components/SharedMediaLayout.java").read_text()
STORY = (JAVA / "Stories/StoryViewer.java").read_text()
PEER = (JAVA / "Stories/PeerStoriesView.java").read_text()


def run_java(source):
    with tempfile.TemporaryDirectory(prefix="media-navigation-handoff-") as directory:
        java = Path(directory) / "Transitions.java"
        java.write_text(source)
        compiled = subprocess.run(["javac", "-d", directory, str(java)],
                                  capture_output=True, text=True, timeout=30)
        if compiled.returncode != 0:
            raise AssertionError(compiled.stderr)
        run = subprocess.run(["java", "-ea", "-cp", directory, "Transitions"],
                             capture_output=True, text=True, timeout=30)
        if run.returncode != 0:
            raise AssertionError(run.stdout + run.stderr)


class MediaNavigationHandoffTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_shared_media_matches_full_identity_before_zoom(self):
        identity = method(MEDIA, "private static boolean isSameMediaMessage(")
        provider = method(MEDIA, "public PhotoViewer.PlaceProviderObject getPlaceForPhoto(")
        self.assertEqual(provider.count("isSameMediaMessage(message, messageObject)"), 4)
        self.assertIn("view.getBottom() <= 0 || view.getTop() >= visibleHeight", provider)
        self.assertLess(provider.index("int[] coords = new int[2];"),
                        provider.index("for (int a = 0, count = listView.getChildCount()"))
        run_java("""public class Transitions {
            static class MessageObject {
                int currentAccount, id; long dialogId;
                MessageObject(int account, long dialog, int id) {
                    currentAccount = account; dialogId = dialog; this.id = id;
                }
                int getId() { return id; }
                long getDialogId() { return dialogId; }
            }
            IDENTITY
            public static void main(String[] args) {
                MessageObject requested = new MessageObject(1, 100, 7);
                assert isSameMediaMessage(requested, new MessageObject(1, 100, 7));
                assert !isSameMediaMessage(requested, new MessageObject(1, 200, 7));
                assert !isSameMediaMessage(requested, new MessageObject(2, 100, 7));
                assert !isSameMediaMessage(requested, new MessageObject(1, 100, 8));
                assert !isSameMediaMessage(null, requested);
            }
        }""".replace("IDENTITY", identity))

    def test_photo_viewer_retires_deferred_receiver_visibility(self):
        opening = method(PHOTO, "private void onPhotoShow(")
        closed = method(PHOTO, "private void onPhotoClosed(")
        destroyed = method(PHOTO, "public void destroyPhotoViewer()")
        restore = method(PHOTO, "private void restoreDeferredPlaceReceivers()")
        self.assertIn("showAfterAnimation = null;", opening)
        self.assertIn("hideAfterAnimation = null;", opening)
        for body in (closed, destroyed):
            self.assertIn("restoreDeferredPlaceReceivers();", body)
        self.assertLess(restore.index("showAfterAnimation.imageReceiver.setVisible(true, true);"),
                        restore.index("showAfterAnimation = null;"))
        self.assertLess(restore.index("hideAfterAnimation.imageReceiver.setVisible(true, true);"),
                        restore.index("hideAfterAnimation = null;"))
        handoff = PHOTO[PHOTO.index("PlaceProviderObject show = showAfterAnimation;"):
                        PHOTO.index("if (photos != null && sendPhotoType != 3", PHOTO.index("PlaceProviderObject show = showAfterAnimation;"))]
        self.assertLess(handoff.index("showAfterAnimation = null;"),
                        handoff.index("show.imageReceiver.setVisible(true, true);"))
        self.assertLess(handoff.index("hideAfterAnimation = null;"),
                        handoff.index("hide.imageReceiver.setVisible(false, true);"))

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_story_video_retry_only_when_opened_and_unbound(self):
        ensure = method(PEER, "void ensureVideoPlayerAfterOpening()")
        animation = method(STORY, "private void startOpenAnimation()")
        self.assertLess(animation.index("progressToOpen = 1f;"),
                        animation.index("peerStoriesView.ensureVideoPlayerAfterOpening();"))
        run_java("""public class Transitions {
            static class Scope { Object player, livePlayer; }
            static class CurrentStory {
                boolean video, live;
                boolean isVideo() { return video; }
                boolean isLive() { return live; }
            }
            boolean isActive; int requests;
            Scope playerSharedScope = new Scope();
            CurrentStory currentStory = new CurrentStory();
            void requestVideoPlayer(long time) {
                requests++;
                if (currentStory.live) playerSharedScope.livePlayer = new Object();
                else playerSharedScope.player = new Object();
            }
            ENSURE
            public static void main(String[] args) {
                Transitions t = new Transitions();
                t.currentStory.video = true;
                t.ensureVideoPlayerAfterOpening(); assert t.requests == 0;
                t.isActive = true;
                t.ensureVideoPlayerAfterOpening(); assert t.requests == 1;
                t.ensureVideoPlayerAfterOpening(); assert t.requests == 1;
                t.currentStory.live = true;
                t.ensureVideoPlayerAfterOpening(); assert t.requests == 2;
                t.ensureVideoPlayerAfterOpening(); assert t.requests == 2;
            }
        }""".replace("ENSURE", ensure))

    def test_late_story_source_layout_cannot_hide_return_thumbnail(self):
        layout = method(STORY, "private void layoutAndFindView()")
        self.assertIn("final int layoutGeneration = ++transitionLayoutGeneration;", layout)
        self.assertIn("final ValueAnimator targetAnimation = openCloseAnimator;", layout)
        self.assertIn("final View targetWindow = windowView;", layout)
        self.assertIn("transitionLayoutGeneration != layoutGeneration", layout)
        self.assertLess(layout.index("openCloseAnimator != targetAnimation"),
                        layout.index("updateTransitionParams();", layout.index("placeProvider.preLayout(")))
        self.assertLess(layout.index("windowView != targetWindow"),
                        layout.index("transitionViewHolder.storyImage.setVisible(false, true);"))

    def test_story_preview_draw_restores_source_alpha_even_on_instant_close(self):
        draw = STORY[STORY.index("float oldAlpha = transitionViewHolder.storyImage.getAlpha();"):
                     STORY.index("transitionViewHolder.storyImage.setImageCoords(x, y, w, h);")]
        self.assertLess(draw.index("setAlpha(1f - progress2)"),
                        draw.index("setAlpha(oldAlpha)"))
        self.assertLess(draw.index("setAlpha(oldAlpha)"),
                        draw.index("setVisible(wasVisible, false)"))
        instant = method(STORY, "public void instantClose()")
        self.assertIn("transitionViewHolder.storyImage.setAlpha(1f);", instant)


if __name__ == "__main__":
    unittest.main()
