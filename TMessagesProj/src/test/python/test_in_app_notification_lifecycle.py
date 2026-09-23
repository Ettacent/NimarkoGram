"""Focused source contracts for Android notification lifecycle edges.

Run with: python3 -m unittest TMessagesProj/src/test/python/test_in_app_notification_lifecycle.py
These checks do not build the Android app or simulate its rendering pipeline.
"""

from pathlib import Path
import re
import unittest


JAVA = Path(__file__).resolve().parents[2] / "main/java"
NOTIFICATIONS = (JAVA / "app/nimarkogram/messenger/notifications/NimarkoInAppNotifications.java").read_text()
PANEL = (JAVA / "app/nimarkogram/messenger/notifications/NotificationInlinePanel.java").read_text()
LIST_INSET = (JAVA / "app/nimarkogram/messenger/notifications/NotificationListInset.java").read_text()
SCROLL_INSET = (JAVA / "app/nimarkogram/messenger/notifications/NotificationScrollInset.java").read_text()
GLASS = (JAVA / "app/nimarkogram/messenger/notifications/NotificationGlassSurface.java").read_text()
CONTROLLER = (JAVA / "org/telegram/messenger/NotificationsController.java").read_text()


def method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 0
    for token in re.finditer(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|[{}]', source[opening:]):
        if token.group() == "{":
            depth += 1
        elif token.group() == "}":
            depth -= 1
            if depth == 0:
                return source[start:opening + token.end()]
    raise AssertionError(f"Unterminated method: {signature}")


class InAppNotificationLifecycleTest(unittest.TestCase):
    def test_appearance_has_attached_reveal_and_bounded_delivery(self):
        show = method(NOTIFICATIONS, "private static boolean show(Banner next)")
        self.assertLess(show.index("next.setAlpha(0f)"), show.index("Slot.obtain(panel).attach(next)"))
        self.assertLess(show.index("Slot.obtain(panel).attach(next)"), show.index("next.animate().alpha(1f)"))
        self.assertIn("next.postDelayed(next.watch, 250)", show)
        offer = method(NOTIFICATIONS, "public static boolean offer(")
        self.assertIn("if (!delivery.isActive()", offer)
        self.assertIn("now >= deadline + archiveWaitDuration", offer)
        self.assertIn("archivePullGestureInProgress()", offer)
        self.assertLess(offer.index("archivePullGestureInProgress()"), offer.index("presentationBusy()"))
        self.assertIn("if (!deferred) completion.accept(handled)", offer)
        self.assertIn("notificationsQueue.postRunnable(fallback, 5000)", CONTROLLER)

    def test_archive_pull_cannot_expire_existing_banner(self):
        watch = method(NOTIFICATIONS, "final Runnable watch = new Runnable()")
        self.assertRegex(watch, r"focused && !touching && !contentGesture && !archivePullGestureInProgress\(\)")
        self.assertIn("postDelayed(this, 250)", watch)

    def test_canceled_account_handoff_restores_card(self):
        open_chat = method(NOTIFICATIONS, "void openChat()")
        pause = method(NOTIFICATIONS, "void pauseInteraction()")
        self.assertIn("if (account == UserConfig.selectedAccount) removeCurrent()", open_chat)
        handoff = open_chat.index("activity.openInAppNotification(intent)")
        recovery = open_chat.index("if (banner == this) {", handoff)
        self.assertIn("pauseInteraction()", open_chat[recovery:])
        self.assertIn("navigationRequestCurrent = null", pause)
        self.assertIn("opening = false", pause)
        self.assertRegex(pause, r"\.alpha\(1f\)\.scaleX\(1f\)\.scaleY\(1f\)")
        self.assertIn("removeCurrent()", method(NOTIFICATIONS, "public static void dismiss()"))

    def test_overlay_detach_and_release_return_insets_to_host(self):
        release = method(PANEL, "public void release()")
        detached = method(PANEL, "protected void onDetachedFromWindow()")
        self.assertLess(release.index("released = true"), release.index("restoreContentInsets()"))
        self.assertIn("restoreContentInsets()", detached)
        self.assertIn("heightListener.accept(0)", release)
        self.assertIn("positionListener.accept(-1)", release)
        self.assertIn("return released || !attached", method(PANEL, "public float getAnimatedHeightWithPadding()"))
        self.assertIn("apply(0, 0, 0)", method(LIST_INSET, "public void release()"))
        self.assertIn("apply(0, 0, 0)", method(SCROLL_INSET, "void release()"))

    def test_glass_releases_detached_and_unavailable_sources(self):
        update = method(GLASS, "private void update(float elapsed)")
        self.assertRegex(update, r"current != null && !current\.sourceRoot\.isAttachedToWindow\(\)[\s\S]*?current\.release\(\)")
        self.assertRegex(update, r"incoming != null && \(!incoming\.sourceRoot\.isAttachedToWindow\(\)[\s\S]*?incoming\.factory != targetFactory")
        self.assertRegex(update, r"!navigating && activeAttached && targetFactory == null[\s\S]*?releaseLayers\(\)")
        self.assertIn("sourceRoot.getLocationOnScreen(origin)", method(GLASS, "void updateOffset()"))


if __name__ == "__main__":
    unittest.main()
