"""Source-level guards for quote, attachment, and share first-frame handoffs.

These are focused ordering checks; visual timing still requires a device run.
"""

from pathlib import Path
import re
import unittest


JAVA = Path(__file__).resolve().parents[2] / "main/java"
QUOTE = (JAVA / "app/nimarkogram/messenger/quotes/NimarkoQuoteCreator.java").read_text()
ATTACH = (JAVA / "org/telegram/ui/Components/ChatAttachAlert.java").read_text()
PHOTO = (JAVA / "org/telegram/ui/Components/ChatAttachAlertPhotoLayout.java").read_text()
SHARE = (JAVA / "org/telegram/ui/Components/ShareAlert.java").read_text()


def section(source, start, end):
    return source[source.index(start):source.index(end, source.index(start))]


class QuoteAttachShareFirstFrameTest(unittest.TestCase):
    def test_quote_busy_feedback_survives_export_handoff(self):
        export = section(QUOTE, "private void export(ExportAction action)", "private void waitForExportMedia(")
        self.assertLess(export.index("setBusy(true, action);"), export.index("completeAction(action, exportedFile);"))

        write = section(QUOTE, "private void writeBitmap(", "private boolean isExportValid(")
        self.assertNotIn("setBusy(false, null)", write)
        self.assertLess(write.index("exporting = false;"), write.index("completeAction(action, result);"))

        action = section(QUOTE, "private void completeAction(ExportAction action", "private boolean shouldSendAsDocument(")
        self.assertIn("setBusy(true, ExportAction.SAVE);", action)
        # The chooser handoff releases busy state even if launching it throws.
        # Comments/indentation are not part of that contract.
        code = re.sub(r'//[^\n]*|/\*[\s\S]*?\*/', '', action)
        self.assertRegex(code, r'finally\s*\{\s*setBusy\(false, null\);')

    def test_picker_visible_first_frame_is_seeded(self):
        layout = section(ATTACH, "private void showLayout(AttachAlertLayout layout, long newId, boolean animated)", "private void onCurrentLayoutAnimatorChanged(")
        seed = layout.index("if (toPhoto && nimarkoFloatingButton.getVisibility() != View.VISIBLE)")
        visible = layout.index("nimarkoFloatingButton.setVisibility(View.VISIBLE);", seed)
        for state in ("setAlpha(0f)", "setScaleX(0.2f)", "setScaleY(0.2f)", "setTranslationY(computeNimarkoFloatingCameraTranslationY())"):
            self.assertLess(layout.index("nimarkoFloatingButton." + state, seed), visible)

        camera = section(PHOTO, "public void showCamera(boolean force)", "public void hideCamera(boolean async)")
        self.assertIn("createdCameraView.setAlpha(cameraOpened ? 1f : 0f);", camera)
        self.assertNotIn("createdCameraView.postDelayed(", camera)
        self.assertNotIn("createdCameraView.setAlpha(mediaEnabled", camera)
        self.assertIn("ObjectAnimator.ofFloat(createdCameraView, View.ALPHA, 0.0f, 1.0f)", camera)

    def test_late_topics_fill_without_second_entry_animation(self):
        topic = section(SHARE, "if (isBotForumWithNotEmptyTopics(fUser)", "private boolean isBotForumWithNotEmptyTopics(")
        self.assertIn("if (selectedTopicDialog != dialog || isDismissed())", topic)
        self.assertIn("boolean enterTopics = topicsGridView.getVisibility() != View.VISIBLE", topic)
        self.assertIn("shareTopicsAdapter.notifyDataSetChanged();", topic)
        self.assertIn("if (enterTopics) {", topic)
        self.assertNotIn("if (animate) {", topic)
        self.assertGreaterEqual(SHARE.count("if (canceled || topicsAnimation != animation)"), 2)

    def test_late_share_dialogs_reveal_for_sync_and_filtered_loads(self):
        reload = section(SHARE, "if (id == NotificationCenter.dialogsNeedReload)", "private void updateDialogsWithFirstFrameReveal(")
        self.assertIn("updateDialogsWithFirstFrameReveal(listAdapter::fetchDialogs);", reload)
        self.assertIn("foldersView.applyFilter(", reload)

        reveal = section(SHARE, "private void updateDialogsWithFirstFrameReveal(", "protected boolean canDismissWithSwipe(")
        self.assertLess(reveal.index("gridView.setAlpha(0f);"), reveal.index("update.run();"))
        self.assertLess(reveal.index("update.run();"), reveal.index("gridView.animate().alpha(1f)"))
        self.assertIn("SharedConfig.animationsEnabled()", reveal)
        self.assertIn("listAdapter.getItemCount() == 0", reveal)

        filtered = section(SHARE, "private void applyFilter(int tabId)", "private boolean hasFolders()")
        self.assertIn("updateDialogsWithFirstFrameReveal(() -> listAdapter.setDialogs(filtered));", filtered)


if __name__ == "__main__":
    unittest.main()
